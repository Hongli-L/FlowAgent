package com.flowagent.engine.dag;

import com.flowagent.common.exception.ErrorCode;
import com.flowagent.common.exception.NodeCustomException;
import com.flowagent.engine.dsl.model.WorkflowDSL;
import com.flowagent.engine.dsl.model.Node;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopologyValidatorTest {

    private TopologyValidator validator;

    @BeforeEach
    void setUp() {
        validator = new TopologyValidator();
    }

    @Test
    void shouldAcceptLinearWorkflow() {
        WorkflowDSL dsl = DagTestFixtures.workflow(
                List.of(
                        DagTestFixtures.node("node-start::001"),
                        DagTestFixtures.node("llm::002"),
                        DagTestFixtures.node("node-end::003")
                ),
                List.of(
                        DagTestFixtures.edge("node-start::001", "llm::002"),
                        DagTestFixtures.edge("llm::002", "node-end::003")
                )
        );

        assertDoesNotThrow(() -> validator.validate(dsl));
        assertFalse(validator.hasCycle(dsl));
    }

    @Test
    void shouldRejectCycle() {
        WorkflowDSL dsl = DagTestFixtures.workflow(
                List.of(
                        DagTestFixtures.node("node-start::001"),
                        DagTestFixtures.node("llm::002"),
                        DagTestFixtures.node("node-end::003")
                ),
                List.of(
                        DagTestFixtures.edge("node-start::001", "llm::002"),
                        DagTestFixtures.edge("llm::002", "node-end::003"),
                        DagTestFixtures.edge("node-end::003", "llm::002")
                )
        );

        NodeCustomException ex = assertThrows(NodeCustomException.class, () -> validator.validate(dsl));
        assertEquals(ErrorCode.PROTOCOL_VALIDATION_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("cycle"));
        assertTrue(validator.hasCycle(dsl));
    }

    @Test
    void shouldRejectMissingStartNode() {
        WorkflowDSL dsl = DagTestFixtures.workflow(
                List.of(
                        DagTestFixtures.node("llm::002"),
                        DagTestFixtures.node("node-end::003")
                ),
                List.of(DagTestFixtures.edge("llm::002", "node-end::003"))
        );

        NodeCustomException ex = assertThrows(NodeCustomException.class, () -> validator.validate(dsl));
        assertTrue(ex.getMessage().contains("start or end"));
    }

    @Test
    void shouldRejectDanglingEdge() {
        WorkflowDSL dsl = DagTestFixtures.workflow(
                List.of(
                        DagTestFixtures.node("node-start::001"),
                        DagTestFixtures.node("node-end::003")
                ),
                List.of(DagTestFixtures.edge("node-start::001", "llm::002"))
        );

        NodeCustomException ex = assertThrows(NodeCustomException.class, () -> validator.validate(dsl));
        assertTrue(ex.getMessage().contains("unknown target node"));
    }

    @Test
    void shouldRejectEmptyWorkflow() {
        WorkflowDSL dsl = new WorkflowDSL();
        dsl.setNodes(List.of());
        dsl.setEdges(List.of());

        NodeCustomException ex = assertThrows(NodeCustomException.class, () -> validator.validate(dsl));
        assertTrue(ex.getMessage().contains("missing nodes or edges"));
    }
}
