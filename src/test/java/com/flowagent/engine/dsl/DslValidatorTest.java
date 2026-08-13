package com.flowagent.engine.dsl;

import com.flowagent.common.exception.NodeCustomException;
import com.flowagent.engine.dag.DagTestFixtures;
import com.flowagent.engine.dsl.model.InputItem;
import com.flowagent.engine.dsl.model.InputSchema;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.engine.dsl.model.NodeData;
import com.flowagent.engine.dsl.model.NodeRef;
import com.flowagent.engine.dsl.model.Value;
import com.flowagent.engine.dsl.model.WorkflowDSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DslValidatorTest {

    private DslValidator dslValidator;

    @BeforeEach
    void setUp() {
        dslValidator = new DslValidator();
    }

    @Test
    void shouldAcceptSupportedWorkflow() {
        WorkflowDSL dsl = DagTestFixtures.workflow(
                List.of(
                        DagTestFixtures.node("node-start::001"),
                        DagTestFixtures.node("llm::002"),
                        DagTestFixtures.node("node-end::003")
                ),
                List.of()
        );

        assertDoesNotThrow(() -> dslValidator.validate(dsl));
    }

    @Test
    void shouldRejectUnsupportedNodeType() {
        WorkflowDSL dsl = DagTestFixtures.workflow(DagTestFixtures.node("knowledge-base::001"));

        NodeCustomException ex = assertThrows(NodeCustomException.class, () -> dslValidator.validate(dsl));
        assertTrue(ex.getMessage().contains("Unknown node type"));
    }

    @Test
    void shouldRejectBrokenInputReference() {
        Node llm = DagTestFixtures.node("llm::002");
        InputItem input = new InputItem();
        input.setName("prompt");
        InputSchema schema = new InputSchema();
        Value value = new Value();
        value.setType("ref");
        value.setContent(new NodeRef("missing-node::001", "output"));
        schema.setValue(value);
        input.setSchema(schema);
        llm.getData().setInputs(List.of(input));

        WorkflowDSL dsl = DagTestFixtures.workflow(
                List.of(DagTestFixtures.node("node-start::001"), llm),
                List.of()
        );

        NodeCustomException ex = assertThrows(NodeCustomException.class, () -> dslValidator.validate(dsl));
        assertTrue(ex.getMessage().contains("unknown node"));
    }

    @Test
    void shouldRejectNullDsl() {
        NodeCustomException ex = assertThrows(NodeCustomException.class, () -> dslValidator.validate(null));
        assertTrue(ex.getMessage().contains("null"));
    }

    @Test
    void shouldRejectEmptyNodeList() {
        WorkflowDSL dsl = DagTestFixtures.workflow(List.of(), List.of());
        NodeCustomException ex = assertThrows(NodeCustomException.class, () -> dslValidator.validate(dsl));
        assertTrue(ex.getMessage().contains("no nodes"));
    }

    @Test
    void shouldRejectDuplicateNodeId() {
        WorkflowDSL dsl = DagTestFixtures.workflow(List.of(
                DagTestFixtures.node("llm::002"),
                DagTestFixtures.node("llm::002")
        ), List.of());
        NodeCustomException ex = assertThrows(NodeCustomException.class, () -> dslValidator.validate(dsl));
        assertTrue(ex.getMessage().contains("Duplicate node id"));
    }

    @Test
    void shouldRejectInvalidNodeIdFormat() {
        Node bad = DagTestFixtures.node("badid-without-type");
        WorkflowDSL dsl = DagTestFixtures.workflow(List.of(bad), List.of());
        NodeCustomException ex = assertThrows(NodeCustomException.class, () -> dslValidator.validate(dsl));
        assertTrue(ex.getMessage().contains("Invalid node id format"));
    }

    @Test
    void shouldRejectMissingNodeData() {
        Node node = DagTestFixtures.node("llm::002");
        node.setData(null);
        WorkflowDSL dsl = DagTestFixtures.workflow(List.of(node), List.of());
        NodeCustomException ex = assertThrows(NodeCustomException.class, () -> dslValidator.validate(dsl));
        assertTrue(ex.getMessage().contains("Node data is missing"));
    }

    @Test
    void shouldAcceptAgentAndKnowledgeNodeTypes() {
        WorkflowDSL dsl = DagTestFixtures.workflow(List.of(
                DagTestFixtures.node("node-start::001"),
                DagTestFixtures.node("agent::002"),
                DagTestFixtures.node("knowledge::003"),
                DagTestFixtures.node("node-end::004")
        ), List.of());
        assertDoesNotThrow(() -> dslValidator.validate(dsl));
    }
}
