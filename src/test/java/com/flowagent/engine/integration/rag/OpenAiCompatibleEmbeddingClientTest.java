package com.flowagent.engine.integration.rag;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the real OpenAI-compatible embedding client against a localhost HTTP server (JDK
 * {@code com.sun.net.httpserver}, no extra test dependency). Exercises the JSON parsing path,
 * URL normalisation and the failure branches that the fake embedder used elsewhere never hits.
 */
public class OpenAiCompatibleEmbeddingClientTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void respond(String body, int status) {
        server.createContext("/embeddings", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        });
    }

    private OpenAiCompatibleEmbeddingClient clientWithUrl(String url) {
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient();
        ReflectionTestUtils.setField(client, "url", url);
        ReflectionTestUtils.setField(client, "apiKey", "test-key");
        ReflectionTestUtils.setField(client, "model", "text-embedding-3-small");
        return client;
    }

    @Test
    void parsesEmbeddingVectorFromResponse() {
        respond("{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}", 200);
        float[] v = clientWithUrl(baseUrl).embed("hi");
        assertEquals(3, v.length);
        assertEquals(0.1f, v[0], 1e-6);
        assertEquals(0.2f, v[1], 1e-6);
        assertEquals(0.3f, v[2], 1e-6);
    }

    @Test
    void handlesTrailingSlashInUrl() {
        respond("{\"data\":[{\"embedding\":[1.0]}]}", 200);
        float[] v = clientWithUrl(baseUrl + "/").embed("x");
        assertEquals(1, v.length);
        assertEquals(1.0f, v[0], 1e-6);
    }

    @Test
    void missingUrlThrowsIllegalState() {
        // url field is null when not Spring-injected (the @Value default is "")
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient();
        assertThrows(IllegalStateException.class, () -> client.embed("x"));
    }

    @Test
    void emptyDataArrayThrows() {
        respond("{\"data\":[]}", 200);
        assertThrows(RuntimeException.class, () -> clientWithUrl(baseUrl).embed("x"));
    }

    @Test
    void httpErrorThrows() {
        respond("{}", 500);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> clientWithUrl(baseUrl).embed("x"));
        assertTrue(ex.getMessage().contains("500"), "error should mention the HTTP status");
    }
}
