package com.flowagent.engine.tracing;

import com.alibaba.fastjson2.JSON;
import com.flowagent.common.enums.NodeExecStatusEnum;
import com.flowagent.engine.context.EngineContextHolder;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.persistence.entity.NodeRunLogEntity;
import com.flowagent.persistence.service.ExecutionHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Hooks into {@code AbstractNodeHandler.execute()} to record per-node execution detail
 * (input/output, duration, retry count, status) into the node run log.
 *
 * <p>The owning execution id and workflow id are resolved from {@link EngineContextHolder},
 * which is propagated across threads via TransmittableThreadLocal so parallel-node tracing
 * stays correlated to the right execution.</p>
 */
@Slf4j
@Component
public class ExecutionRecorder {

    private final ExecutionHistoryService executionHistoryService;

    public ExecutionRecorder(ExecutionHistoryService executionHistoryService) {
        this.executionHistoryService = executionHistoryService;
    }

    /**
     * Record one node execution.
     *
     * @param nodeState   node execution state
     * @param result      node run result
     * @param durationMs  wall-clock duration of this node activation in milliseconds
     */
    public void record(NodeState nodeState, NodeRunResult result, long durationMs) {
        EngineContextHolder.EngineContext ctx = EngineContextHolder.get();
        if (ctx == null || ctx.getExecutionId() == null) {
            // Tracing only applies inside a workflow execution context.
            return;
        }
        try {
            Node node = nodeState.node();
            NodeExecStatusEnum status = result.getStatus();
            NodeRunLogEntity log = new NodeRunLogEntity();
            log.setExecutionId(ctx.getExecutionId());
            log.setNodeId(node.getId());
            log.setNodeType(node.getNodeType() == null ? "UNKNOWN" : node.getNodeType().name());
            log.setStatus(status == null || status.isSuccess() ? "SUCCESS" : "FAILED");

            LocalDateTime end = LocalDateTime.now();
            log.setEndTime(end);
            log.setStartTime(end.minus(durationMs, ChronoUnit.MILLIS));
            log.setDurationMs(durationMs);
            log.setRetryCount(node.getExecutedCount() == null ? 0 : node.getExecutedCount().get());
            log.setInputData(toJson(result.getInputs()));
            log.setOutputData(toJson(result.getOutputs()));
            log.setErrorMessage(result.getError() == null ? null : result.getError().getMessage());

            executionHistoryService.recordNodeLog(log);
        } catch (Exception e) {
            log.warn("Failed to record node run log for node {}", nodeState.node().getId(), e);
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.toJSONString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
