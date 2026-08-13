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

    @Test
    void shouldSelectLegacyWhenConfigured() {
        EngineProperties properties = new EngineProperties();
        properties.setType("LEGACY");
        WorkflowExecutionEngine legacy = new StubEngine(EngineType.LEGACY);
        EngineFactory factory = new EngineFactory(List.of(legacy, new StubEngine(EngineType.LANGGRAPH)), properties);

        assertSame(legacy, factory.getEngine());
        assertEquals(EngineType.LEGACY, factory.activeType());
    }

    @Test
    void shouldDefaultToLegacyWhenTypeUnset() {
        EngineProperties properties = new EngineProperties();
        properties.setType(null);
        WorkflowExecutionEngine legacy = new StubEngine(EngineType.LEGACY);
        EngineFactory factory = new EngineFactory(List.of(legacy, new StubEngine(EngineType.LANGGRAPH)), properties);

        assertEquals(EngineType.LEGACY, factory.activeType());
        assertSame(legacy, factory.getEngine());
    }

    @Test
    void shouldReportConfiguredParallelMode() {
        EngineProperties properties = new EngineProperties();
        properties.setType("LEGACY");
        properties.setMode("PARALLEL");
        EngineFactory factory = new EngineFactory(List.of(new StubEngine(EngineType.LEGACY)), properties);

        assertEquals(EngineType.LEGACY, factory.activeType());
        assertEquals(ExecutionMode.PARALLEL, factory.activeMode());
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
