package com.flowagent.engine.node.impl;

import com.flowagent.common.enums.EndNodeOutputModeEnum;
import com.flowagent.common.enums.NodeExecStatusEnum;
import com.flowagent.engine.WorkflowContextStore;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.engine.domain.callbacks.ChatCallBackStreamResult;
import com.flowagent.engine.domain.callbacks.LLMGenerate;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.engine.dsl.model.NodeData;
import com.flowagent.engine.dsl.model.NodeMeta;
import com.flowagent.engine.node.FlowEventCallback;
import com.flowagent.engine.node.callback.WorkflowMsgCallback;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for IfElseNodeHandler: boolean condition evaluation and branch routing.
 */
public class IfElseNodeHandlerTest {

    private WorkflowMsgCallback noopCallback() {
        FlowEventCallback clientCallback = new FlowEventCallback() {
            @Override
            public void callback(String eventType, Object data) {
                // no-op for unit test
            }
        };
        BlockingQueue<LLMGenerate> streamQueue = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<ChatCallBackStreamResult> orderQueue = new LinkedBlockingQueue<>();
        return new WorkflowMsgCallback("test-sid", clientCallback, EndNodeOutputModeEnum.DIRECT_MODE,
                streamQueue, orderQueue);
    }

    private Node makeNode(String id, Map<String, Object> nodeParam) {
        Node node = new Node();
        node.setId(id);
        node.setExecutedCount(new AtomicInteger(0));
        NodeData data = new NodeData();
        NodeMeta meta = new NodeMeta();
        meta.setAliasName("test-" + id);
        data.setNodeMeta(meta);
        data.setNodeParam(nodeParam);
        node.setData(data);
        return node;
    }

    private NodeRunResult run(String condition, WorkflowContextStore pool) {
        IfElseNodeHandler handler = new IfElseNodeHandler();
        Node node = makeNode("if-else::001", Map.of("condition", condition));
        return handler.execute(new NodeState(node, pool, noopCallback()));
    }

    @Test
    void trueConditionRoutesToSuccessBranch() {
        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("node-start::001", "user_input", "I want a refund");
        NodeRunResult result = run("{{node-start::001.user_input}} contains \"refund\"", pool);
        assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
        assertEquals("true", result.getOutputs().get("branch"));
    }

    @Test
    void falseConditionRoutesToFailBranch() {
        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("node-start::001", "user_input", "hello world");
        NodeRunResult result = run("{{node-start::001.user_input}} contains \"refund\"", pool);
        assertEquals(NodeExecStatusEnum.ERR_FAIL_CONDITION, result.getStatus());
        assertEquals("false", result.getOutputs().get("branch"));
    }

    @Test
    void numericComparisonWithGreaterOrEqual() {
        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("llm::002", "score", 75);
        NodeRunResult result = run("{{llm::002.score}} >= 60", pool);
        assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
    }

    @Test
    void numericComparisonLessThanRoutesToFail() {
        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("llm::002", "score", 42);
        NodeRunResult result = run("{{llm::002.score}} >= 60", pool);
        assertEquals(NodeExecStatusEnum.ERR_FAIL_CONDITION, result.getStatus());
    }

    @Test
    void equalityAndConjunction() {
        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("llm::002", "score", 75);
        pool.set("node-start::001", "flag", true);
        NodeRunResult result = run("{{llm::002.score}} >= 60 && {{node-start::001.flag}} == true", pool);
        assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
    }

    @Test
    void disjunctionShortCircuitsOnFirstTrue() {
        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("a", "x", 1);
        pool.set("b", "y", 2);
        NodeRunResult result = run("{{a.x}} == 1 || {{b.y}} == 99", pool);
        assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
    }

    @Test
    void disjunctionAllFalseRoutesToFail() {
        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("a", "x", 1);
        pool.set("b", "y", 2);
        NodeRunResult result = run("{{a.x}} == 9 || {{b.y}} == 99", pool);
        assertEquals(NodeExecStatusEnum.ERR_FAIL_CONDITION, result.getStatus());
    }

    @Test
    void stringEqualityMatch() {
        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("switch::003", "mode", "prod");
        NodeRunResult result = run("{{switch::003.mode}} == \"prod\"", pool);
        assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
    }

    @Test
    void notEqualOperator() {
        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("switch::003", "mode", "prod");
        NodeRunResult result = run("{{switch::003.mode}} != \"dev\"", pool);
        assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
    }
}
