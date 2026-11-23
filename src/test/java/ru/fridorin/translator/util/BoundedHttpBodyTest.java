package ru.fridorin.translator.util;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class BoundedHttpBodyTest {
    private static final int LIMIT = 256;

    @Test
    public void decodesUtf8WithinLimit() throws Exception {
        String text = "Force · Сила";
        assertEquals(text, receive(text.getBytes(StandardCharsets.UTF_8), false));
    }

    @Test
    public void acceptsExactlyTheLimit() throws Exception {
        assertEquals("a".repeat(LIMIT), receive("a".repeat(LIMIT).getBytes(StandardCharsets.UTF_8), true));
    }

    @Test
    public void boundsResponsesWithOrWithoutLength() {
        assertThrows(IOException.class, () -> receive(new byte[LIMIT + 1], true));
        assertThrows(IOException.class, () -> receive(new byte[LIMIT + 1], false));
    }

    @Test
    public void rejectsInvalidUtf8() {
        assertThrows(IOException.class, () -> receive(new byte[] { (byte) 0xFF }, false));
    }

    private static String receive(byte[] body, boolean chunked) throws Exception {
        var address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        HttpServer server = HttpServer.create(address, 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, chunked ? 0 : body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var uri = new URI("http", null, address.getAddress().getHostAddress(),
                server.getAddress().getPort(), "/", null, null);
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
            return client.send(request, BoundedHttpBody.ofUtf8String(LIMIT)).body();
        } finally {
            server.stop(0);
        }
    }
}
