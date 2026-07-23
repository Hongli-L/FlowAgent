package com.flowagent.engine.core;

import com.flowagent.engine.WorkflowContextStore;
import com.flowagent.engine.dsl.model.WorkflowDSL;
import com.flowagent.engine.node.FlowEventCallback;

import java.util.Map;

/**
 * Unified workflow execution engine contract.
 * Implementations: {@link LegacyDagEngine}, {@link LangGraphEngine}.
 */
public interface WorkflowExecutionEngine {

    EngineType type();

    void execute(WorkflowDSL workflowDSL,
                 WorkflowContextStore variablePool,
                 Map<String, Object> inputs,
                 FlowEventCallback callback) throws Exception;
}
