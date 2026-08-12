package com.flowagent.engine.node.impl.llm;

import com.flowagent.common.enums.EndNodeOutputModeEnum;
import com.flowagent.common.enums.NodeExecStatusEnum;
import com.flowagent.common.exception.ErrorCode;
import com.flowagent.common.exception.ModelInvocationException;
import com.flowagent.engine.WorkflowContextStore;
import com.flowagent.engine.context.EngineContextHolder;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.engine.dsl.model.InputItem;
import com.flowagent.engine.dsl.model.InputSchema;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.engine.dsl.model.NodeData;
import com.flowagent.engine.dsl.model.NodeMeta;
import com.flowagent.engine.dsl.model.OutputItem;
import com.flowagent.engine.dsl.model.Value;
import com.flowagent.engine.integration.model.LlmChatHistory;
import com.flowagent.engine.integration.model.ModelServiceClient;
import com.flowagent.engine.integration.model.bo.LlmCallback;
import com.flowagent.engine.integration.model.bo.LlmReqBo;
import com.flowagent.engine.integration.model.bo.LlmResVo;
import com.flowagent.engine.node.callback.WorkflowMsgCallback;
import com.flowagent.engine.domain.callbacks.ChatCallBackStreamResult;
import com.flowagent.engine.domain.callbacks.LLMGenerate;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for LLMNodeHandler. A scripted ModelServiceClient (no real model call) lets the
 * tests assert exactly what prompt / system prompt reached the model and what got rendered back.
 */
public class LLMNodeHandlerTest {

    private WorkflowMsgCallback noopCallback() {
        com.flowagent.engine.node.FlowEventCallback clientCallback = (eventType, data) -> {
        };
        BlockingQueue<LLMGenerate> streamQueue = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<ChatCallBackStreamResult> orderQueue = new LinkedBlockingQueue<>();
        return new WorkflowMsgCallback("test-sid", clientCallback, EndNodeOutputModeEnum.DIRECT_MODE,
                streamQueue, orderQueue);
    }

    private Node makeLlmNode(Map<String, Object> nodeParam, List<InputItem> inputs, List<OutputItem> outputs) {
        Node node = new Node();
        node.setId("llm::002");
        node.setExecutedCount(new AtomicInteger(0));
        NodeData data = new NodeData();
        NodeMeta meta = new NodeMeta();
        meta.setAliasName("assistant");
        data.setNodeMeta(meta);
        data.setNodeParam(nodeParam);
        if (inputs != null) {
            data.setInputs(inputs);
        }
        if (outputs != null) {
            data.setOutputs(outputs);
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

    private ModelServiceClient scriptedLlm(AtomicReference<LlmReqBo> captured, String content) {
        return new ModelServiceClient() {
            @Override
            public LlmResVo chatCompletion(LlmReqBo req, LlmCallback cb) {
                captured.set(req);
                Usage usage = mock(Usage.class);
                when(usage.getCompletionTokens()).thenReturn(7);
                when(usage.getPromptTokens()).thenReturn(3);
                when(usage.getTotalTokens()).thenReturn(10);
                return new LlmResVo(usage, content, null);
            }
        };
    }

    @Test
    void rendersUserPromptWithRefInput() throws Exception {
        WorkflowContextStore pool = new WorkflowContextStore();
        pool.set("node-start::001", "user_input", "tell me about Java");
        AtomicReference<LlmReqBo> captured = new AtomicReference<>();
        ModelServiceClient scripted = scriptedLlm(captured, "Java is a language");
        WorkflowMsgCallback cb = noopCallback();
        EngineContextHolder.EngineContext ctx = EngineContextHolder.initContext("flow-llm",
                "chat-" + UUID.randomUUID(), cb);
        try {
            LLMNodeHandler handler = new LLMNodeHandler(scripted);
            NodeRunResult result = handler.execute(new NodeState(
                    makeLlmNode(Map.of("modelId", 1, "template", "Q: {{user_input}}"),
                            List.of(refInput("user_input", "node-start::001", "user_input")), null),
                    pool, cb));
            assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
            assertTrue(captured.get().getUserMsg().contains("tell me about Java"),
                    "resolved prompt should contain the upstream value");
            assertEquals("Java is a language", result.getOutputs().get("output"));
        } finally {
            EngineContextHolder.remove();
        }
    }

    @Test
    void rendersLiteralInputWithoutReference() throws Exception {
        AtomicReference<LlmReqBo> captured = new AtomicReference<>();
        ModelServiceClient scripted = scriptedLlm(captured, "ok");
        WorkflowMsgCallback cb = noopCallback();
        EngineContextHolder.EngineContext ctx = EngineContextHolder.initContext("flow-llm",
                "chat-" + UUID.randomUUID(), cb);
        try {
            LLMNodeHandler handler = new LLMNodeHandler(scripted);
            NodeRunResult result = handler.execute(new NodeState(
                    makeLlmNode(Map.of("modelId", 1, "template", "Topic: {{topic}}"),
                            List.of(literalInput("topic", "weather")), null),
                    new WorkflowContextStore(), cb));
            assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
            assertTrue(captured.get().getUserMsg().contains("weather"));
        } finally {
            EngineContextHolder.remove();
        }
    }

    @Test
    void systemPromptRenderedFromTemplate() throws Exception {
        AtomicReference<LlmReqBo> captured = new AtomicReference<>();
        ModelServiceClient scripted = scriptedLlm(captured, "ok");
        WorkflowMsgCallback cb = noopCallback();
        EngineContextHolder.EngineContext ctx = EngineContextHolder.initContext("flow-llm",
                "chat-" + UUID.randomUUID(), cb);
        try {
            LLMNodeHandler handler = new LLMNodeHandler(scripted);
            handler.execute(new NodeState(
                    makeLlmNode(Map.of("modelId", 1, "template", "hi",
                            "systemTemplate", "You are a {{role}} assistant"),
                            List.of(literalInput("role", "helpful")), null),
                    new WorkflowContextStore(), cb));
            assertEquals("You are a helpful assistant", captured.get().getSystemMsg());
        } finally {
            EngineContextHolder.remove();
        }
    }

    @Test
    void outputUsesOutputItemName() throws Exception {
        ModelServiceClient scripted = scriptedLlm(new AtomicReference<>(), "final answer");
        WorkflowMsgCallback cb = noopCallback();
        EngineContextHolder.EngineContext ctx = EngineContextHolder.initContext("flow-llm",
                "chat-" + UUID.randomUUID(), cb);
        try {
            LLMNodeHandler handler = new LLMNodeHandler(scripted);
            NodeRunResult result = handler.execute(new NodeState(
                    makeLlmNode(Map.of("modelId", 1, "template", "hi"),
                            null, List.of(new OutputItem("o1", "answer", null, false))),
                    new WorkflowContextStore(), cb));
            assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
            assertEquals("final answer", result.getOutputs().get("answer"));
        } finally {
            EngineContextHolder.remove();
        }
    }

    @Test
    void defaultOutputKeyIsOutputWhenNoOutputItem() throws Exception {
        ModelServiceClient scripted = scriptedLlm(new AtomicReference<>(), "plain text");
        WorkflowMsgCallback cb = noopCallback();
        EngineContextHolder.EngineContext ctx = EngineContextHolder.initContext("flow-llm",
                "chat-" + UUID.randomUUID(), cb);
        try {
            LLMNodeHandler handler = new LLMNodeHandler(scripted);
            NodeRunResult result = handler.execute(new NodeState(
                    makeLlmNode(Map.of("modelId", 1, "template", "hi"), null, null),
                    new WorkflowContextStore(), cb));
            assertEquals("plain text", result.getOutputs().get("output"));
        } finally {
            EngineContextHolder.remove();
        }
    }

    @Test
    void capturesTokenUsage() throws Exception {
        AtomicReference<LlmReqBo> captured = new AtomicReference<>();
        ModelServiceClient scripted = scriptedLlm(captured, "ok");
        WorkflowMsgCallback cb = noopCallback();
        EngineContextHolder.EngineContext ctx = EngineContextHolder.initContext("flow-llm",
                "chat-" + UUID.randomUUID(), cb);
        try {
            LLMNodeHandler handler = new LLMNodeHandler(scripted);
            NodeRunResult result = handler.execute(new NodeState(
                    makeLlmNode(Map.of("modelId", 1, "template", "hi"), null, null),
                    new WorkflowContextStore(), cb));
            assertNotNull(result.getTokenCost());
            assertEquals(10, result.getTokenCost().getTotalTokens());
            assertEquals(3, result.getTokenCost().getPromptTokens());
            assertEquals(7, result.getTokenCost().getCompletionTokens());
        } finally {
            EngineContextHolder.remove();
        }
    }

    @Test
    void missingTemplateFails() throws Exception {
        ModelServiceClient scripted = scriptedLlm(new AtomicReference<>(), "x");
        WorkflowMsgCallback cb = noopCallback();
        EngineContextHolder.EngineContext ctx = EngineContextHolder.initContext("flow-llm",
                "chat-" + UUID.randomUUID(), cb);
        try {
            LLMNodeHandler handler = new LLMNodeHandler(scripted);
            NodeRunResult result = handler.execute(new NodeState(
                    makeLlmNode(Map.of("modelId", 1), null, null),
                    new WorkflowContextStore(), cb));
            assertNotEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
            assertNotNull(result.getError());
        } finally {
            EngineContextHolder.remove();
        }
    }

    @Test
    void missingModelIdFails() throws Exception {
        ModelServiceClient scripted = scriptedLlm(new AtomicReference<>(), "x");
        WorkflowMsgCallback cb = noopCallback();
        EngineContextHolder.EngineContext ctx = EngineContextHolder.initContext("flow-llm",
                "chat-" + UUID.randomUUID(), cb);
        try {
            LLMNodeHandler handler = new LLMNodeHandler(scripted);
            NodeRunResult result = handler.execute(new NodeState(
                    makeLlmNode(Map.of("template", "hi"), null, null),
                    new WorkflowContextStore(), cb));
            assertNotEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
            assertNotNull(result.getError());
        } finally {
            EngineContextHolder.remove();
        }
    }

    @Test
    void chatHistoryRecordedWhenEnabled() throws Exception {
        String chatId = "chat-hist-" + UUID.randomUUID();
        ModelServiceClient scripted = scriptedLlm(new AtomicReference<>(), "ok");
        WorkflowMsgCallback cb = noopCallback();
        EngineContextHolder.EngineContext ctx = EngineContextHolder.initContext("flow-llm", chatId, cb);
        try {
            Map<String, Object> historyCfg = new LinkedHashMap<>();
            historyCfg.put("isEnabled", true);
            historyCfg.put("rounds", 5);
            LLMNodeHandler handler = new LLMNodeHandler(scripted);
            handler.execute(new NodeState(
                    makeLlmNode(Map.of("modelId", 1, "template", "remember this",
                            "enableChatHistoryV2", historyCfg), null, null),
                    new WorkflowContextStore(), cb));
            List<LlmChatHistory.ChatItem> history =
                    LlmChatHistory.getHistory(chatId, "llm::002", 5);
            assertNotNull(history);
            assertFalse(history.isEmpty(), "user message should be persisted to chat history");
        } finally {
            EngineContextHolder.remove();
        }
    }

    /**
     * A scripted client that fails on the call numbers listed in {@code failCallToError} and
     * returns {@code successContent} on any other call. Every call's request is recorded so tests
     * can assert which model endpoint was actually invoked.
     */
    private ModelServiceClient fallbackScripted(List<LlmReqBo> captured,
                                                Map<Integer, String> failCallToError,
                                                String successContent) {
        return new ModelServiceClient() {
            private int n = 0;

            @Override
            public LlmResVo chatCompletion(LlmReqBo req, LlmCallback cb) {
                n++;
                captured.add(req);
                String err = failCallToError.get(n);
                if (err != null) {
                    throw new ModelInvocationException(err, null, 500, true);
                }
                Usage usage = mock(Usage.class);
                when(usage.getCompletionTokens()).thenReturn(5);
                when(usage.getPromptTokens()).thenReturn(2);
                when(usage.getTotalTokens()).thenReturn(7);
                return new LlmResVo(usage, successContent, null);
            }
        };
    }

    private Map<String, Object> fallbackEntry(String domain, String url, String apiKey) {
        Map<String, Object> fb = new LinkedHashMap<>();
        fb.put("domain", domain);
        fb.put("url", url);
        fb.put("apiKey", apiKey);
        return fb;
    }

    @Test
    void fallsBackToSecondModelWhenPrimaryFails() throws Exception {
        List<LlmReqBo> calls = new ArrayList<>();
        Map<Integer, String> fail = new LinkedHashMap<>();
        fail.put(1, "primary model 500");
        ModelServiceClient scripted = fallbackScripted(calls, fail, "deepseek answer");
        WorkflowMsgCallback cb = noopCallback();
        EngineContextHolder.EngineContext ctx = EngineContextHolder.initContext("flow-fb",
                "chat-" + UUID.randomUUID(), cb);
        try {
            LLMNodeHandler handler = new LLMNodeHandler(scripted);
            NodeRunResult result = handler.execute(new NodeState(
                    makeLlmNode(Map.of("modelId", 1, "domain", "gpt-4o", "template", "hi",
                            "fallbackModels", List.of(fallbackEntry("deepseek-chat", "http://fb", "k2"))),
                            null, null),
                    new WorkflowContextStore(), cb));
            assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
            assertEquals("deepseek answer", result.getOutputs().get("output"));
            assertEquals("deepseek-chat", result.getModelUsed());
            assertEquals(2, result.getModelAttempts());
            assertEquals("gpt-4o", calls.get(0).getModel());
            assertEquals("deepseek-chat", calls.get(1).getModel());
        } finally {
            EngineContextHolder.remove();
        }
    }

    @Test
    void usesPrimaryModelWhenNoFallbackConfigured() throws Exception {
        List<LlmReqBo> calls = new ArrayList<>();
        ModelServiceClient scripted = fallbackScripted(calls, Map.of(), "primary answer");
        WorkflowMsgCallback cb = noopCallback();
        EngineContextHolder.EngineContext ctx = EngineContextHolder.initContext("flow-fb",
                "chat-" + UUID.randomUUID(), cb);
        try {
            LLMNodeHandler handler = new LLMNodeHandler(scripted);
            NodeRunResult result = handler.execute(new NodeState(
                    makeLlmNode(Map.of("modelId", 1, "domain", "gpt-4o", "template", "hi"), null, null),
                    new WorkflowContextStore(), cb));
            assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
            assertEquals("gpt-4o", result.getModelUsed());
            assertEquals(1, result.getModelAttempts());
            assertEquals(1, calls.size());
        } finally {
            EngineContextHolder.remove();
        }
    }

    @Test
    void fallsBackThroughMultipleModels() throws Exception {
        List<LlmReqBo> calls = new ArrayList<>();
        Map<Integer, String> fail = new LinkedHashMap<>();
        fail.put(1, "gpt 500");
        fail.put(2, "deepseek 500");
        ModelServiceClient scripted = fallbackScripted(calls, fail, "qwen answer");
        WorkflowMsgCallback cb = noopCallback();
        EngineContextHolder.EngineContext ctx = EngineContextHolder.initContext("flow-fb",
                "chat-" + UUID.randomUUID(), cb);
        try {
            LLMNodeHandler handler = new LLMNodeHandler(scripted);
            NodeRunResult result = handler.execute(new NodeState(
                    makeLlmNode(Map.of("modelId", 1, "domain", "gpt-4o", "template", "hi",
                            "fallbackModels", List.of(
                                    fallbackEntry("deepseek-chat", "http://fb1", "k2"),
                                    fallbackEntry("qwen-plus", "http://fb2", "k3"))),
                            null, null),
                    new WorkflowContextStore(), cb));
            assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
            assertEquals("qwen-plus", result.getModelUsed());
            assertEquals(3, result.getModelAttempts());
            assertEquals("qwen-plus", calls.get(2).getModel());
        } finally {
            EngineContextHolder.remove();
        }
    }

    @Test
    void allConfiguredModelsFailReturnsError() throws Exception {
        List<LlmReqBo> calls = new ArrayList<>();
        Map<Integer, String> fail = new LinkedHashMap<>();
        fail.put(1, "gpt 500");
        fail.put(2, "deepseek 500");
        ModelServiceClient scripted = fallbackScripted(calls, fail, "never");
        WorkflowMsgCallback cb = noopCallback();
        EngineContextHolder.EngineContext ctx = EngineContextHolder.initContext("flow-fb",
                "chat-" + UUID.randomUUID(), cb);
        try {
            LLMNodeHandler handler = new LLMNodeHandler(scripted);
            NodeRunResult result = handler.execute(new NodeState(
                    makeLlmNode(Map.of("modelId", 1, "domain", "gpt-4o", "template", "hi",
                            "fallbackModels", List.of(fallbackEntry("deepseek-chat", "http://fb", "k2"))),
                            null, null),
                    new WorkflowContextStore(), cb));
            assertNotEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
            assertNotNull(result.getError());
            assertEquals(ErrorCode.MODEL_INVOCATION_FAILED.getCode(), result.getError().getCode());
            assertEquals(2, calls.size());
        } finally {
            EngineContextHolder.remove();
        }
    }

    @Test
    void doesNotFallBackOnMissingTemplateConfigError() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        ModelServiceClient scripted = new ModelServiceClient() {
            @Override
            public LlmResVo chatCompletion(LlmReqBo req, LlmCallback cb) {
                callCount.incrementAndGet();
                return new LlmResVo(mock(Usage.class), "x", null);
            }
        };
        WorkflowMsgCallback cb = noopCallback();
        EngineContextHolder.EngineContext ctx = EngineContextHolder.initContext("flow-fb",
                "chat-" + UUID.randomUUID(), cb);
        try {
            LLMNodeHandler handler = new LLMNodeHandler(scripted);
            // missing template, but fallbackModels present: a config error must NOT trigger fallback
            NodeRunResult result = handler.execute(new NodeState(
                    makeLlmNode(Map.of("modelId", 1,
                            "fallbackModels", List.of(fallbackEntry("deepseek-chat", "http://fb", "k2"))),
                            null, null),
                    new WorkflowContextStore(), cb));
            assertNotEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
            assertEquals(0, callCount.get(), "config error should never invoke any model");
        } finally {
            EngineContextHolder.remove();
        }
    }

    @Test
    void bufferingCallbackReplaysChunksInOrderOnFlush() {
        List<ChatResponse> seen = new ArrayList<>();
        LlmCallback delegate = seen::add;
        LLMNodeHandler.BufferingLlmCallback buf = new LLMNodeHandler.BufferingLlmCallback();
        ChatResponse c1 = mock(ChatResponse.class);
        ChatResponse c2 = mock(ChatResponse.class);
        buf.onResponse(c1);
        buf.onResponse(c2);
        buf.flushTo(delegate);
        assertEquals(List.of(c1, c2), seen, "buffered chunks must replay in order to the real callback");
    }

    @Test
    void bufferingCallbackDiscardsChunksOnFailedAttempt() {
        List<ChatResponse> seen = new ArrayList<>();
        LlmCallback delegate = seen::add;
        LLMNodeHandler.BufferingLlmCallback buf = new LLMNodeHandler.BufferingLlmCallback();
        buf.onResponse(mock(ChatResponse.class));
        buf.discard();
        buf.flushTo(delegate);
        assertTrue(seen.isEmpty(), "chunks from a failed attempt must never reach the real callback");
    }
}
