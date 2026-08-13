package com.flowagent.engine.node.impl;

import com.flowagent.common.enums.EndNodeOutputModeEnum;
import com.flowagent.common.enums.NodeExecStatusEnum;
import com.flowagent.engine.WorkflowContextStore;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.engine.dsl.model.InputItem;
import com.flowagent.engine.dsl.model.InputSchema;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.engine.dsl.model.NodeData;
import com.flowagent.engine.dsl.model.NodeMeta;
import com.flowagent.engine.dsl.model.Value;
import com.flowagent.engine.node.callback.WorkflowMsgCallback;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EndNodeHandler.
 *
 * <p>Design note: the end-node-executed callback stores the rendered template into
 * {@code nodeAnswerContent} (and reasoning into {@code nodeAnswerReasoningContent}), then rewrites
 * {@code outputs} to equal the resolved {@code inputs}. So the final answer is asserted from
 * {@code getNodeAnswerContent()}, not from {@code getOutputs().get("content")}.
 */
public class EndNodeHandlerTest {

    private WorkflowMsgCallback noopCallback() {
        com.flowagent.engine.node.FlowEventCallback clientCallback = (eventType, data) -> {
        };
        java.util.concurrent.BlockingQueue<com.flowagent.engine.domain.callbacks.LLMGenerate> streamQueue =
                new java.util.concurrent.LinkedBlockingQueue<>();
        java.util.concurrent.LinkedBlockingQueue<com.flowagent.engine.domain.callbacks.ChatCallBackStreamResult> orderQueue =
                new java.util.concurrent.LinkedBlockingQueue<>();
        return new WorkflowMsgCallback("test-sid", clientCallback, EndNodeOutputModeEnum.DIRECT_MODE,
                streamQueue, orderQueue);
    }

    private Node makeEndNode(Map<String, Object> nodeParam, List<InputItem> inputs) {
        Node node = new Node();
        node.setId("node-end::004");
        node.setExecutedCount(new AtomicInteger(0));
        NodeData data = new NodeData();
        NodeMeta meta = new NodeMeta();
        meta.setAliasName("end");
        data.setNodeMeta(meta);
        data.setNodeParam(nodeParam);
        if (inputs != null) {
            data.setInputs(inputs);
        }
        node.setData(data);
        return node;
    }

    private InputItem refInput(String name, String nodeId, String outName) {
        Map<String, String> ref = new LinkedHashMap<>();
        ref.put("nodeId", nodeId);
        ref.put("name", outName);
        return new InputItem("in-" + name, name, new InputSchema("string", new Value("ref", ref)));
    }

    private InputItem literalInput(String name, Object value) {
        return new InputItem("in-" + name, name, new InputSchema("string", new Value("literal", value)));
    }

    @Test
    void variableModeRendersTemplateIntoAnswerContent() {
        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("llm::002", "answer", "42");
        EndNodeHandler handler = new EndNodeHandler();
        NodeRunResult result = handler.execute(new NodeState(
                makeEndNode(Map.of("outputMode", 1, "template", "The answer is {{answer}}"),
                        List.of(refInput("answer", "llm::002", "answer"))),
                pool, noopCallback()));
        assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
        assertEquals("The answer is 42", result.getNodeAnswerContent());
    }

    @Test
    void directModeJoinsInputsIntoAnswerContent() {
        EndNodeHandler handler = new EndNodeHandler();
        NodeRunResult result = handler.execute(new NodeState(
                makeEndNode(Map.of("outputMode", 0), List.of(literalInput("user_input", "hello"))),
                new WorkflowContextStore(), noopCallback()));
        assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
        assertTrue(result.getNodeAnswerContent().contains("hello"),
                "direct mode should join inputs into the final answer content");
    }

    @Test
    void missingTemplateFallsBackToJoinedInputs() {
        EndNodeHandler handler = new EndNodeHandler();
        NodeRunResult result = handler.execute(new NodeState(
                makeEndNode(Map.of("outputMode", 1), List.of(literalInput("x", "y"))),
                new WorkflowContextStore(), noopCallback()));
        assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
        assertTrue(result.getNodeAnswerContent().contains("x: y"));
    }

    @Test
    void reasoningTemplateStoredInReasoningContent() {
        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("llm::002", "answer", "42");
        pool.set("llm::002", "reason", "it is the answer");
        EndNodeHandler handler = new EndNodeHandler();
        NodeRunResult result = handler.execute(new NodeState(
                makeEndNode(Map.of("outputMode", 1, "template", "The answer is {{answer}}",
                                "reasoningTemplate", "because {{reason}}"),
                        List.of(refInput("answer", "llm::002", "answer"),
                                refInput("reason", "llm::002", "reason"))),
                pool, noopCallback()));
        assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
        assertEquals("because it is the answer", result.getNodeAnswerReasoningContent());
        assertEquals("The answer is 42", result.getNodeAnswerContent());
    }
}
