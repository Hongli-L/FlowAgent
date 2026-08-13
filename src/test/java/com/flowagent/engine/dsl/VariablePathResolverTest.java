package com.flowagent.engine.dsl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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

    @Test
    void shouldReturnRootWhenPathBlank() {
        Map<String, Object> root = Map.of("a", 1);
        assertSame(root, VariablePathResolver.resolve(root, ""));
        assertSame(root, VariablePathResolver.resolve(root, "   "));
    }

    @Test
    void shouldReturnNullForNonMapRoot() {
        assertNull(VariablePathResolver.resolve("just a string", "a.b"));
        assertNull(VariablePathResolver.resolve(42, "a"));
    }

    @Test
    void shouldReturnNullForMissingKey() {
        Map<String, Object> root = Map.of("a", 1);
        assertNull(VariablePathResolver.resolve(root, "missing"));
        assertNull(VariablePathResolver.resolve(root, "a.missing"));
    }

    @Test
    void shouldResolveArrayElementThenNestedField() {
        Map<String, Object> root = Map.of(
                "segments", List.of(
                        Map.of("text", "first", "meta", Map.of("len", 5)),
                        Map.of("text", "second", "meta", Map.of("len", 6))
                )
        );
        assertEquals("first", VariablePathResolver.resolve(root, "segments[0].text"));
        assertEquals(6, VariablePathResolver.resolve(root, "segments[1].meta.len"));
    }
}
