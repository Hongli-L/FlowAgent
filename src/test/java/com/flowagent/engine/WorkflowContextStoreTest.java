package com.flowagent.engine;

import lombok.Data;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WorkflowContextStore}: the per-execution variable pool that node outputs
 * are written to and that {@code {{node-id.field}}} template references resolve from. Covers
 * primitive round-tripping, nested object / array path resolution, missing-key handling, the
 * full-map accessor and {@code clear()}.
 */
public class WorkflowContextStoreTest {

    @Test
    void primitiveTypesRoundTrip() {
        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("node-start::001", "user_input", "Tell me about Java");
        pool.set("node-start::001", "request_id", 12345);
        pool.set("node-start::001", "is_urgent", true);

        assertEquals("Tell me about Java", pool.get("node-start::001", "user_input"));
        assertEquals(12345, pool.get("node-start::001", "request_id"));
        assertEquals(true, pool.get("node-start::001", "is_urgent"));
    }

    @Test
    void nestedObjectResolvesWithDotPath() {
        LLMResponse response = new LLMResponse();
        response.setContent("Java is an object-oriented programming language...");
        response.setWordCount(150);
        response.setKeywords(List.of("Java", "OOP", "Programming"));
        Metadata metadata = new Metadata();
        metadata.setModel("deepseek-chat");
        metadata.setTemperature(0.7D);
        metadata.setTimestamp(System.currentTimeMillis());
        response.setMetadata(metadata);

        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("node-llm::002", "response", response);

        assertEquals("Java is an object-oriented programming language...",
                pool.get("node-llm::002", "response.content"));
        assertEquals("deepseek-chat", pool.get("node-llm::002", "response.metadata.model"));
        assertNotNull(pool.get("node-llm::002", "response.metadata.temperature"));
    }

    @Test
    void listElementsResolveByIndex() {
        List<ChatSegment> segments = Arrays.asList(
                new ChatSegment("Welcome to Tech Podcast", "xiaoyan", 3000),
                new ChatSegment("Today we discuss Java", "narrator", 2500));
        Map<String, Object> llmResult = new HashMap<>();
        llmResult.put("segments", segments);
        llmResult.put("total_duration", 5500);

        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("node-llm::003", "result", llmResult);

        assertEquals("Welcome to Tech Podcast", pool.get("node-llm::003", "result.segments[0].text"));
        assertEquals("narrator", pool.get("node-llm::003", "result.segments[1].speaker"));
        assertEquals(3000, pool.get("node-llm::003", "result.segments[0].duration"));
    }

    @Test
    void missingKeyReturnsNull() {
        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("node-start::001", "user_input", "hi");
        assertNull(pool.get("node-start::001", "does_not_exist"));
        assertNull(pool.get("node-missing::999", "user_input"));
    }

    @Test
    void getByNodeIdReturnsFullMap() {
        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("node-start::001", "a", "1");
        pool.set("node-start::001", "b", "2");
        Map<String, Object> all = pool.get("node-start::001");
        assertEquals(2, all.size());
        assertTrue(all.containsKey("a"));
        assertTrue(all.containsKey("b"));
    }

    @Test
    void clearEmptiesAllVariables() {
        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("node-start::001", "user_input", "hi");
        pool.set("node-llm::002", "answer", "42");
        pool.clear();
        assertNull(pool.get("node-start::001", "user_input"));
        assertNull(pool.get("node-llm::002", "answer"));
        assertEquals(0, pool.get("node-start::001").size());
    }

    @Test
    void nestedMapValueResolvesThroughDotPath() {
        Map<String, Object> inner = new HashMap<>();
        inner.put("city", "Beijing");
        inner.put("zip", 100000);
        Map<String, Object> outer = new HashMap<>();
        outer.put("address", inner);

        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("node-tool::004", "payload", outer);
        assertEquals("Beijing", pool.get("node-tool::004", "payload.address.city"));
        assertEquals(100000, pool.get("node-tool::004", "payload.address.zip"));
    }

    @Data
    public static class LLMResponse {
        private String content;
        private Integer wordCount;
        private List<String> keywords;
        private Metadata metadata;
    }

    @Data
    public static class Metadata {
        private String model;
        private Double temperature;
        private Long timestamp;
    }

    public record ChatSegment(String text, String speaker, Integer duration) {
    }
}
