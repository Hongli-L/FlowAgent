package com.flowagent.engine.dsl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VariablePathResolverTest {

    @Test
    void shouldResolveNestedPath() {
        Map<String, Object> root = Map.of(
                "response", Map.of(
                        "content", "hello",
                        "metadata", Map.of("model", "deepseek")
                )
        );

        assertEquals("hello", VariablePathResolver.resolve(root, "response.content"));
        assertEquals("deepseek", VariablePathResolver.resolve(root, "response.metadata.model"));
    }

    @Test
    void shouldResolveArrayIndexPath() {
        Map<String, Object> root = Map.of(
                "segments", List.of(
                        Map.of("text", "first"),
                        Map.of("text", "second")
                )
        );

        assertEquals(Map.of("text", "second"), VariablePathResolver.resolve(root, "segments[1]"));
    }

    @Test
    void shouldRenderTemplateWithNestedReference() {
        Map<String, Object> inputs = Map.of(
                "payload", Map.of("message", "FlowAgent")
        );

        String rendered = VariableTemplateRender.render("Hello {{payload.message}}", inputs);

        assertEquals("Hello FlowAgent", rendered);
    }
}
