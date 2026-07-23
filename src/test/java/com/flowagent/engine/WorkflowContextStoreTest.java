package com.flowagent.engine;

import lombok.Data;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 */
public class WorkflowContextStoreTest {

    @org.junit.jupiter.api.Test
    void basicTest() {
        WorkflowContextStore variablePool = new WorkflowContextStore();
        // Store basic data types
        variablePool.set("node-start::001", "user_input", "Tell me about Java");
        variablePool.set("node-start::001", "request_id", 12345);
        variablePool.set("node-start::001", "is_urgent", true);

// Retrieve basic data types
        String userInput = (String) variablePool.get("node-start::001", "user_input");
        Integer requestId = (Integer) variablePool.get("node-start::001", "request_id");
        Boolean isUrgent = (Boolean) variablePool.get("node-start::001", "is_urgent");
        System.out.println("User input：" + userInput);
        System.out.println("Request ID：" + requestId);
        System.out.println("Is urgent：" + isUrgent);
    }

    @Data
    public static class LLMResponse {
        private String content;
        private Integer wordCount;
        private List<String> keywords;
        private Metadata metadata;
    }

    @Data
    public class Metadata {
        private String model;
        private Double temperature;
        private Long timestamp;
    }

    @org.junit.jupiter.api.Test
    public void objTest() {
        // 1. Store complex object (auto-converted to JSONObject)
        LLMResponse response = new LLMResponse();
        response.setContent("Java is an object-oriented programming language...");
        response.setWordCount(150);
        response.setKeywords(List.of("Java", "OOP", "Programming"));
        Metadata metadata = new Metadata();
        metadata.setModel("deepseek-chat");
        metadata.setTemperature(0.7D);
        metadata.setTimestamp(System.currentTimeMillis());
        response.setMetadata(metadata);

        WorkflowContextStore variablePool = new WorkflowContextStore();
        variablePool.set("node-llm::002", "response", response);

// 2. Direct object member access
        String content = (String) variablePool.get("node-llm::002", "response.content");
        Integer wordCount = (Integer) variablePool.get("node-llm::002", "response.wordCount");
        String model = (String) variablePool.get("node-llm::002", "response.metadata.model");
        var temperature = variablePool.get("node-llm::002", "response.metadata.temperature");
        System.out.println("Content：" + content);
        System.out.println("Word count：" + wordCount);
        System.out.println("Model：" + model);
        System.out.println("Temperature：" + temperature);
    }

    public record ChatSegment(String text, String speaker, Integer duration) {
    }

    @org.junit.jupiter.api.Test
    public void listTest() {
        // Store object containing arrays
        List<ChatSegment> segments = Arrays.asList(
                new ChatSegment("Welcome to Tech Podcast", "xiaoyan", 3000),
                new ChatSegment("Today we discuss Java", "narrator", 2500)
        );
        Map<String, Object> llmResult = new HashMap<>();
        llmResult.put("segments", segments);
        llmResult.put("total_duration", 5500);

        WorkflowContextStore variablePool = new WorkflowContextStore();
        variablePool.set("node-llm::003", "result", llmResult);

        // Access array element properties by index
        String firstSegmentText = (String) variablePool.get("node-llm::003", "result.segments[0].text");
        String secondSpeaker = (String) variablePool.get("node-llm::003", "result.segments[1].speaker");
        Integer firstDuration = (Integer) variablePool.get("node-llm::003", "result.segments[0].duration");
        System.out.println("First segment text：" + firstSegmentText);
        System.out.println("Second segment speaker：" + secondSpeaker);
        System.out.println("First segment duration：" + firstDuration);
    }
}