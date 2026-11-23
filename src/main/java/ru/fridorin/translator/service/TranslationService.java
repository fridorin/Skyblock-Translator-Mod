package ru.fridorin.translator.service;

import ru.fridorin.translator.cache.TranslationCache;
import ru.fridorin.translator.config.TranslatorConfig;
import ru.fridorin.translator.config.TranslatorConfigManager;
import ru.fridorin.translator.util.BoundedHttpBody;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TranslationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TranslationService.class);

    // Bounded executor to prevent unbounded thread growth from HTTP requests
    private static final java.util.concurrent.ExecutorService HTTP_EXECUTOR = java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "SkyblockTranslator-HTTP");
        t.setDaemon(true);
        return t;
    });

    public static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .executor(HTTP_EXECUTOR)
            .build();

    private static final Semaphore API_SEMAPHORE = new Semaphore(3);
    private static final int MAX_ACTIVE_TRANSLATIONS = 32;
    static final int MAX_INPUT_LENGTH = 4000;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);
    private static final Pattern LANGUAGE_PATTERN = Pattern.compile("[a-z]{2,3}(?:-[a-z0-9]{2,8})*");
    private static final Gson PROVIDER_JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    private static final ConcurrentHashMap<String, CompletableFuture<String>> ACTIVE_TRANSLATIONS = new ConcurrentHashMap<>();
    private static final Cache<String, Boolean> FAILED_TRANSLATIONS = CacheBuilder.newBuilder()
            .maximumSize(1024)
            .expireAfterWrite(3, TimeUnit.SECONDS)
            .build();
    private static volatile boolean shuttingDown;

    private static final ThreadLocal<Boolean> BYPASS_TRANSLATION = ThreadLocal.withInitial(() -> false);

    public static void runWithBypass(Runnable action) {
        boolean previous = BYPASS_TRANSLATION.get();
        BYPASS_TRANSLATION.set(true);
        try { action.run(); } finally { BYPASS_TRANSLATION.set(previous); }
    }

    public static <T> T callWithBypass(java.util.function.Supplier<T> action) {
        boolean previous = BYPASS_TRANSLATION.get();
        BYPASS_TRANSLATION.set(true);
        try { return action.get(); } finally { BYPASS_TRANSLATION.set(previous); }
    }

    public static boolean isBypassed() { return BYPASS_TRANSLATION.get(); }

    private static final Pattern DAMAGE_INDICATOR_CLEANUP = Pattern.compile("crit|[0-9.,\\s+\\-kmb%]|[✧❤⚡❈⚔☠]", Pattern.CASE_INSENSITIVE);

    public static boolean isDamageIndicator(String cleanText) {
        if (cleanText == null) return false;
        String lower = cleanText.toLowerCase(java.util.Locale.ROOT).trim();
        if (lower.isEmpty()) return false;
        String stripped = DAMAGE_INDICATOR_CLEANUP.matcher(lower).replaceAll("");
        return stripped.isEmpty();
    }

    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final String IPV4_OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[0-9]{1,2})";
    private static final Pattern IPV4 = Pattern.compile("(?<![\\w.])(?:" + IPV4_OCTET + "\\.){3}" + IPV4_OCTET + "(?!\\w|\\.\\w)");
    private static final Pattern DUNGEON_STATS = Pattern.compile("^(?:secrets found|dungeon score|crypts|deaths):");
    private static final Pattern LOBBY_STATUS = Pattern.compile("\\b(?:joined|left) (?:the )?(?:lobby|game)[.!]?$");
    private static final Pattern DATE_OR_LOBBY = Pattern.compile("(?:\\d{1,2}/\\d{1,2}/\\d{2,4}|(?:mini|mega)\\d+[a-z])");
    private static final Pattern TECHNICAL_CLEANUP = Pattern.compile("(?i)\\b_[fpcFPC]?\\d+_\\b|\\{F\\d+\\}|\\{P\\d+\\}|\\{C\\d+\\}|\\{\\d+\\}");

    public static boolean isTechnicalOrModLine(String text) {
        if (text == null) return true;
        String clean = TECHNICAL_CLEANUP.matcher(FORMATTING_PATTERN.matcher(text).replaceAll("")).replaceAll("");
        
        String lower = clean.toLowerCase(java.util.Locale.ROOT).trim();
        if (lower.isEmpty()) return true;

        TranslatorConfig config = TranslatorConfigManager.getConfig();
        if (config.ignoreUuidsAndIds && (lower.contains("uuid:") || lower.contains("profile id:") || lower.contains("coop id:") || UUID_PATTERN.matcher(lower).find())) return true;
        if (config.ignoreUrlsAndIps && (lower.contains("hypixel.net") || lower.contains("http://")
                || lower.contains("https://") || IPV4.matcher(lower).find())) return true;
        if (config.ignoreModPrefixes && (lower.contains("neu:") || lower.contains("skytils:") || lower.contains("sba:"))) return true;
        
        if (config.ignoreAbilityCooldowns && (lower.startsWith("cooldown:") || lower.startsWith("ability cooldown:"))) return true;
        if (config.ignoreDamageIndicators && isDamageIndicator(clean)) return true;
        if (config.ignoreDungeonStats && DUNGEON_STATS.matcher(lower).find()) return true;
        if (config.ignoreSlayerAlerts && (lower.startsWith("slayer quest") || lower.startsWith("boss slain"))) return true;
        if (config.ignoreLobbyJoinLeft && LOBBY_STATUS.matcher(lower).find()) return true;
        if (config.ignoreClickActions && (lower.startsWith("click to ") || lower.startsWith("right-click to ") || lower.startsWith("left-click to "))) return true;
        if (config.ignoreBazaarAndBin && (lower.startsWith("[bazaar]") || lower.startsWith("[auction]"))) return true;
        if (config.ignoreDatesAndLobbies && DATE_OR_LOBBY.matcher(lower).matches()) return true;

        if (lower.startsWith("/")) return true;

        return false;
    }

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\{[FPC]\\d+\\}|(\\d{1,2}:\\d{2}(?::\\d{2})?|\\d{1,2}/\\d{1,2}/\\d{2,4}|[+-]?\\d+(?:[.,]\\d+)*(?:st|nd|rd|th|ST|ND|RD|TH|[kKM%])?)");
    // Try the separator only at the start of each whitespace run.
    private static final String ENCHANTMENT = "[A-Za-z0-9\\s'-]+(?<!\\s)\\s++[IVXLCDM]++(?=,|$)";
    public static final Pattern ENCHANTMENT_LINE_PATTERN = Pattern.compile("^" + ENCHANTMENT + "(?:,\\s++" + ENCHANTMENT + ")*+$");
    public static final Pattern NAMESPACE_PATTERN = Pattern.compile("^(?i)(minecraft|skyblock)\\s*:\\s*.+$");
    private static final Pattern FORMATTING_PATTERN = Pattern.compile("§x(?:§[0-9a-fA-F]){6}|§.", Pattern.CASE_INSENSITIVE);

    public static String stripFormatting(String text) {
        return text.indexOf('§') < 0 ? text : FORMATTING_PATTERN.matcher(text).replaceAll("");
    }
    private static final Pattern NON_ALPHANUMERIC_PATTERN = Pattern.compile("[^\\p{L}\\p{N}]");

    public static class FormattingTemplateResult {
        public final String templatedText;
        public final List<String> formattingCodes;
        public FormattingTemplateResult(String templatedText, List<String> formattingCodes) {
            this.templatedText = templatedText;
            this.formattingCodes = formattingCodes;
        }
    }

    public static FormattingTemplateResult extractFormatting(String text) {
        List<String> codes = new ArrayList<>();
        if (text == null) return new FormattingTemplateResult("", codes);
        Matcher matcher = FORMATTING_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (matcher.find()) {
            codes.add(matcher.group());
            matcher.appendReplacement(sb, "{F" + count + "}");
            count++;
        }
        matcher.appendTail(sb);
        return new FormattingTemplateResult(sb.toString(), codes);
    }

    private static final Pattern[] RESTORE_FORMATTING_PATTERNS = new Pattern[32];
    private static final Pattern[] RESTORE_TEMPLATE_PATTERNS = new Pattern[32];
    static {
        for (int i = 0; i < 32; i++) {
            RESTORE_FORMATTING_PATTERNS[i] = Pattern.compile("(?:\\{\\s*|\\b_?|[\\(\\[])[fFфФ]\\s*_*\\s*" + i + "\\s*_*\\s*(?:\\s*\\}|_?\\b|[\\)\\]])");
            RESTORE_TEMPLATE_PATTERNS[i] = Pattern.compile("(?:\\{\\s*|[\\(\\[])" + i + "\\s*(?:\\s*\\}|[\\)\\]])");
        }
    }

    public static String restoreFormatting(String text, List<String> codes) {
        if (text == null) return null;
        String result = text;
        for (int i = 0; i < codes.size(); i++) {
            Pattern p = i < 32 ? RESTORE_FORMATTING_PATTERNS[i] : Pattern.compile("(?:\\{\\s*|\\b_?|[\\(\\[])[fFфФ]\\s*_*\\s*" + i + "\\s*_*\\s*(?:\\s*\\}|_?\\b|[\\)\\]])");
            result = p.matcher(result).replaceAll(Matcher.quoteReplacement(codes.get(i)));
        }
        return result;
    }

    public static boolean isTranslationSame(String original, String translated) {
        if (original == null || translated == null) return true;
        String cleanOrig = NON_ALPHANUMERIC_PATTERN.matcher(FORMATTING_PATTERN.matcher(original).replaceAll("")).replaceAll("").toLowerCase(java.util.Locale.ROOT);
        String cleanTrans = NON_ALPHANUMERIC_PATTERN.matcher(FORMATTING_PATTERN.matcher(translated).replaceAll("")).replaceAll("").toLowerCase(java.util.Locale.ROOT);
        return cleanOrig.equals(cleanTrans);
    }

    public static class TemplateResult {
        public final String templatedText;
        public final List<String> values;
        public TemplateResult(String templatedText, List<String> values) {
            this.templatedText = templatedText;
            this.values = values;
        }
    }

    public static TemplateResult extractTemplates(String text) {
        List<String> values = new ArrayList<>();
        if (text == null) return new TemplateResult("", values);
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (matcher.find()) {
            if (matcher.group(1) == null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            values.add(matcher.group(1));
            matcher.appendReplacement(sb, "{" + count + "}");
            count++;
        }
        matcher.appendTail(sb);
        return new TemplateResult(sb.toString(), values);
    }

    public static String restoreTemplates(String templatedText, List<String> values) {
        if (templatedText == null) return null;
        String result = templatedText;
        for (int i = 0; i < values.size(); i++) {
            result = result.replace("{" + i + "}", values.get(i));
            Pattern p = i < 32 ? RESTORE_TEMPLATE_PATTERNS[i] : Pattern.compile("(?:\\{\\s*|[\\(\\[])" + i + "\\s*(?:\\s*\\}|[\\)\\]])");
            result = p.matcher(result).replaceAll(Matcher.quoteReplacement(values.get(i)));
        }
        return result;
    }

    public static String normalizePlaceholders(String text) {
        if (text == null) return null;
        return text.replaceAll("[\\[\\{\\(]\\s*(\\d+)\\s*[\\}\\]\\)]", "{$1}");
    }

    private static final Pattern PUNCTUATION_SPACE_1 = Pattern.compile("[ \t]+(\\)|\\]|\\})");
    private static final Pattern PUNCTUATION_SPACE_2 = Pattern.compile("(\\(|\\[|\\{)[ \t]+");
    private static final Pattern PUNCTUATION_SPACE_3 = Pattern.compile("[ \t]+([.,!?:;])");
    private static final Pattern HORIZONTAL_SPACES = Pattern.compile("[ \t]+");
    private static final Pattern PROTECTED_TOKEN = Pattern.compile("\\{[FPC]?\\d+}");
    private static final Pattern PROVIDER_TOKEN = Pattern.compile("[{\\[(][ \t]*([fFpPcCфФ]?)[ \t]*(\\d+)[ \t]*[}\\])]");

    public static String cleanPunctuationSpaces(String text) {
        if (text == null) return null;
        String r = PUNCTUATION_SPACE_1.matcher(text).replaceAll("$1");
        r = PUNCTUATION_SPACE_2.matcher(r).replaceAll("$1");
        return PUNCTUATION_SPACE_3.matcher(r).replaceAll("$1");
    }

    static String normalizeProviderTranslation(String original, String translated) {
        String normalized = PROVIDER_TOKEN.matcher(translated).replaceAll(match -> {
            String prefix = match.group(1).toUpperCase(Locale.ROOT).replace('Ф', 'F');
            return "{" + prefix + match.group(2) + "}";
        });
        if (!protectedTokens(original).equals(protectedTokens(normalized))) return null;
        if (!lineBreaks(original).equals(lineBreaks(normalized))) return null;
        return cleanPunctuationSpaces(normalized);
    }

    private static String lineBreaks(String text) {
        StringBuilder breaks = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') breaks.append(c);
        }
        return breaks.toString();
    }

    private static Map<String, Integer> protectedTokens(String text) {
        Map<String, Integer> counts = new HashMap<>();
        Matcher matcher = PROTECTED_TOKEN.matcher(text);
        while (matcher.find()) counts.merge(matcher.group(), 1, Integer::sum);
        return counts;
    }

    public static String getImmediateTranslation(String text, TranslationScope scope) {
        if (text == null || text.length() > MAX_INPUT_LENGTH || text.trim().isEmpty()) return null;

        // Normalize double spaces that may be introduced by componentToLegacy serialization
        String normalizedText = HORIZONTAL_SPACES.matcher(text).replaceAll(" ").trim();

        FormattingTemplateResult fmtTemplate = extractFormatting(normalizedText);
        PlayerTemplateResult playerTemplate = templatePlayerNames(fmtTemplate.templatedText);
        CommandTemplateResult commandTemplate = templateCommands(playerTemplate.templatedText);

        String dictMatch = SkyblockDictionary.lookup(commandTemplate.templatedText);
        if (dictMatch != null) {
            String res = restoreCommands(dictMatch, commandTemplate.commands);
            res = restorePlayerNames(res, playerTemplate.playerNames);
            return restoreFormatting(res, fmtTemplate.formattingCodes);
        }

        TemplateResult template = extractTemplates(commandTemplate.templatedText);
        dictMatch = SkyblockDictionary.lookup(template.templatedText);
        if (dictMatch != null) {
            String res = restoreTemplates(dictMatch, template.values);
            res = restoreCommands(res, commandTemplate.commands);
            res = restorePlayerNames(res, playerTemplate.playerNames);
            return restoreFormatting(res, fmtTemplate.formattingCodes);
        }

        String cached = TranslationCache.get(template.templatedText, scope);
        if (cached != null) {
            String res = restoreTemplates(cached, template.values);
            res = restoreCommands(res, commandTemplate.commands);
            res = restorePlayerNames(res, playerTemplate.playerNames);
            return restoreFormatting(res, fmtTemplate.formattingCodes);
        }

        return null;
    }

    public static CompletableFuture<String> translateAsync(String text, TranslationScope scope) {
        TranslatorConfig config = TranslatorConfigManager.getConfig();
        final String targetLanguage = config.targetLanguage;
        final long generation = ru.fridorin.translator.TranslatorModClient.getCacheGeneration();
        if (!config.enabled || text == null || text.trim().isEmpty()) return CompletableFuture.completedFuture("");
        if ("en".equalsIgnoreCase(targetLanguage) || text.length() > MAX_INPUT_LENGTH) return CompletableFuture.completedFuture(text);

        FormattingTemplateResult fmtResult = extractFormatting(text);

        PlayerTemplateResult playerTemplate = templatePlayerNames(fmtResult.templatedText);
        CommandTemplateResult commandTemplate = templateCommands(playerTemplate.templatedText);
        String textWithoutCommands = commandTemplate.templatedText;

        if ((!config.translateEnchantments && ENCHANTMENT_LINE_PATTERN.matcher(textWithoutCommands.trim()).matches()) || NAMESPACE_PATTERN.matcher(textWithoutCommands.trim()).matches()) return CompletableFuture.completedFuture(text);
        if (!containsTranslatableLetters(textWithoutCommands)) return CompletableFuture.completedFuture(text);

        final TemplateResult template;
        String exactDictMatch = SkyblockDictionary.lookup(textWithoutCommands);
        CompletableFuture<String> translationFuture;
        if (exactDictMatch != null) {
            translationFuture = CompletableFuture.completedFuture(exactDictMatch);
            template = new TemplateResult(textWithoutCommands, new ArrayList<>());
        } else {
            template = extractTemplates(textWithoutCommands);
            String dictMatch = SkyblockDictionary.lookup(template.templatedText);
            if (dictMatch != null) {
                translationFuture = CompletableFuture.completedFuture(dictMatch);
            } else {
                String cached = TranslationCache.get(template.templatedText, scope);
                if (cached != null) {
                    translationFuture = CompletableFuture.completedFuture(cached);
                } else {
                    // Check isTechnicalOrModLine ONLY before API call — dictionary/cache lookups above must still work
                    if (!scope.allowsOnline(config) || isTechnicalOrModLine(fmtResult.templatedText)) return CompletableFuture.completedFuture(text);
                    String trimmedTemplate = template.templatedText.trim();
                    translationFuture = requestIncomingTranslation(trimmedTemplate, config, scope, targetLanguage, generation);
                }
            }
        }

        return translationFuture.thenApply(translatedTemplate -> {
            if (generation != ru.fridorin.translator.TranslatorModClient.getCacheGeneration()) return text;
            String res = restoreTemplates(translatedTemplate, template.values);
            res = restoreCommands(res, commandTemplate.commands);
            res = restorePlayerNames(res, playerTemplate.playerNames);
            return restoreFormatting(res, fmtResult.formattingCodes);
        });
    }

    private static CompletableFuture<String> requestIncomingTranslation(String text, TranslatorConfig config,
                                                                          TranslationScope scope, String targetLanguage, long generation) {
        String provider = config.apiProvider;
        String requestKey = generation + ":" + scope + ":" + provider + ":" + targetLanguage + ":" + text;
        CompletableFuture<String> pending;
        synchronized (ACTIVE_TRANSLATIONS) {
            CompletableFuture<String> existing = ACTIVE_TRANSLATIONS.get(requestKey);
            if (existing != null) return existing;
            if (shuttingDown || FAILED_TRANSLATIONS.getIfPresent(requestKey) != null) {
                return CompletableFuture.completedFuture("");
            }
            if (ACTIVE_TRANSLATIONS.size() >= MAX_ACTIVE_TRANSLATIONS) {
                lastError = FailureCategory.BUSY;
                return CompletableFuture.completedFuture("");
            }
            pending = new CompletableFuture<>();
            ACTIVE_TRANSLATIONS.put(requestKey, pending);
        }

        BooleanSupplier isCurrent = () -> !shuttingDown && config.enabled
                && generation == ru.fridorin.translator.TranslatorModClient.getCacheGeneration()
                && scope.allowsOnline(config) && provider.equals(config.apiProvider)
                && targetLanguage.equals(TranslatorConfigManager.getConfig().targetLanguage);
        requestApiTranslation(text, provider, config.keyFor(provider), targetLanguage,
                () -> config.allowGoogleFallback && isCurrent.getAsBoolean(), TranslationService::sendLimitedRequest)
                .thenApply(response -> {
                    if (!isCurrent.getAsBoolean()) return null;
                    String result = normalizeProviderTranslation(text, response.text());
                    if (result != null) {
                        TranslationCache.put(text, result, targetLanguage, generation, response.provider(), scope);
                    }
                    return result;
                })
                .whenComplete((result, error) -> {
                    boolean success = error == null && result != null && isCurrent.getAsBoolean();
                    if (!success && isCurrent.getAsBoolean()) {
                        FAILED_TRANSLATIONS.put(requestKey, true);
                        lastError = error == null ? FailureCategory.RESPONSE : failureCategory(error);
                    }
                    ACTIVE_TRANSLATIONS.remove(requestKey, pending);
                    pending.complete(success ? result : "");
                });
        return pending;
    }

    private enum Provider {
        GOOGLE_FREE("Google"), DEEPL_FREE("DeepL"), YANDEX("Yandex");

        private final String displayName;
        private final AtomicLong retryAfterMillis = new AtomicLong();

        Provider(String displayName) {
            this.displayName = displayName;
        }
    }

    public enum FailureCategory {
        NONE, LOCAL_ONLY, CONFIGURATION, AUTHORIZATION, RATE_LIMIT, QUOTA, TIMEOUT, NETWORK, SERVER, RESPONSE, BUSY
    }

    private static volatile FailureCategory lastError = FailureCategory.NONE;

    public static FailureCategory lastError() {
        return lastError;
    }

    public static net.minecraft.network.chat.Component failureLabel(FailureCategory category) {
        return net.minecraft.network.chat.Component.translatable(
                "gui.skyblock_translator.result." + category.name().toLowerCase(Locale.ROOT));
    }

    public static int pendingRequests() {
        return ACTIVE_TRANSLATIONS.size();
    }

    private static FailureCategory failureCategory(Throwable error) {
        return unwrap(error) instanceof ProviderFailure failure ? failure.category : FailureCategory.RESPONSE;
    }

    private static final class ProviderFailure extends RuntimeException {
        private final FailureCategory category;
        private final boolean retryWithFallback;

        private ProviderFailure(String message, FailureCategory category) {
            super(message);
            this.category = category;
            this.retryWithFallback = category == FailureCategory.NETWORK || category == FailureCategory.TIMEOUT
                    || category == FailureCategory.SERVER;
        }
    }

    record ProviderTranslation(String text, String provider) {}

    static CompletableFuture<ProviderTranslation> requestApiTranslation(String text, String providerName,
            String key, String targetLang, BooleanSupplier allowFallback,
            Function<HttpRequest, CompletableFuture<HttpResponse<String>>> transport) {
        Provider provider;
        try {
            provider = Provider.valueOf(providerName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException error) {
            return CompletableFuture.failedFuture(new ProviderFailure("Unknown translation provider", FailureCategory.CONFIGURATION));
        }
        return sendProvider(provider, text, targetLang, key, transport)
                .thenApply(value -> new ProviderTranslation(value, provider.name()))
                .exceptionallyCompose(error -> {
                    Throwable cause = unwrap(error);
                    if (provider != Provider.GOOGLE_FREE && cause instanceof ProviderFailure failure) {
                        lastError = failure.category;
                        if (failure.retryWithFallback && allowFallback.getAsBoolean()) {
                            return sendProvider(Provider.GOOGLE_FREE, text, targetLang, null, transport)
                                    .thenApply(value -> new ProviderTranslation(value, Provider.GOOGLE_FREE.name()));
                        }
                    }
                    return CompletableFuture.failedFuture(cause);
                });
    }

    public static CompletableFuture<FailureCategory> testConnectionAsync(String provider, String key, boolean localOnly) {
        if (localOnly || TranslatorConfigManager.getConfig().localOnly) {
            return CompletableFuture.completedFuture(FailureCategory.LOCAL_ONLY);
        }
        return requestApiTranslation("Hello", provider, key, "fr", () -> false, TranslationService::sendLimitedRequest)
                .handle((value, error) -> {
                    FailureCategory category = error == null ? FailureCategory.NONE : failureCategory(error);
                    if (error != null) lastError = category;
                    return category;
                });
    }

    public static CompletableFuture<String> translateOutgoingAsync(String text) {
        TranslatorConfig config = TranslatorConfigManager.getConfig();
        if (!config.enabled || !TranslationScope.OUTGOING.allowsOnline(config) || text == null
                || text.isBlank() || text.stripLeading().startsWith("/")) {
            return CompletableFuture.completedFuture(text);
        }
        long generation = ru.fridorin.translator.TranslatorModClient.getCacheGeneration();
        String provider = config.apiProvider;
        BooleanSupplier isCurrent = () -> !shuttingDown && config.enabled && TranslationScope.OUTGOING.allowsOnline(config)
                && generation == ru.fridorin.translator.TranslatorModClient.getCacheGeneration();
        return requestApiTranslation(text.trim(), provider, config.keyFor(provider), "en",
                        () -> config.allowGoogleFallback && isCurrent.getAsBoolean(), TranslationService::sendLimitedRequest)
                .thenApply(translated -> isCurrent.getAsBoolean() ? outgoingTextOrOriginal(text, translated.text()) : text)
                .exceptionally(error -> {
                    if (isCurrent.getAsBoolean()) lastError = failureCategory(error);
                    return text;
                });
    }

    static String outgoingTextOrOriginal(String original, String translated) {
        String cleaned = cleanPunctuationSpaces(translated).strip();
        if (cleaned.isEmpty() || cleaned.length() > 256 || cleaned.startsWith("/")) return original;
        boolean invalid = cleaned.codePoints().anyMatch(c -> !isValidTranslatedCharacter(c) || Character.isISOControl(c)
                || c == 0x2028 || c == 0x2029);
        if (invalid) return original;
        return cleaned;
    }

    static CompletableFuture<HttpResponse<String>> sendLimitedRequest(HttpRequest request) {
        if (shuttingDown || !API_SEMAPHORE.tryAcquire()) {
            return CompletableFuture.failedFuture(new ProviderFailure("Translator is busy", FailureCategory.BUSY));
        }

        try {
            AtomicReference<HttpResponse.ResponseInfo> receivedHeaders = new AtomicReference<>();
            CompletableFuture<HttpResponse<String>> exchange = HTTP_CLIENT.sendAsync(request, info -> {
                receivedHeaders.set(info);
                return BoundedHttpBody.ofUtf8String(MAX_RESPONSE_BYTES).apply(info);
            });
            long timeoutMillis = request.timeout().orElse(REQUEST_TIMEOUT).toMillis();
            // Time the copied future so a timeout can cancel the original HTTP exchange, including its body.
            return exchange.copy().orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).handle((response, error) -> {
                if (error == null) return response;
                exchange.cancel(true);
                HttpResponse.ResponseInfo info = receivedHeaders.get();
                if (info != null && info.statusCode() != 200) {
                    throw new ResponseStatus(info.statusCode(), info.headers());
                }
                Throwable cause = unwrap(error);
                if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) {
                    throw new ProviderFailure("Translation request timed out", FailureCategory.TIMEOUT);
                }
                if (info == null) throw new ProviderFailure("Translation service is unreachable", FailureCategory.NETWORK);
                throw new ProviderFailure("Invalid translation response", FailureCategory.RESPONSE);
            }).whenComplete((response, error) -> API_SEMAPHORE.release());
        } catch (RuntimeException error) {
            API_SEMAPHORE.release();
            return CompletableFuture.failedFuture(new ProviderFailure("Unable to start translation request", FailureCategory.NETWORK));
        }
    }

    private static final class ResponseStatus extends RuntimeException {
        private final int code;
        private final HttpHeaders headers;

        private ResponseStatus(int code, HttpHeaders headers) {
            this.code = code;
            this.headers = headers;
        }
    }

    private static CompletableFuture<String> sendProvider(Provider provider, String text, String language, String key,
                                                          Function<HttpRequest, CompletableFuture<HttpResponse<String>>> transport) {
        if (text.length() > MAX_INPUT_LENGTH) {
            return CompletableFuture.failedFuture(new ProviderFailure("Text is too long to translate", FailureCategory.CONFIGURATION));
        }
        if (language == null || language.length() > 64 || !LANGUAGE_PATTERN.matcher(language).matches()) {
            return CompletableFuture.failedFuture(new ProviderFailure("Invalid target language", FailureCategory.CONFIGURATION));
        }
        if (provider != Provider.GOOGLE_FREE && (key == null || key.isEmpty() || key.length() > 4096
                || !key.chars().allMatch(c -> c >= 33 && c <= 126))) {
            return CompletableFuture.failedFuture(new ProviderFailure("Missing or invalid API key for " + provider.displayName, FailureCategory.CONFIGURATION));
        }
        if (provider.retryAfterMillis.get() > System.currentTimeMillis()) {
            return CompletableFuture.failedFuture(new ProviderFailure(provider.displayName + " is rate limiting translations", FailureCategory.RATE_LIMIT));
        }

        try {
            HttpRequest request = buildProviderRequest(provider, text, language, key);
            return transport.apply(request).handle((response, error) -> {
                if (error != null) {
                    Throwable cause = unwrap(error);
                    if (cause instanceof ResponseStatus status) {
                        throw providerStatusFailure(provider, status.code, status.headers);
                    }
                    if (cause instanceof ProviderFailure failure) throw failure;
                    throw new ProviderFailure("Translation request failed", FailureCategory.RESPONSE);
                }
                if (response.statusCode() != 200) throw providerStatusFailure(provider, response.statusCode(), response.headers());
                return parseTranslation(provider, response.body());
            });
        } catch (ProviderFailure error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private static HttpRequest buildProviderRequest(Provider provider, String text, String language, String key) {
        HttpRequest.Builder request = HttpRequest.newBuilder().timeout(REQUEST_TIMEOUT);
        if (provider == Provider.GOOGLE_FREE) {
            String queryText = URLEncoder.encode(text, StandardCharsets.UTF_8);
            if (queryText.length() > 6000) throw new ProviderFailure("Text is too long for Google", FailureCategory.CONFIGURATION);
            String target = language.equals("zh") ? "zh-CN" : language;
            return request.uri(URI.create("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl="
                    + target + "&dt=t&q=" + queryText)).GET().build();
        }

        JsonObject body = new JsonObject();
        JsonArray texts = new JsonArray();
        texts.add(text);
        if (provider == Provider.DEEPL_FREE) {
            String target = switch (language) {
                case "en" -> "EN-US";
                case "pt" -> "PT-BR";
                default -> language.toUpperCase(Locale.ROOT);
            };
            body.addProperty("target_lang", target);
            body.add("text", texts);
            request.uri(URI.create("https://api-free.deepl.com/v2/translate")).header("Authorization", "DeepL-Auth-Key " + key);
        } else {
            if (language.length() > 3) throw new ProviderFailure("Yandex requires a two- or three-letter language code", FailureCategory.CONFIGURATION);
            body.addProperty("targetLanguageCode", language);
            body.add("texts", texts);
            request.uri(URI.create("https://translate.api.cloud.yandex.net/translate/v2/translate")).header("Authorization", "Api-Key " + key);
        }
        return request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
    }

    private static String parseTranslation(Provider provider, String body) {
        try {
            JsonElement parsed = PROVIDER_JSON.fromJson(body, JsonElement.class);
            if (parsed == null) throw new IllegalStateException("Expected a JSON response");
            String translated;
            if (provider == Provider.GOOGLE_FREE) {
                StringBuilder result = new StringBuilder();
                for (JsonElement part : parsed.getAsJsonArray().get(0).getAsJsonArray()) {
                    JsonArray fields = part.getAsJsonArray();
                    if (fields.get(0).isJsonNull()) continue;
                    result.append(jsonString(fields.get(0)));
                }
                translated = result.toString();
            } else {
                JsonElement translations = parsed.getAsJsonObject().get("translations");
                if (translations == null) throw new IllegalStateException("Missing translations");
                translated = jsonString(translations.getAsJsonArray().get(0).getAsJsonObject().get("text"));
            }
            if (translated.isBlank() || translated.length() > 2 * MAX_INPUT_LENGTH) {
                throw new IllegalStateException("Invalid translation length");
            }
            if (!translated.codePoints().allMatch(TranslationService::isValidTranslatedCharacter)) {
                throw new IllegalStateException("Invalid character in translation");
            }
            return translated;
        } catch (JsonParseException | IllegalStateException | IndexOutOfBoundsException error) {
            // The parser's exception can contain translated chat or provider response data.
            throw new ProviderFailure(provider.displayName + " returned an invalid translation", FailureCategory.RESPONSE);
        }
    }

    private static boolean isValidTranslatedCharacter(int c) {
        if (c == '\n' || c == '\r' || c == '\t') return true;
        return c != '§' && Character.getType(c) != Character.SURROGATE && !Character.isISOControl(c);
    }

    private static String jsonString(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalStateException("Expected a JSON string");
        }
        return element.getAsString();
    }

    private static ProviderFailure providerStatusFailure(Provider provider, int status, HttpHeaders headers) {
        if (status == 429) {
            long now = System.currentTimeMillis();
            long delay = retryDelayMillis(headers.firstValue("Retry-After").orElse(""), now);
            provider.retryAfterMillis.accumulateAndGet(now + delay, Math::max);
        }
        FailureCategory category = switch (status) {
            case 401, 403 -> FailureCategory.AUTHORIZATION;
            case 429 -> FailureCategory.RATE_LIMIT;
            case 456 -> FailureCategory.QUOTA;
            default -> status >= 500 && status <= 599 ? FailureCategory.SERVER : FailureCategory.RESPONSE;
        };
        return new ProviderFailure(provider.displayName + " returned HTTP " + status, category);
    }

    static long retryDelayMillis(String retryAfter, long now) {
        long maxDelay = Long.MAX_VALUE - now;
        try {
            long seconds = Long.parseLong(retryAfter);
            return Math.clamp(seconds, 1, maxDelay / 1000) * 1000;
        } catch (NumberFormatException error) {
            try {
                long retryAt = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                return Math.max(retryAt - now, 1000);
            } catch (DateTimeParseException | ArithmeticException invalidDate) {
                return Duration.ofSeconds(30).toMillis();
            }
        }
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error;
    }
    public static boolean containsNonEnglishLetters(String text) {
        if (text == null) return false;
        String clean = stripFormatting(text);
        int nonEng = 0, eng = 0;
        for (int i = 0; i < clean.length();) {
            int c = clean.codePointAt(i);
            i += Character.charCount(c);
            if (Character.isLetter(c)) {
                Character.UnicodeScript script = Character.UnicodeScript.of(c);
                if (script == Character.UnicodeScript.COMMON) continue; // skip symbols like ✦
                if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z')) nonEng++; else eng++; 
            }
        }
        return nonEng > eng && nonEng >= 1;
    }

    public static boolean containsLetters(String text) {
        if (text == null) return false;
        return stripFormatting(text).codePoints().anyMatch(Character::isLetter);
    }

    public static class PlayerTemplateResult { public final String templatedText; public final List<String> playerNames; public PlayerTemplateResult(String t, List<String> p) { this.templatedText = t; this.playerNames = p; } }
    private static final Pattern PLAYER_OR_PLACEHOLDER = Pattern.compile("\\{[FPC]?\\d+}|[A-Za-z0-9_]+");
    private static volatile Set<String> onlinePlayers = Set.of();

    public static void updateOnlinePlayerNames(net.minecraft.client.Minecraft client) {
        if (client == null) return;
        Set<String> names = new HashSet<>();
        try {
            if (client.player != null) names.add(client.player.getName().getString());
            if (client.getConnection() != null) for (net.minecraft.client.multiplayer.PlayerInfo i : client.getConnection().getOnlinePlayers()) {
                String n = i.getProfile().name();
                if (n != null && n.length() >= 3) names.add(n);
            }
        } catch (Exception e) { LOGGER.warn("Failed to update player names", e); }
        onlinePlayers = Set.copyOf(names);
    }

    public static PlayerTemplateResult templatePlayerNames(String text) {
        if (text == null || text.isEmpty()) return new PlayerTemplateResult("", new ArrayList<>());
        Set<String> names = onlinePlayers;
        if (names.isEmpty()) return new PlayerTemplateResult(text, List.of());
        List<String> matched = new ArrayList<>();
        Matcher matcher = PLAYER_OR_PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String word = matcher.group();
            if (!names.contains(word)) continue;
            matcher.appendReplacement(sb, "{P" + matched.size() + "}");
            matched.add(word);
        }
        matcher.appendTail(sb);
        return new PlayerTemplateResult(sb.toString(), matched);
    }

    public static String restorePlayerNames(String text, List<String> playerNames) {
        if (text == null) return null;
        String result = text;
        for (int i = 0; i < playerNames.size(); i++) result = result.replace("{P" + i + "}", playerNames.get(i));
        return result;
    }

    private static final Pattern COMMAND_PATTERN = Pattern.compile("(?<![:/])/[a-zA-Z][a-zA-Z0-9_-]*");
    public static class CommandTemplateResult { public final String templatedText; public final List<String> commands; public CommandTemplateResult(String t, List<String> c) { this.templatedText = t; this.commands = c; } }
    public static CommandTemplateResult templateCommands(String text) {
        List<String> commands = new ArrayList<>();
        if (text == null || text.isEmpty()) return new CommandTemplateResult("", commands);
        Matcher matcher = COMMAND_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (matcher.find()) {
            commands.add(matcher.group());
            matcher.appendReplacement(sb, "{C" + count + "}");
            count++;
        }
        matcher.appendTail(sb);
        return new CommandTemplateResult(sb.toString(), commands);
    }

    public static String restoreCommands(String text, List<String> commands) {
        if (text == null) return null;
        String result = text;
        for (int i = 0; i < commands.size(); i++) result = result.replace("{C" + i + "}", commands.get(i));
        return result;
    }

    public static boolean containsTranslatableLetters(String text) {
        if (text == null) return false;
        String clean = PROTECTED_TOKEN.matcher(text).replaceAll("");
        return containsLetters(clean);
    }

    public static void shutdown() {
        List<CompletableFuture<String>> pending;
        synchronized (ACTIVE_TRANSLATIONS) {
            shuttingDown = true;
            pending = List.copyOf(ACTIVE_TRANSLATIONS.values());
            ACTIVE_TRANSLATIONS.clear();
        }
        pending.forEach(future -> future.complete(""));
        HTTP_CLIENT.shutdownNow();
        HTTP_EXECUTOR.shutdown();
    }
}
