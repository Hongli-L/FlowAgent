package com.flowagent.engine.node.impl;

import com.flowagent.common.enums.EndNodeOutputModeEnum;
import com.flowagent.common.enums.NodeExecStatusEnum;
import com.flowagent.engine.WorkflowContextStore;
import com.flowagent.engine.context.EngineContextHolder;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.engine.dsl.model.NodeData;
import com.flowagent.engine.dsl.model.NodeMeta;
import com.flowagent.engine.integration.model.ModelServiceClient;
import com.flowagent.engine.integration.model.bo.LlmCallback;
import com.flowagent.engine.integration.model.bo.LlmReqBo;
import com.flowagent.engine.integration.model.bo.LlmResVo;
import com.flowagent.engine.node.FlowEventCallback;
import com.flowagent.engine.node.WorkflowNodeHandler;
import com.flowagent.engine.node.callback.WorkflowMsgCallback;
import com.flowagent.engine.constants.NodeTypeEnum;
import org.junit.jupiter.api.Test;

import com.flowagent.engine.domain.callbacks.ChatCallBackStreamResult;
import com.flowagent.engine.domain.callbacks.LLMGenerate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AgentNodeHandler (agentic orchestration node).
 * Uses a scripted LLM client (no real model call) and a fake TOOL executor so the
 * ReAct loop is fully deterministic inside the process.
 */
public class AgentNodeHandlerTest {

    @Test
    void runsReactLoopToFinalAnswerAndEmitsSteps() throws Exception {
        // Scripted LLM: first call returns an action, second (after observation) returns final.
        ModelServiceClient scriptedLlm = new ModelServiceClient() {
            @Override
            public LlmResVo chatCompletion(LlmReqBo req, LlmCallback cb) {
                String prompt = req.getUserMsg();
                if (prompt == null || !prompt.contains("PREVIOUS STEPS")) {
                    return new LlmResVo(null,
                            "{\"type\":\"action\",\"thought\":\"need data\",\"nodeId\":\"tool::001\",\"args\":{\"query\":\"life\"}}",
                            null);
                }
                return new LlmResVo(null,
                        "{\"type\":\"final\",\"thought\":\"got it\",\"answer\":\"The answer is 42\"}",
                        null);
            }
        };

        AtomicInteger toolInvocations = new AtomicInteger(0);
        WorkflowNodeHandler fakeTool = new WorkflowNodeHandler() {
            @Override
            public NodeRunResult execute(NodeState ns) {
                toolInvocations.incrementAndGet();
                // The agent exposed action args under {{agent::001.args}}.
                Object args = ns.variablePool().get("agent::001", "args");
                NodeRunResult r = new NodeRunResult();
                Map<String, Object> outs = new LinkedHashMap<>();
                outs.put("result", "echo:" + args);
                r.setOutputs(outs);
                r.setStatus(NodeExecStatusEnum.SUCCESS);
                return r;
            }

            @Override
            public NodeTypeEnum getNodeType() {
                return NodeTypeEnum.TOOL;
            }
        };

        Node agentNode = makeAgentNode(Map.of(
                "goal", "Find the answer to {{node-start::001.q}}",
                "modelId", "1",
                "maxIter", 5,
                "toolNodeIds", List.of("tool::001")));
        Node toolNode = makeToolNode();

        List<Map<String, Object>> events = new ArrayList<>();
        WorkflowMsgCallback callback = capturingCallback(events);

        EngineContextHolder.EngineContext ctx = EngineContextHolder.initContext("flow-agent", "chat-agent", callback);
        ctx.setWorkflowNodes(List.of(agentNode, toolNode));
        ctx.setNodeExecutors(Map.of(NodeTypeEnum.TOOL, fakeTool));
        try {
            WorkflowContextStore pool = new WorkflowContextStore();
            AgentNodeHandler handler = new AgentNodeHandler(scriptedLlm);
            NodeRunResult result = handler.execute(new NodeState(agentNode, pool, callback));

            assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus(), "agent run should succeed");
            assertEquals("The answer is 42", result.getOutputs().get("output"));
            assertTrue(toolInvocations.get() >= 1, "tool node should have been invoked by the agent");
            // One workflow_step per iteration (action + observation + final) => at least 2.
            long stepEvents = events.stream().filter(e -> "workflow_step".equals(e.get("type"))).count();
            assertTrue(stepEvents >= 2, "should emit workflow_step events per iteration");
            assertNotNull(pool.get("agent::001", "scratchpad"), "reasoning trace should be persisted");
        } finally {
            EngineContextHolder.remove();
        }
    }

    @Test
    void missingGoalProducesError() throws Exception {
        ModelServiceClient scriptedLlm = new ModelServiceClient() {
            @Override
            public LlmResVo chatCompletion(LlmReqBo req, LlmCallback cb) {
                return new LlmResVo(null, "{\"type\":\"final\",\"answer\":\"x\"}", null);
            }
        };
        Node agentNode = makeAgentNode(Map.of("modelId", "1"));
        WorkflowMsgCallback callback = capturingCallback(new ArrayList<>());
        EngineContextHolder.EngineContext ctx = EngineContextHolder.initContext("flow-agent", "chat-agent", callback);
        ctx.setWorkflowNodes(List.of(agentNode));
        ctx.setNodeExecutors(Map.of());
        try {
            NodeRunResult result = new AgentNodeHandler(scriptedLlm)
                    .execute(new NodeState(agentNode, new WorkflowContextStore(), callback));
            assertNotEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
            assertNotNull(result.getError());
        } finally {
            EngineContextHolder.remove();
        }
    }

    private Node makeAgentNode(Map<String, Object> nodeParam) {
        Node node = new Node();
        node.setId("agent::001");
        node.setExecutedCount(new AtomicInteger(0));
        NodeData data = new NodeData();
        NodeMeta meta = new NodeMeta();
        meta.setAliasName("researcher");
        data.setNodeMeta(meta);
        data.setNodeParam(nodeParam);
        node.setData(data);
        return node;
    }

    private Node makeToolNode() {
        Node node = new Node();
        node.setId("tool::001");
        node.setExecutedCount(new AtomicInteger(0));
        NodeData data = new NodeData();
        NodeMeta meta = new NodeMeta();
        meta.setAliasName("echo tool");
        data.setNodeMeta(meta);
        data.setNodeParam(Map.of("description", "echoes back the action args"));
        node.setData(data);
        return node;
    }

    private WorkflowMsgCallback capturingCallback(List<Map<String, Object>> sink) {
        FlowEventCallback clientCallback = (eventType, data) -> {
            if ("workflow_step".equals(eventType)) {
                sink.add(Map.of("type", eventType, "data", data));
            }
        };
        BlockingQueue<LLMGenerate> streamQueue = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<ChatCallBackStreamResult> orderQueue = new LinkedBlockingQueue<>();
        return new WorkflowMsgCallback("test-sid", clientCallback, EndNodeOutputModeEnum.DIRECT_MODE,
                streamQueue, orderQueue);
    }
}
