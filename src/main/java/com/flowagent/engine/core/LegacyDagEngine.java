package com.flowagent.engine.core;

import com.flowagent.engine.ParallelWorkflowEngine;
import com.flowagent.engine.WorkflowContextStore;
import com.flowagent.engine.DagWorkflowEngine;
import com.flowagent.engine.dsl.model.WorkflowDSL;
import com.flowagent.engine.node.FlowEventCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Self-developed DAG engine adapter.
 * Internally supports sequential execution and BFS parallel scheduling.
 */
@Slf4j
@Component
public class LegacyDagEngine implements WorkflowExecutionEngine {

    private final DagWorkflowEngine sequentialEngine;
    private final ParallelWorkflowEngine parallelEngine;
    private final EngineProperties properties;

    public LegacyDagEngine(DagWorkflowEngine sequentialEngine,
                           ParallelWorkflowEngine parallelEngine,
                           EngineProperties properties) {
        this.sequentialEngine = sequentialEngine;
        this.parallelEngine = parallelEngine;
        this.properties = properties;
    }

    @Override
    public EngineType type() {
        return EngineType.LEGACY;
    }

    @Override
    public void execute(WorkflowDSL workflowDSL,
                        WorkflowContextStore variablePool,
                        Map<String, Object> inputs,
                        FlowEventCallback callback) throws Exception {
        execute(workflowDSL, variablePool, inputs, callback, properties.resolveMode());
    }

    public void execute(WorkflowDSL workflowDSL,
                        WorkflowContextStore variablePool,
                        Map<String, Object> inputs,
                        FlowEventCallback callback,
                        ExecutionMode mode) throws Exception {
        ExecutionMode resolved = mode != null ? mode : properties.resolveMode();
        log.info("LegacyDagEngine executing with mode={}", resolved);
        if (resolved == ExecutionMode.PARALLEL) {
            parallelEngine.execute(workflowDSL, variablePool, inputs, callback);
        } else {
            sequentialEngine.execute(workflowDSL, variablePool, inputs, callback);
        }
    }
}
