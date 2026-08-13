package com.flowagent.engine.node.impl;

import com.flowagent.common.enums.EndNodeOutputModeEnum;
import com.flowagent.common.enums.NodeExecStatusEnum;
import com.flowagent.engine.WorkflowContextStore;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.engine.dsl.model.NodeData;
import com.flowagent.engine.dsl.model.NodeMeta;
import com.flowagent.engine.node.FlowEventCallback;
import com.flowagent.engine.node.callback.WorkflowMsgCallback;
import com.flowagent.engine.domain.callbacks.ChatCallBackStreamResult;
import com.flowagent.engine.domain.callbacks.LLMGenerate;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StartNodeHandler.
 *
 * <p>Design note: the start node echoes its initial inputs into the context store (keyed by the
 * start node id) so downstream nodes can reference them. By design the node-executed callback
 * then clears {@code result.outputs} (for a start node input == output), so assertions read the
 * values back from the context store, not from {@code result.getOutputs()}.
 */
public class StartNodeHandlerTest {

    private WorkflowMsgCallback noopCallback() {
        FlowEventCallback clientCallback = (eventType, data) -> {
        };
        BlockingQueue<LLMGenerate> streamQueue = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<ChatCallBackStreamResult> orderQueue = new LinkedBlockingQueue<>();
        return new WorkflowMsgCallback("test-sid", clientCallback, EndNodeOutputModeEnum.DIRECT_MODE,
                streamQueue, orderQueue);
    }

    private Node makeStartNode() {
        Node node = new Node();
        node.setId("node-start::001");
        node.setExecutedCount(new AtomicInteger(0));
        NodeData data = new NodeData();
        NodeMeta meta = new NodeMeta();
        meta.setAliasName("start");
        data.setNodeMeta(meta);
        node.setData(data);
        return node;
    }

    @Test
    void passesInputsIntoContextStore() {
        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("node-start::001", "user_input", "hello world");
        StartNodeHandler handler = new StartNodeHandler();
        NodeRunResult result = handler.execute(new NodeState(makeStartNode(), pool, noopCallback()));
        assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
        // Downstream reference reads from the context store, not from result.outputs().
        assertEquals("hello world", pool.get("node-start::001", "user_input"));
    }

    @Test
    void emptyInputsProduceEmptyOutputs() {
        StartNodeHandler handler = new StartNodeHandler();
        NodeRunResult result = handler.execute(new NodeState(makeStartNode(), new WorkflowContextStore(), noopCallback()));
        assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
        // Callback clears outputs for a start node by design.
        assertTrue(result.getOutputs().isEmpty());
    }

    @Test
    void multipleInputsAreAllStored() {
        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("node-start::001", "a", 1);
        pool.set("node-start::001", "b", "two");
        StartNodeHandler handler = new StartNodeHandler();
        NodeRunResult result = handler.execute(new NodeState(makeStartNode(), pool, noopCallback()));
        assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
        assertEquals(1, pool.get("node-start::001", "a"));
        assertEquals("two", pool.get("node-start::001", "b"));
    }
}
