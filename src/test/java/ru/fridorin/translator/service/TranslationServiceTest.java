package ru.fridorin.translator.service;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import ru.fridorin.translator.config.TranslatorConfig;
import ru.fridorin.translator.config.TranslatorConfigManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class TranslationServiceTest {
    private HttpServer server;
    private String endpoint;

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @After
    public void stopServer() {
        server.stop(0);
    }

    @Test
    public void deeplFallbackHandlesServerErrorWithoutForwardingTheKey() throws Exception {
        AtomicReference<String> deeplAuth = new AtomicReference<>();
        AtomicReference<String> deeplBody = new AtomicReference<>();
        AtomicReference<String> googleAuth = new AtomicReference<>();
        AtomicReference<String> googleQuery = new AtomicReference<>();
        server.createContext("/api-free.deepl.com", exchange -> {
            deeplAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            deeplBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 503, "");
        });
        server.createContext("/translate.googleapis.com", exchange -> {
            googleAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            googleQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, "[[[\"[Guild] translated\",\"source\"],[\" correctly\",\"source\"]]]");
        });

        var translated = TranslationService.requestApiTranslation("é & a?", "DEEPL_FREE", "placeholder-key",
                "en", () -> true, this::sendLocally).get(5, TimeUnit.SECONDS);
        assertEquals("[Guild] translated correctly", translated.text());
        assertEquals("GOOGLE_FREE", translated.provider());
        assertEquals("DeepL-Auth-Key placeholder-key", deeplAuth.get());
        var deeplJson = JsonParser.parseString(deeplBody.get()).getAsJsonObject();
        assertEquals("EN-US", deeplJson.get("target_lang").getAsString());
        assertEquals("é & a?", deeplJson.getAsJsonArray("text").get(0).getAsString());
        assertNull(googleAuth.get());
        assertTrue(googleQuery.get().endsWith("q=%C3%A9+%26+a%3F"));
    }

    @Test
    public void configurationAndResponseErrorsDoNotStartFallback() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger status = new AtomicInteger(403);
        AtomicInteger googleCalls = new AtomicInteger();
        AtomicReference<String> response = new AtomicReference<>("private-response-data");
        server.createContext("/api-free.deepl.com", exchange -> {
            calls.incrementAndGet();
            respond(exchange, status.get(), response.get());
        });
        server.createContext("/translate.googleapis.com", exchange -> {
            googleCalls.incrementAndGet();
            respond(exchange, 200, "[[[\"translated\",\"source\"]]]");
        });

        assertFailure(translate("DEEPL_FREE", "", "sample", "ru", true), "Missing or invalid API key");
        assertEquals(0, calls.get());
        assertFailure(translate("DEEPL_FREE", "placeholder-key", "sample", "ru", true), "HTTP 403");
        status.set(456);
        assertFailure(translate("DEEPL_FREE", "placeholder-key", "sample", "ru", true), "HTTP 456");
        status.set(200);
        assertFailure(translate("DEEPL_FREE", "placeholder-key", "sample", "ru", true), "invalid translation");
        response.set("{'translations':[{'text':'private-response-data'}]}");
        assertFailure(translate("DEEPL_FREE", "placeholder-key", "sample", "ru", true), "invalid translation");
        response.set("{\"translations\":[{\"text\":\" \"}]}");
        assertFailure(translate("DEEPL_FREE", "placeholder-key", "sample", "ru", true), "invalid translation");
        status.set(503);
        assertFailure(translate("DEEPL_FREE", "placeholder-key", "sample", "ru", false), "HTTP 503");
        assertEquals(6, calls.get());
        assertEquals(0, googleCalls.get());
    }

    @Test
    public void honorsYandexRateLimitAndSendsTargetLanguage() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> payload = new AtomicReference<>();
        AtomicInteger googleCalls = new AtomicInteger();
        server.createContext("/translate.api.cloud.yandex.net", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (calls.incrementAndGet() == 1) {
                respond(exchange, 200, "{\"translations\":[{\"text\":\"Bonjour\"}]}");
            } else {
                exchange.getResponseHeaders().set("Retry-After", "60");
                respond(exchange, 429, "{}");
            }
        });
        server.createContext("/translate.googleapis.com", exchange -> {
            googleCalls.incrementAndGet();
            respond(exchange, 200, "[[[\"translated\",\"source\"]]]");
        });

        assertEquals("Bonjour", translate("YANDEX", "placeholder-key", "Hello", "fr", true)
                .get(5, TimeUnit.SECONDS));
        assertEquals("Api-Key placeholder-key", auth.get());
        var yandexJson = JsonParser.parseString(payload.get()).getAsJsonObject();
        assertEquals("fr", yandexJson.get("targetLanguageCode").getAsString());
        assertEquals("Hello", yandexJson.getAsJsonArray("texts").get(0).getAsString());
        assertFailure(translate("YANDEX", "placeholder-key", "other", "fr", true), "HTTP 429");
        assertFailure(translate("YANDEX", "placeholder-key", "another", "fr", true), "rate limiting");
        assertEquals(2, calls.get());
        assertEquals(0, googleCalls.get());
        assertEquals(10_000, TranslationService.retryDelayMillis("Thu, 01 Jan 1970 00:00:10 GMT", 0));
        assertEquals(30_000, TranslationService.retryDelayMillis("invalid", 0));
    }

    @Test
    public void incompleteResponseTimesOutAndReleasesCapacity() throws Exception {
        CountDownLatch finish = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('a');
                exchange.getResponseBody().flush();
                finish.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/slow"))
                    .timeout(Duration.ofMillis(500)).build();
            assertFailure(TranslationService.sendLimitedRequest(request), "timed out");
        } finally {
            finish.countDown();
        }
        assertConcurrencyLimit();
    }

    private void assertConcurrencyLimit() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/hold", exchange -> {
            try {
                release.await(3, TimeUnit.SECONDS);
                respond(exchange, 200, "ok");
            } catch (InterruptedException error) {
                exchange.close();
                Thread.currentThread().interrupt();
            }
        });
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/hold")).build();
        var first = TranslationService.sendLimitedRequest(request);
        var second = TranslationService.sendLimitedRequest(request);
        var third = TranslationService.sendLimitedRequest(request);
        try {
            assertFailure(TranslationService.sendLimitedRequest(request), "busy");
        } finally {
            release.countDown();
        }
        assertEquals("ok", first.get(5, TimeUnit.SECONDS).body());
        assertEquals("ok", second.get(5, TimeUnit.SECONDS).body());
        assertEquals("ok", third.get(5, TimeUnit.SECONDS).body());
    }

    @Test
    public void oversizedResponseIsRejected() throws Exception {
        server.createContext("/large", exchange -> respond(exchange, 200, "a".repeat(70 * 1024)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/large")).build();
        assertFailure(TranslationService.sendLimitedRequest(request), "Invalid translation response");
    }

    @Test
    public void preservesPlaceholderMultiplicityAndLineBreaks() {
        String original = "{F0}Hello {P0}\n{C0} {0}";
        assertEquals("{F0}Bonjour {P0}\n{C0} {0}",
                TranslationService.normalizeProviderTranslation(original, "[ ф 0 ]Bonjour {p0}\n(C0) [ 0 ]"));
        assertNull(TranslationService.normalizeProviderTranslation(original, "{F0}Bonjour {P0} {C0} {0}"));
        assertNull(TranslationService.normalizeProviderTranslation(original, "{F0}Bonjour {P0}\n{C0}"));
        assertNull(TranslationService.normalizeProviderTranslation(original, "{F0}Bonjour {P0}\n{C0} {0}{0}"));
    }

    @Test
    public void outgoingChatFallsBackToOriginalOnInvalidTranslation() {
        String original = "Привет";
        assertEquals("Hello!", TranslationService.outgoingTextOrOriginal(original, " Hello! "));
        assertEquals(original, TranslationService.outgoingTextOrOriginal(original, "/command"));
        assertEquals(original, TranslationService.outgoingTextOrOriginal(original, "Hello\nworld"));
        assertEquals(original, TranslationService.outgoingTextOrOriginal(original, "§eHello"));
        assertEquals(original, TranslationService.outgoingTextOrOriginal(original, "a".repeat(257)));
        assertTrue(TranslationService.containsLetters("𐐀"));
        assertTrue(TranslationService.containsNonEnglishLetters("𐐀"));
    }

    @Test
    public void recognizesEnchantmentLists() {
        assertTrue(TranslationService.ENCHANTMENT_LINE_PATTERN.matcher("Sharpness VI, Critical  Hit IV, Bane of Arthropods III").matches());
        assertFalse(TranslationService.ENCHANTMENT_LINE_PATTERN.matcher("Sharpness VI and other enchantments").matches());
        assertFalse(TranslationService.ENCHANTMENT_LINE_PATTERN.matcher("Sharpness 8").matches());
    }

    @Test
    public void respectsIpFilterForBareIpv4WithPunctuation() {
        TranslatorConfig config = TranslatorConfigManager.getConfig();
        boolean previous = config.ignoreUrlsAndIps;
        try {
            config.ignoreUrlsAndIps = true;
            assertTrue(TranslationService.isTechnicalOrModLine("Sample server address: 192.0.2.15."));
            assertFalse(TranslationService.isTechnicalOrModLine("Sample invalid address: 192.0.2.999."));
            config.ignoreUrlsAndIps = false;
            assertFalse(TranslationService.isTechnicalOrModLine("Sample server address: 192.0.2.15."));
        } finally {
            config.ignoreUrlsAndIps = previous;
        }
    }

    @Test
    public void localOnlyAndDisabledChannelsDoNotRequestProviderTranslations() throws Exception {
        var config = TranslatorConfigManager.getConfig();
        boolean local = config.localOnly;
        boolean npc = config.onlineNpc;
        try {
            config.localOnly = true;
            String sample = "Unique sample for channel isolation";
            for (var scope : TranslationScope.values()) {
                assertFalse(scope.allowsOnline(config));
                assertEquals(sample, TranslationService.translateAsync(sample, scope).get(1, TimeUnit.SECONDS));
            }
            assertEquals(TranslationService.FailureCategory.LOCAL_ONLY,
                    TranslationService.testConnectionAsync("DEEPL_FREE", "mock", false).get());
            config.localOnly = false;
            config.onlineNpc = false;
            assertEquals(sample, TranslationService.translateAsync(sample, TranslationScope.NPC).get(1, TimeUnit.SECONDS));
            assertTrue(TranslationScope.CHAT.allowsOnline(config));
        } finally {
            config.localOnly = local;
            config.onlineNpc = npc;
        }
    }

    private CompletableFuture<String> translate(String provider, String key, String text, String language, boolean fallback) {
        return TranslationService.requestApiTranslation(text, provider, key, language, () -> fallback, this::sendLocally)
                .thenApply(TranslationService.ProviderTranslation::text);
    }

    private CompletableFuture<HttpResponse<String>> sendLocally(HttpRequest source) {
        String query = source.uri().getRawQuery();
        URI uri = URI.create(endpoint + "/" + source.uri().getHost() + (query == null ? "" : "?" + query));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .method(source.method(), source.bodyPublisher().orElseGet(HttpRequest.BodyPublishers::noBody));
        source.headers().map().forEach((name, values) -> values.forEach(value -> request.header(name, value)));
        source.timeout().ifPresent(request::timeout);
        return TranslationService.sendLimitedRequest(request.build());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static void assertFailure(CompletableFuture<?> request, String expected) throws Exception {
        ExecutionException error = assertThrows(ExecutionException.class, () -> request.get(5, TimeUnit.SECONDS));
        assertTrue(error.getCause().getMessage(), error.getCause().getMessage().contains(expected));
        assertFalse(error.getCause().getMessage().contains("private-response-data"));
    }
}
