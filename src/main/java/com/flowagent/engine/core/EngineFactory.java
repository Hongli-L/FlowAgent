package com.flowagent.engine.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class EngineFactory {

    private final Map<EngineType, WorkflowExecutionEngine> engines;
    private final EngineProperties properties;

    public EngineFactory(List<WorkflowExecutionEngine> engineList, EngineProperties properties) {
        this.properties = properties;
        this.engines = new EnumMap<>(EngineType.class);
        for (WorkflowExecutionEngine engine : engineList) {
            this.engines.put(engine.type(), engine);
            log.info("Registered workflow engine adapter: {}", engine.type());
        }
    }

    public WorkflowExecutionEngine getEngine() {
        return getEngine(properties.resolveType());
    }

    public WorkflowExecutionEngine getEngine(EngineType type) {
        WorkflowExecutionEngine engine = engines.get(type);
        if (engine == null) {
            throw new IllegalStateException("No workflow engine registered for type: " + type);
        }
        return engine;
    }

    public EngineType activeType() {
        return properties.resolveType();
    }

    public ExecutionMode activeMode() {
        return properties.resolveMode();
    }
}
