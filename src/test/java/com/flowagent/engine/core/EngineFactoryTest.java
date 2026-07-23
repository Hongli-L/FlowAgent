package com.flowagent.engine.core;

import com.flowagent.engine.WorkflowContextStore;
import com.flowagent.engine.dsl.model.WorkflowDSL;
import com.flowagent.engine.node.FlowEventCallback;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EngineFactoryTest {

    @Test
    void shouldSelectEngineByConfiguredType() {
        EngineProperties properties = new EngineProperties();
        properties.setType("LANGGRAPH");
        properties.setMode("PARALLEL");

        WorkflowExecutionEngine legacy = new StubEngine(EngineType.LEGACY);
        WorkflowExecutionEngine langGraph = new StubEngine(EngineType.LANGGRAPH);
        EngineFactory factory = new EngineFactory(List.of(legacy, langGraph), properties);

        assertSame(langGraph, factory.getEngine());
        assertEquals(EngineType.LANGGRAPH, factory.activeType());
        assertEquals(ExecutionMode.PARALLEL, factory.activeMode());
        assertSame(legacy, factory.getEngine(EngineType.LEGACY));
    }

    @Test
    void shouldFailWhenEngineMissing() {
        EngineProperties properties = new EngineProperties();
        properties.setType("LANGGRAPH");
        EngineFactory factory = new EngineFactory(List.of(new StubEngine(EngineType.LEGACY)), properties);

        assertThrows(IllegalStateException.class, factory::getEngine);
    }

    private static final class StubEngine implements WorkflowExecutionEngine {
        private final EngineType type;

        private StubEngine(EngineType type) {
            this.type = type;
        }

        @Override
        public EngineType type() {
            return type;
        }

        @Override
        public void execute(WorkflowDSL workflowDSL, WorkflowContextStore variablePool,
                            Map<String, Object> inputs, FlowEventCallback callback) {
        }
    }
}
