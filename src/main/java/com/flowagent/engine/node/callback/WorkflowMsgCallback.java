package com.flowagent.engine.node.callback;

import com.flowagent.common.enums.EndNodeOutputModeEnum;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.callbacks.ChatCallBackStreamResult;
import com.flowagent.engine.domain.callbacks.ChatCallBacks;
import com.flowagent.engine.domain.callbacks.LLMGenerate;
import com.flowagent.engine.node.FlowEventCallback;
import com.flowagent.engine.util.AsyncUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Workflow stream callback implementation that bridges ChatCallBacks with FlowEventCallback
 * This class handles workflow events and forwards them to the underlying FlowEventCallback
 */
@Slf4j
public class WorkflowMsgCallback implements FlowEventCallback {

    private final ChatCallBacks chatCallBacks;
    private final FlowEventCallback clientCallback;

    private volatile boolean tag = true;
    private volatile boolean scheduleTaskOver = false;

    public WorkflowMsgCallback(String sid,
                               FlowEventCallback clientCallback,
                               EndNodeOutputModeEnum endNodeOutputMode,
                               Queue<LLMGenerate> streamQueue,
                               Queue<ChatCallBackStreamResult> needOrderStreamResultQ) {
        this.clientCallback = clientCallback;
        this.chatCallBacks = new ChatCallBacks(
                sid,
                streamQueue, // streamQueue is handled internally
                endNodeOutputMode,
                Set.of(),
                needOrderStreamResultQ
        );


        // Consumer thread: pull LLM stream output from queue and forward to client
        AsyncUtil.execute(() -> {
            while (tag) {
                try {
                    // Block-wait for data availability
                    while (streamQueue.isEmpty() && tag) {
                        AsyncUtil.sleep(10); // Brief sleep to avoid busy-wait
                    }

                    LLMGenerate resp = streamQueue.poll();
                    if (resp != null) {
                        clientCallback.callback("stream", resp);
                    }
                } catch (Exception e) {
                    log.error("Error in stream callback", e);
                }
            }
            scheduleTaskOver = true;
        });
    }

    /**
     * Handle workflow start event
     */
    public void onWorkflowStart() {
        chatCallBacks.onWorkflowStart();
    }

    /**
     * Handle workflow end event
     *
     * @param message Final node run result containing execution summary
     */
    public void onWorkflowEnd(NodeRunResult message) {
        chatCallBacks.onWorkflowEnd(message);
    }

    /**
     * Handle node start event
     *
     * @param code      Status code for the node start operation
     * @param nodeId    Unique identifier of the starting node
     * @param aliasName Human-readable name for the node
     */
    public void onNodeStart(int code, String nodeId, String aliasName) {
        chatCallBacks.onNodeStart(code, nodeId, aliasName);
    }

    /**
     * Handle node processing event
     *
     * @param code             Status code for the node processing operation
     * @param nodeId           Unique identifier of the processing node
     * @param aliasName        Human-readable name for the node
     * @param message          Processing message or error content
     * @param reasoningContent Additional reasoning or intermediate content
     */
    public void onNodeProcess(int code, String nodeId, String aliasName,
                              String message, String reasoningContent) {
        chatCallBacks.onNodeProcess(code, nodeId, aliasName, message, reasoningContent);
    }

    /**
     * Handle node interrupt event
     *
     * @param eventId      Unique identifier for the interrupt event
     * @param value        Interrupt event data
     * @param nodeId       Unique identifier of the interrupted node
     * @param aliasName    Human-readable name for the node
     * @param code         Status code for the interrupt operation
     * @param finishReason Reason for the interrupt
     * @param needReply    Whether a reply is needed for the interrupt
     */
    public void onNodeInterrupt(String eventId, Map<String, Object> value,
                                String nodeId, String aliasName, int code,
                                String finishReason, boolean needReply) {
        chatCallBacks.onNodeInterrupt(eventId, value, nodeId, aliasName, code, finishReason, needReply);
    }

    /**
     * Handle node end event
     *
     * @param nodeId    Unique identifier of the completed node
     * @param aliasName Human-readable name for the node
     * @param message   Node execution result, null if execution failed
     */
    public void onNodeEnd(String nodeId, String aliasName,
                          NodeRunResult message) {
        chatCallBacks.onNodeEnd(nodeId, aliasName, message, message.getError());
    }

    @Override
    public void callback(String eventType, Object data) {
        clientCallback.callback(eventType, data);
    }


    public void finished() {
        tag = false;
        while (!scheduleTaskOver) {
            AsyncUtil.sleep(10);
        }

        // Wait for async consumer thread to finish before draining remaining events
        while (!chatCallBacks.getStreamQueue().isEmpty()) {
            var resp = chatCallBacks.getStreamQueue().poll();
            callback("stream", resp);
        }

        while (!chatCallBacks.getOrderStreamResultQ().isEmpty()) {
            var resp = chatCallBacks.getOrderStreamResultQ().poll();
            clientCallback.callback("stream", resp.getNodeAnswerContent());
        }

        clientCallback.finished();
    }

    /**
     * Handle end node executed event
     */
    public void onEndNodeExecuted(String nodeId, String aliasName, NodeRunResult message) {
        message.setNodeAnswerContent((String) message.getOutputs().getOrDefault("content", ""));
        message.setNodeAnswerReasoningContent((String) message.getOutputs().getOrDefault("reasoning_content", ""));
        message.setOutputs(message.getInputs());
        message.setInputs(Map.of());
        onNodeEnd(nodeId, aliasName, message);
    }

    /**
     * Handle start node executed event
     */
    public void onStartNodeExecuted(String nodeId, String aliasName, NodeRunResult message) {
        message.setOutputs(Map.of());
        this.onNodeEnd(nodeId, aliasName, message);
    }
}