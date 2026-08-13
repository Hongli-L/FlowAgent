package com.flowagent.engine.dsl;

import com.alibaba.fastjson2.JSON;
import com.flowagent.common.exception.NodeCustomException;
import com.flowagent.engine.dsl.model.WorkflowDSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DslParserTest {

    private DslParser dslParser;

    @BeforeEach
    void setUp() {
        dslParser = new DslParser();
    }

    @Test
    void shouldParseWorkflowJson() {
        String json = """
                {
                  "nodes": [
                    {"id": "node-start::001", "data": {"inputs": [], "outputs": [], "nodeParam": {}}},
                    {"id": "node-end::002", "data": {"inputs": [], "outputs": [], "nodeParam": {}}}
                  ],
                  "edges": [
                    {"sourceNodeId": "node-start::001", "targetNodeId": "node-end::002"}
                  ]
                }
                """;

        WorkflowDSL dsl = dslParser.parse(json);

        assertEquals(2, dsl.getNodes().size());
        assertEquals(1, dsl.getEdges().size());
    }

    @Test
    void shouldParseEnvelopeMap() {
        Map<String, Object> envelope = Map.of(
                "data", """
                        {
                          "nodes": [
                            {"id": "node-start::001", "data": {"inputs": [], "outputs": [], "nodeParam": {}}},
                            {"id": "node-end::002", "data": {"inputs": [], "outputs": [], "nodeParam": {}}}
                          ],
                          "edges": [
                            {"sourceNodeId": "node-start::001", "targetNodeId": "node-end::002"}
                          ]
                        }
                        """
        );

        WorkflowDSL dsl = dslParser.parseFromEnvelope(envelope);

        assertNotNull(dsl.getNodes());
        assertEquals(2, dsl.getNodes().size());
    }

    @Test
    void shouldRejectMissingEnvelopeData() {
        assertThrows(NodeCustomException.class, () -> dslParser.parseFromEnvelope(Map.of("name", "demo")));
    }

    @Test
    void emptyJsonThrows() {
        assertThrows(NodeCustomException.class, () -> dslParser.parse(""));
    }

    @Test
    void nullJsonThrows() {
        assertThrows(NodeCustomException.class, () -> dslParser.parse(null));
    }

    @Test
    void invalidJsonThrows() {
        assertThrows(NodeCustomException.class, () -> dslParser.parse("{ this is not json"));
    }

    @Test
    void shouldParseMultipleEdges() {
        String json = """
                {
                  "nodes": [
                    {"id": "node-start::001", "data": {"inputs": [], "outputs": [], "nodeParam": {}}},
                    {"id": "llm::002", "data": {"inputs": [], "outputs": [], "nodeParam": {}}},
                    {"id": "node-end::003", "data": {"inputs": [], "outputs": [], "nodeParam": {}}}
                  ],
                  "edges": [
                    {"sourceNodeId": "node-start::001", "targetNodeId": "llm::002"},
                    {"sourceNodeId": "llm::002", "targetNodeId": "node-end::003"}
                  ]
                }
                """;
        WorkflowDSL dsl = dslParser.parse(json);
        assertEquals(3, dsl.getNodes().size());
        assertEquals(2, dsl.getEdges().size());
    }

    @Test
    void shouldParseFromStoredDataEnvelope() {
        String inner = """
                {"nodes": [
                   {"id": "node-start::001", "data": {"inputs": [], "outputs": [], "nodeParam": {}}},
                   {"id": "node-end::002", "data": {"inputs": [], "outputs": [], "nodeParam": {}}}
                 ],
                 "edges": [
                   {"sourceNodeId": "node-start::001", "targetNodeId": "node-end::002"}
                 ]}""";
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("data", inner);
        String stored = JSON.toJSONString(env);

        WorkflowDSL dsl = dslParser.parseFromStoredData(stored);
        assertEquals(2, dsl.getNodes().size());
        assertEquals(1, dsl.getEdges().size());
    }

    @Test
    void shouldRejectStoredDataMissingField() {
        String stored = "{\"name\": \"demo\"}";
        assertThrows(NodeCustomException.class, () -> dslParser.parseFromStoredData(stored));
    }
}
