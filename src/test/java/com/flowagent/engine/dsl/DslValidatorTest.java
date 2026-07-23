package com.flowagent.engine.dsl;

import com.flowagent.common.exception.NodeCustomException;
import com.flowagent.engine.dag.DagTestFixtures;
import com.flowagent.engine.dsl.model.InputItem;
import com.flowagent.engine.dsl.model.InputSchema;
import com.flowagent.engine.dsl.model.Node;
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
}
