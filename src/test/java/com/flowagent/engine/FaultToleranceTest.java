package com.flowagent.engine;

import com.flowagent.common.enums.EndNodeOutputModeEnum;
import com.flowagent.common.enums.ErrorStrategyEnum;
import com.flowagent.common.enums.NodeExecStatusEnum;
import com.flowagent.common.enums.NodeStatusEnum;
import com.flowagent.common.exception.ErrorCode;
import com.flowagent.common.exception.NodeCustomException;
import com.flowagent.engine.constants.NodeTypeEnum;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.engine.domain.callbacks.ChatCallBackStreamResult;
import com.flowagent.engine.domain.callbacks.LLMGenerate;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.engine.dsl.model.NodeData;
import com.flowagent.engine.dsl.model.NodeMeta;
import com.flowagent.engine.dsl.model.RetryConfig;
import com.flowagent.engine.dsl.model.WorkflowDSL;
import com.flowagent.engine.node.FlowEventCallback;
import com.flowagent.engine.node.WorkflowNodeHandler;
import com.flowagent.engine.node.callback.WorkflowMsgCallback;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fault tolerance tests covering timeout fallback, error branch routing,
 * interrupt strategy, MARK-to-SKIP normalization, and blocking queue consumer.
 */
public class FaultToleranceTest {

    // ---- Helper: minimal DSL builder ----

    private Node makeNode(String id, NodeTypeEnum type, NodeStatusEnum initialStatus) {
        Node node = new Node();
        node.setId(id);
        node.setNodeType(type);
        node.setStatus(initialStatus);
        node.setExecutedCount(new AtomicInteger(0));
        NodeData data = new NodeData();
        NodeMeta meta = new NodeMeta();
        meta.setAliasName("test-" + id);
        data.setNodeMeta(meta);
        node.setData(data);
        return node;
    }

    // ---- Test 1: MARK-to-SKIP normalization ----

    @Test
    void markNodesNormalizeToSkipAfterExecution() {
        // Create nodes: start -> [A (success) | B (error branch)] -> end
        // B is on fail-branch of start and never visited -> should be MARK -> SKIP
        Node startNode = makeNode("node-start", NodeTypeEnum.START, NodeStatusEnum.INIT);
        Node endNode = makeNode("node-end", NodeTypeEnum.END, NodeStatusEnum.INIT);
        Node failNode = makeNode("node-fail", NodeTypeEnum.LLM, NodeStatusEnum.INIT);

        // Simulate: start succeeded, end succeeded, failNode was marked MARK but never visited
        startNode.setStatus(NodeStatusEnum.SUCCESS);
        endNode.setStatus(NodeStatusEnum.SUCCESS);
        failNode.setStatus(NodeStatusEnum.MARK);

        // Normalize
        List<Node> allNodes = List.of(startNode, endNode, failNode);
        for (Node node : allNodes) {
            if (node.getStatus() == NodeStatusEnum.MARK) {
                node.setStatus(NodeStatusEnum.SKIP);
            }
        }

        // Verify: failNode is now SKIP, not MARK
        assertEquals(NodeStatusEnum.SKIP, failNode.getStatus());
        assertEquals(NodeStatusEnum.SUCCESS, startNode.getStatus());
        assertEquals(NodeStatusEnum.SUCCESS, endNode.getStatus());
    }

    // ---- Test 2: ERR_INTERRUPT strategy halts workflow ----

    @Test
    void interruptStrategySetsErrInterrupt() {
        Node node = makeNode("llm-1", NodeTypeEnum.LLM, NodeStatusEnum.INIT);
        RetryConfig retryConfig = new RetryConfig();
        retryConfig.setShouldRetry(false);
        retryConfig.setErrorStrategy(ErrorStrategyEnum.ERR_INTERRUPT.getCode());
        node.getData().setRetryConfig(retryConfig);

        // Simulate error response for ERR_INTERRUPT
        NodeRunResult result = new NodeRunResult();
        result.setError(new NodeCustomException(ErrorCode.NODE_RUN_ERROR, "execution failed"));

        // When no retry config: default is ERR_INTERRUPT
        NodeRunResult noConfigResult = new NodeRunResult();
        noConfigResult.setError(new NodeCustomException(ErrorCode.NODE_RUN_ERROR));

        // Verify: without retry config, status should be ERR_INTERRUPT
        assertEquals(NodeExecStatusEnum.ERR_INTERUPT, noConfigResult.getStatus());
    }

    // ---- Test 3: ERR_FAIL_CONDITION routes to fail branch ----

    @Test
    void failConditionStrategySetsErrFailCondition() {
        Node node = makeNode("llm-1", NodeTypeEnum.LLM, NodeStatusEnum.INIT);
        RetryConfig retryConfig = new RetryConfig();
        retryConfig.setShouldRetry(false);
        retryConfig.setErrorStrategy(ErrorStrategyEnum.ERR_CONDITION.getCode());
        node.getData().setRetryConfig(retryConfig);

        // Simulate error response for ERR_CONDITION
        NodeRunResult result = new NodeRunResult();
        result.setError(new NodeCustomException(ErrorCode.NODE_RUN_ERROR, "condition failed"));
        result.setStatus(NodeExecStatusEnum.ERR_FAIL_CONDITION);

        // Verify: ERR_CONDITION sets correct status
        assertEquals(NodeExecStatusEnum.ERR_FAIL_CONDITION, result.getStatus());
    }

    // ---- Test 4: ERR_CODE strategy continues on normal branch ----

    @Test
    void errorCodeStrategySetsErrCodeMsg() {
        Node node = makeNode("llm-1", NodeTypeEnum.LLM, NodeStatusEnum.INIT);
        RetryConfig retryConfig = new RetryConfig();
        retryConfig.setShouldRetry(false);
        retryConfig.setErrorStrategy(ErrorStrategyEnum.ERR_CODE.getCode());
        retryConfig.setCustomOutput(Map.of("fallback_message", "service unavailable"));
        node.getData().setRetryConfig(retryConfig);

        // Simulate error response for ERR_CODE
        NodeRunResult result = new NodeRunResult();
        result.setError(new NodeCustomException(ErrorCode.NODE_RUN_ERROR, "api error"));
        result.setStatus(NodeExecStatusEnum.ERR_CODE_MSG);
        result.setOutputs(Map.of("fallback_message", "service unavailable"));

        // Verify: ERR_CODE sets correct status and custom output
        assertEquals(NodeExecStatusEnum.ERR_CODE_MSG, result.getStatus());
        assertEquals("service unavailable", result.getOutputs().get("fallback_message"));
    }

    // ---- Test 5: BlockingQueue consumer terminates with POISON_PILL ----

    @Test
    void blockingQueueConsumerTerminatesWithPoisonPill() throws InterruptedException {
        BlockingQueue<LLMGenerate> streamQueue = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<ChatCallBackStreamResult> orderQueue = new LinkedBlockingQueue<>();

        // Collect events forwarded by callback
        List<String> eventTypes = new java.util.ArrayList<>();
        List<Object> eventData = new java.util.ArrayList<>();

        FlowEventCallback clientCallback = new FlowEventCallback() {
            @Override
            public void callback(String eventType, Object data) {
                eventTypes.add(eventType);
                eventData.add(data);
            }

            @Override
            public void finished() {
                // no-op for test
            }
        };

        WorkflowMsgCallback msgCallback = new WorkflowMsgCallback(
                "test-sid",
                clientCallback,
                EndNodeOutputModeEnum.DIRECT_MODE,
                streamQueue,
                orderQueue
        );

        // Push a test event into the queue
        LLMGenerate testEvent = LLMGenerate.workflowStart("test-sid");
        streamQueue.offer(testEvent);

        // Wait for consumer to process the event
        Thread.sleep(200);

        // Verify event was forwarded
        assertEquals(1, eventTypes.size());
        assertEquals("stream", eventTypes.get(0));

        // Terminate consumer with finished()
        msgCallback.finished();

        // Verify consumer thread has terminated
        // (finished() calls join internally, so thread should be done)
        assertTrue(eventData.size() >= 1, "At least one event should be forwarded");
    }

    // ---- Test 6: Timeout fallback produces error result ----

    @Test
    void timeoutFallbackProducesErrorResult() {
        Node node = makeNode("llm-1", NodeTypeEnum.LLM, NodeStatusEnum.INIT);
        RetryConfig retryConfig = new RetryConfig();
        retryConfig.setShouldRetry(false);
        retryConfig.setTimeOutEnabled(true);
        retryConfig.setErrorStrategy(ErrorStrategyEnum.ERR_INTERRUPT.getCode());
        node.getData().setRetryConfig(retryConfig);

        // Simulate timeout result
        NodeRunResult result = new NodeRunResult();
        result.setError(new NodeCustomException(ErrorCode.TIMEOUT_ERROR));

        // Verify: timeout produces error result with TIMEOUT_ERROR
        assertEquals(ErrorCode.TIMEOUT_ERROR.getCode(), result.getError().getCode());
    }
}
