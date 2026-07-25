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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConditionSwitchNodeHandler: first-match routing and default branch.
 */
public class ConditionSwitchNodeHandlerTest {

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

    private NodeRunResult run(Map<String, Object> nodeParam, WorkflowContextStore pool) {
        ConditionSwitchNodeHandler handler = new ConditionSwitchNodeHandler();
        Node node = makeNode("condition-switch::001", nodeParam);
        return handler.execute(new NodeState(node, pool, noopCallback()));
    }

    private Map<String, Object> condition(String expr, String branch) {
        Map<String, Object> c = new HashMap<>();
        c.put("condition", expr);
        c.put("branch", branch);
        return c;
    }

    @Test
    void firstMatchingConditionRoutesToSuccessWithBranchKey() {
        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("llm::002", "score", 75);

        List<Map<String, Object>> conditions = new ArrayList<>();
        conditions.add(condition("{{llm::002.score}} >= 90", "high"));
        conditions.add(condition("{{llm::002.score}} >= 60", "mid"));

        Map<String, Object> nodeParam = new HashMap<>();
        nodeParam.put("conditions", conditions);
        nodeParam.put("defaultBranch", "low");

        NodeRunResult result = run(nodeParam, pool);
        assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
        assertEquals("mid", result.getOutputs().get("branch"));
        assertEquals("mid", result.getOutputs().get("matchedBranch"));
    }

    @Test
    void noMatchRoutesToFailWithDefaultBranch() {
        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("llm::002", "score", 20);

        List<Map<String, Object>> conditions = new ArrayList<>();
        conditions.add(condition("{{llm::002.score}} >= 90", "high"));
        conditions.add(condition("{{llm::002.score}} >= 60", "mid"));

        Map<String, Object> nodeParam = new HashMap<>();
        nodeParam.put("conditions", conditions);
        nodeParam.put("defaultBranch", "low");

        NodeRunResult result = run(nodeParam, pool);
        assertEquals(NodeExecStatusEnum.ERR_FAIL_CONDITION, result.getStatus());
        assertEquals("low", result.getOutputs().get("branch"));
    }

    @Test
    void defaultBranchUsedWhenUnspecified() {
        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("llm::002", "score", 20);

        List<Map<String, Object>> conditions = new ArrayList<>();
        conditions.add(condition("{{llm::002.score}} >= 60", "mid"));

        Map<String, Object> nodeParam = new HashMap<>();
        nodeParam.put("conditions", conditions);

        NodeRunResult result = run(nodeParam, pool);
        assertEquals(NodeExecStatusEnum.ERR_FAIL_CONDITION, result.getStatus());
        assertEquals("default", result.getOutputs().get("branch"));
    }

    @Test
    void emptyConditionsRouteToDefault() {
        WorkflowContextStore pool = new WorkflowContextStore();
        Map<String, Object> nodeParam = new HashMap<>();
        nodeParam.put("conditions", new ArrayList<Map<String, Object>>());
        nodeParam.put("defaultBranch", "fallback");

        NodeRunResult result = run(nodeParam, pool);
        assertEquals(NodeExecStatusEnum.ERR_FAIL_CONDITION, result.getStatus());
        assertEquals("fallback", result.getOutputs().get("branch"));
    }
}
