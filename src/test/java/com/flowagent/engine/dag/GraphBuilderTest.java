package com.flowagent.engine.dag;

import com.flowagent.engine.dsl.model.WorkflowDSL;
import com.flowagent.engine.dsl.model.Edge;
import com.flowagent.engine.dsl.model.Node;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphBuilderTest {

    private GraphBuilder graphBuilder;

    @BeforeEach
    void setUp() {
        graphBuilder = new GraphBuilder();
    }

    @Test
    void shouldWireLinearChain() {
        Node start = DagTestFixtures.node("node-start::001");
        Node llm = DagTestFixtures.node("llm::002");
        Node end = DagTestFixtures.node("node-end::003");
        WorkflowDSL dsl = DagTestFixtures.workflow(
                List.of(start, llm, end),
                List.of(
                        DagTestFixtures.edge("node-start::001", "llm::002"),
                        DagTestFixtures.edge("llm::002", "node-end::003")
                )
        );

        GraphBuildResult result = graphBuilder.build(dsl);

        assertSame(start, result.getStartNode());
        assertEquals(3, result.getNodeMap().size());
        assertEquals(List.of(start), llm.getPreNodes());
        assertEquals(List.of(llm), end.getPreNodes());
        assertEquals(List.of(llm), start.getNextNodes());
        assertEquals(List.of(end), llm.getNextNodes());
    }

    @Test
    void shouldWireConditionBranches() {
        Node start = DagTestFixtures.node("node-start::001");
        Node condition = DagTestFixtures.node("if-else::002");
        Node success = DagTestFixtures.node("llm::003");
        Node fail = DagTestFixtures.node("node-end::004");
        WorkflowDSL dsl = DagTestFixtures.workflow(
                List.of(start, condition, success, fail),
                List.of(
                        DagTestFixtures.edge("node-start::001", "if-else::002"),
                        DagTestFixtures.edge("if-else::002", "llm::003", "branch-1"),
                        DagTestFixtures.edge("if-else::002", "node-end::004", "fail_branch")
                )
        );

        GraphBuildResult result = graphBuilder.build(dsl);

        assertSame(start, result.getStartNode());
        assertEquals(List.of(success), condition.getNextNodes());
        assertEquals(List.of(fail), condition.getFailNodes());
        assertTrue(success.getPreNodes().contains(condition));
        assertTrue(fail.getPreNodes().contains(condition));
    }

    @Test
    void shouldSupportMultiplePredecessors() {
        Node start = DagTestFixtures.node("node-start::001");
        Node left = DagTestFixtures.node("llm::002");
        Node right = DagTestFixtures.node("llm::003");
        Node join = DagTestFixtures.node("node-end::004");
        WorkflowDSL dsl = DagTestFixtures.workflow(
                List.of(start, left, right, join),
                List.of(
                        DagTestFixtures.edge("node-start::001", "llm::002"),
                        DagTestFixtures.edge("node-start::001", "llm::003"),
                        DagTestFixtures.edge("llm::002", "node-end::004"),
                        DagTestFixtures.edge("llm::003", "node-end::004")
                )
        );

        graphBuilder.build(dsl);

        assertEquals(2, join.getPreNodes().size());
        assertTrue(join.getPreNodes().contains(left));
        assertTrue(join.getPreNodes().contains(right));
    }
}
