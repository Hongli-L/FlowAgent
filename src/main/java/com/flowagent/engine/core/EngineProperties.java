package com.flowagent.engine.core;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "workflow.engine")
public class EngineProperties {

    /**
     * Active engine adapter: LEGACY or LANGGRAPH.
     */
    private String type = EngineType.LEGACY.name();

    /**
     * Legacy engine mode: SEQUENTIAL or PARALLEL.
     */
    private String mode = ExecutionMode.SEQUENTIAL.name();

    private int nodeTimeout = 300;

    private int workflowTimeout = 600;

    public EngineType resolveType() {
        return EngineType.from(type);
    }

    public ExecutionMode resolveMode() {
        return ExecutionMode.from(mode);
    }
}
