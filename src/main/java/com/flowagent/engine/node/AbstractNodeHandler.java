package com.flowagent.engine.node;

import cn.hutool.core.util.BooleanUtil;
import com.alibaba.fastjson2.JSON;
import com.flowagent.engine.WorkflowContextStore;
import com.flowagent.common.enums.ErrorStrategyEnum;
import org.springframework.beans.factory.annotation.Autowired;
import com.flowagent.common.enums.NodeExecStatusEnum;
import com.flowagent.engine.constants.NodeTypeEnum;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.engine.tracing.ExecutionRecorder;
import com.flowagent.engine.dsl.model.InputItem;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.engine.dsl.model.RetryConfig;
import com.flowagent.engine.dsl.model.Value;
import com.flowagent.engine.node.callback.WorkflowMsgCallback;
import com.flowagent.engine.util.AsyncUtil;
import com.flowagent.engine.util.FlowUtil;
import com.flowagent.common.exception.ErrorCode;
import com.flowagent.common.exception.NodeCustomException;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Abstract base class for node executors
 * Provides common functionality for all node types
 */
@Slf4j
public abstract class AbstractNodeHandler implements WorkflowNodeHandler {

    /** Node-level tracer; null-safe so engines/handlers created outside Spring still run. */
    @Autowired
    private ExecutionRecorder executionRecorder;

    @Override
    public NodeRunResult execute(NodeState nodeState) {
        long startTs = System.currentTimeMillis();
        NodeRunResult result = null;
        try {
            result = runWithRetry(nodeState);
        } finally {
            long durationMs = System.currentTimeMillis() - startTs;
            if (result != null && executionRecorder != null) {
                try {
                    executionRecorder.record(nodeState, result, durationMs);
                } catch (Exception e) {
                    log.warn("Node tracing failed for {}", nodeState.node().getId(), e);
                }
            }
        }
        return result;
    }

    private NodeRunResult runWithRetry(NodeState nodeState) {
        Node node = nodeState.node();

        // Execution count
        int executeTime = node.getExecutedCount().addAndGet(1);
        RetryConfig retryConfig = node.getData().getRetryConfig();
        if (retryConfig == null) {
            // No retry config: execute directly without timeout control
            return this.doExecute(nodeState);
        }

        // With config: apply timeout control
        if (!BooleanUtil.isTrue(retryConfig.getShouldRetry())) {
            // No retry, but with timeout control
            return this.doExecuteWithTimeout(nodeState, retryConfig);
        }

        // Retry with timeout control
        while (true) {
            NodeRunResult res = this.doExecuteWithTimeout(nodeState, retryConfig);
            NodeExecStatusEnum executeRes = res.getStatus();
            if (executeRes.isSuccess()) {
                return res;
            }

            if (executeTime > retryConfig.getMaxRetries()) {
                // Exceeded max retries
                return res;
            }
            // Backoff wait
            this.handleRetryWait(retryConfig, executeTime);
            executeTime = node.getExecutedCount().addAndGet(1);
        }
    }

    private void handleRetryWait(RetryConfig retryConfig, int retryCount) {
        if (retryConfig.getRetryInterval() == null || retryConfig.getRetryInterval() <= 0) {
            return;
        }

        long intervalMillis = (long) (retryConfig.getRetryInterval() * 1000);
        long waitTime;

        Integer strategy = retryConfig.getRetryStrategy();
        if (strategy == null) {
            strategy = 0; // Default to fixed
        }

        switch (strategy) {
            case 0: // Fixed interval
                waitTime = intervalMillis;
                break;
            case 1: // Linear backoff
                waitTime = intervalMillis * retryCount;
                break;
            case 2: // Exponential backoff
                waitTime = (long) (intervalMillis * Math.pow(2, retryCount - 1));
                break;
            default:
                waitTime = intervalMillis;
        }

        if (waitTime > 0) {
            try {
                Thread.sleep(waitTime);
            } catch (InterruptedException e) {
                log.warn("Retry wait interrupted", e);
                Thread.currentThread().interrupt();
            }
        }
    }

    protected NodeRunResult doExecuteWithTimeout(NodeState nodeState, RetryConfig retryConfig) {
        if (retryConfig.timeOutEnabled()) {
            // Timeout enabled scenario: wrap execution with time limit
            try {
                return AsyncUtil.callWithTimeLimit(retryConfig.toMillis(), TimeUnit.MILLISECONDS,
                        () -> this.doExecute(nodeState));
            } catch (TimeoutException e) {
                // Timeout fallback: node exceeded time limit, degrade to error strategy
                log.warn("Node {} timed out after {}ms, degrading to error strategy",
                        nodeState.node().getId(), retryConfig.toMillis());
                NodeRunResult result = new NodeRunResult();
                result.setError(new NodeCustomException(ErrorCode.TIMEOUT_ERROR));
                return errorResponse(nodeState, result);
            } catch (InterruptedException e) {
                // Thread interrupted: treat as timeout fallback
                log.warn("Node {} execution interrupted, degrading to error strategy",
                        nodeState.node().getId());
                NodeRunResult result = new NodeRunResult();
                result.setError(new NodeCustomException(ErrorCode.TIMEOUT_ERROR));
                return errorResponse(nodeState, result);
            } catch (Exception e) {
                // Unexpected node execution exception
                NodeRunResult result = new NodeRunResult();
                result.setError(new NodeCustomException(ErrorCode.NODE_RUN_ERROR, e.getMessage()));
                return errorResponse(nodeState, result);
            }
        } else {
            return this.doExecute(nodeState);
        }
    }

    protected NodeRunResult doExecute(NodeState nodeStage) {
        Node node = nodeStage.node();
        WorkflowMsgCallback callback = nodeStage.callback();
        WorkflowContextStore variablePool = nodeStage.variablePool();
        String nodeId = node.getId();
        NodeTypeEnum nodeType = node.getNodeType();

        log.info("Executing node: {} (type: {})", nodeId, nodeType);

        // Node execution start
        callback.onNodeStart(0, node.getId(), node.getData().getNodeMeta().getAliasName());

        // Resolve inputs
        Map<String, Object> resolvedInputs = node.getNodeType() == NodeTypeEnum.START ? variablePool.get(node.getId()) : resolveInputs(node, variablePool);
        try {
            // Execute node
            if (log.isDebugEnabled()) {
                log.debug("Executing start nodeId: {}, req: {}", node.getId(), JSON.toJSONString(resolvedInputs));
            }
            NodeRunResult executeRes = executeNode(nodeStage, resolvedInputs);

            // Capture resolved inputs for execution tracing
            executeRes.setInputs(resolvedInputs);

            // Store outputs to variable pool
            storeOutputs(node, executeRes.getOutputs(), variablePool);

            // Node execution complete: report result
            if (executeRes.getStatus() == null || executeRes.getStatus().isSuccess()) {
                successResponse(nodeStage, executeRes);
            } else {
                errorResponse(nodeStage, executeRes);
            }
            return executeRes;
        } catch (NodeCustomException e) {
            log.error("NodeCustomException executing node {}: {}", nodeId, e.getMessage(), e);
            NodeRunResult result = new NodeRunResult();
            result.setInputs(resolvedInputs);
            result.setError(e);
            return errorResponse(nodeStage, result);
        } catch (Exception e) {
            log.error("Exception executing node {}: {}", nodeId, e.getMessage(), e);
            NodeRunResult result = new NodeRunResult();
            result.setInputs(resolvedInputs);
            result.setError(new NodeCustomException(ErrorCode.NODE_RUN_ERROR, e.getMessage()));
            return errorResponse(nodeStage, result);
        }
    }


    /**
     * Execute the node-specific logic
     * Subclasses must implement this method
     *
     * @param nodeState workflow nodeState
     * @param inputs    input values
     * @throws Exception if execution fails
     */
    protected abstract NodeRunResult executeNode(NodeState nodeState, Map<String, Object> inputs) throws Exception;

    /**
     * Resolve all inputs for this node
     * Handles both literal values and variable references
     *
     * @param node         workflow node
     * @param variablePool variable pool
     * @return map of resolved input values
     */
    protected Map<String, Object> resolveInputs(Node node, WorkflowContextStore variablePool) {
        Map<String, Object> resolvedInputs = new HashMap<>();

        if (node.getData().getInputs() == null || node.getData().getInputs().isEmpty()) {
            log.debug("No inputs defined for node {}", node.getId());
            return resolvedInputs;
        }

        for (InputItem input : node.getData().getInputs()) {
            String inputName = input.getName();

            if (input.getSchema() == null || input.getSchema().getValue() == null) {
                log.warn("Input '{}' has no schema or value", inputName);
                continue;
            }

            Value value = input.getSchema().getValue();

            if (value.isReference()) {
                // Reference another node output as this node input
                if (value.getContent() instanceof java.util.Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> refMap = (Map<String, String>) value.getContent();
                    String refNodeId = refMap.get("nodeId");
                    String refName = refMap.get("name");

                    if (refNodeId != null && refName != null) {
                        // refName supports dot-path: xxx.yyy where xxx is node output key, yyy is nested attribute
                        Object refValue = variablePool.get(refNodeId, refName);
                        resolvedInputs.put(inputName, refValue);
                        if (log.isDebugEnabled()) {
                            log.debug("Resolved input '{}' from reference {}.{} = {}", inputName, refNodeId, refName, refValue);
                        }
                    }
                } else {
                    log.warn("Reference content is not a Map for input '{}'", inputName);
                }
            } else {
                // Use value directly as input parameter
                resolvedInputs.put(inputName, value.getContent());
                if (log.isDebugEnabled()) {
                    log.debug("Resolved input '{}' from literal = {}", inputName, value.getContent());
                }
            }
        }

        return resolvedInputs;
    }

    /**
     * Store node outputs to context store for downstream node inputs
     *
     * @param node         workflow node
     * @param outputs      output values produced by the node
     * @param variablePool variable pool
     */
    protected void storeOutputs(Node node, Map<String, Object> outputs, WorkflowContextStore variablePool) {
        String nodeId = node.getId();

        for (Map.Entry<String, Object> entry : outputs.entrySet()) {
            String outputName = entry.getKey();
            Object outputValue = entry.getValue();

            if (outputValue == null) {
                continue;
            }

            variablePool.set(nodeId, outputName, outputValue);

            if (log.isDebugEnabled()) {
                log.debug("Stored output: {}.{} = {}", nodeId, outputName, outputValue);
            }
        }
    }


    /**
     * Build success response for node execution
     *
     * @param nodeState node execution state
     * @param result    node run result
     */
    protected void successResponse(NodeState nodeState, NodeRunResult result) {
        Node node = nodeState.node();
        WorkflowMsgCallback callback = nodeState.callback();
        switch (nodeState.node().getNodeType()) {
            case START ->
                    callback.onStartNodeExecuted(node.getId(), node.getData().getNodeMeta().getAliasName(), result);
            case END -> callback.onEndNodeExecuted(node.getId(), node.getData().getNodeMeta().getAliasName(), result);
            default -> callback.onNodeEnd(node.getId(), node.getData().getNodeMeta().getAliasName(), result);
        }
    }


    /**
     * Build error response based on configured error strategy.
     * <p>
     * Three strategies are supported:
     * - ERR_CODE: emit custom output and continue on normal branch (error as data)
     * - ERR_CONDITION: route execution to fail-branch nodes (error as branch)
     * - ERR_INTERRUPT: halt workflow execution immediately (error as interrupt)
     * <p>
     * When no retry config is present, defaults to ERR_INTERRUPT.
     *
     * @param nodeState node execution state
     * @param result    node run result containing error information
     * @return node run result with status set according to error strategy
     */
    private NodeRunResult errorResponse(NodeState nodeState, NodeRunResult result) {
        Node node = nodeState.node();
        RetryConfig retryConfig = node.getData().getRetryConfig();
        WorkflowContextStore variablePool = nodeState.variablePool();
        NodeCustomException e = result.getError();
        if (e == null) e = new NodeCustomException(ErrorCode.NODE_RUN_ERROR);
        WorkflowMsgCallback callback = nodeState.callback();
        log.warn("Node execution exception, entering error branch flow: {}", node.getId(), e);
        if (retryConfig == null) {
            // No retry config: direct interrupt
            result.setError(e);
            result.setStatus(NodeExecStatusEnum.ERR_INTERUPT);
            callback.onNodeInterrupt(FlowUtil.genInterruptEventId(), Map.of(), node.getId(), node.getData().getNodeMeta().getAliasName(), e.getCode(), "interrupt", false);
            return result;
        }

        ErrorStrategyEnum errorStrategy = ErrorStrategyEnum.fromCode(retryConfig.getErrorStrategy());
        Map<String, Object> customOutput = retryConfig.getCustomOutput();
        if (errorStrategy == ErrorStrategyEnum.ERR_CODE) {
            // Error code strategy: emit output with custom values
            storeOutputs(node, customOutput, variablePool);
            result.setOutputs(customOutput);
            result.setError(e);
            result.setErrorOutputs(customOutput);
            result.setStatus(NodeExecStatusEnum.ERR_CODE_MSG);
            callback.onNodeEnd(node.getId(), node.getData().getNodeMeta().getAliasName(), result);
            return result;
        } else if (errorStrategy == ErrorStrategyEnum.ERR_CONDITION) {
            // Error branch strategy: route to fail node path
            result.setError(e);
            result.setErrorOutputs(customOutput);
            result.setStatus(NodeExecStatusEnum.ERR_FAIL_CONDITION);
            callback.onNodeEnd(node.getId(), node.getData().getNodeMeta().getAliasName(), result);
            return result;
        } else {
            // Interrupt strategy: halt workflow execution
            result.setError(e);
            result.setErrorOutputs(customOutput);
            result.setStatus(NodeExecStatusEnum.ERR_INTERUPT);
            callback.onNodeInterrupt(FlowUtil.genInterruptEventId(), customOutput, node.getId(), node.getData().getNodeMeta().getAliasName(), e.getCode(), "interrupt", false);
            return result;
        }
    }
}
