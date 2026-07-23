package com.flowagent.engine.dsl;

import com.flowagent.common.exception.NodeCustomException;
import com.flowagent.engine.dsl.model.WorkflowDSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
