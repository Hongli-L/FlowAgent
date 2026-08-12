package com.flowagent.engine.node.impl.llm;

import com.alibaba.fastjson2.JSONObject;
import com.flowagent.common.enums.MsgTypeEnum;
import com.flowagent.common.enums.NodeExecStatusEnum;
import com.flowagent.common.exception.ErrorCode;
import com.flowagent.common.exception.ModelInvocationException;
import com.flowagent.common.exception.NodeCustomException;
import com.flowagent.engine.constants.NodeTypeEnum;
import com.flowagent.engine.context.EngineContextHolder;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.engine.domain.callbacks.GenerateUsage;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.engine.dsl.model.OutputItem;
import com.flowagent.engine.integration.model.LlmChatHistory;
import com.flowagent.engine.integration.model.ModelEndpoint;
import com.flowagent.engine.integration.model.ModelFallbackPolicy;
import com.flowagent.engine.integration.model.ModelServiceClient;
import com.flowagent.engine.integration.model.bo.LlmCallback;
import com.flowagent.engine.integration.model.bo.LlmReqBo;
import com.flowagent.engine.integration.model.bo.LlmResVo;
import com.flowagent.engine.node.AbstractNodeHandler;
import com.flowagent.engine.dsl.VariableTemplateRender;
import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * LLM node executor
 * Calls the model service to execute LLM inference
 */
@Slf4j
@Component
public class LLMNodeHandler extends AbstractNodeHandler {

    private final ModelServiceClient modelServiceClient;

    public LLMNodeHandler(ModelServiceClient modelServiceClient) {
        this.modelServiceClient = modelServiceClient;
    }

    @Override
    public NodeTypeEnum getNodeType() {
        return NodeTypeEnum.LLM;
    }

    @Override
    protected NodeRunResult executeNode(NodeState nodeState, Map<String, Object> inputs) throws Exception {
        Node node = nodeState.node();
        Map<String, Object> nodeParam = node.getData().getNodeParam();

        Integer modelId = getModelId(nodeParam);

        String resolvedPrompt = getPrompt(nodeParam, inputs);
        String systemPrompt = getSystemPrompt(nodeParam, inputs);
        List<LlmChatHistory.ChatItem> history = getChatHistory(nodeParam, node);
        log.info("LLM node: modelId={}, promptLength={}, history={}", modelId, resolvedPrompt.length(), history.size());
        if (log.isDebugEnabled()) {
            log.debug("LLM prompt (resolved): {}", resolvedPrompt);
        }

        String chatId = EngineContextHolder.get().getChatId();
        LlmChatHistory.newChat(chatId, node.getId());
        if (StringUtils.isNotBlank(systemPrompt)) {
            LlmChatHistory.addMessage(chatId, node.getId(), MsgTypeEnum.SYSTEM, systemPrompt);
        }
        LlmChatHistory.addMessage(chatId, node.getId(), MsgTypeEnum.USER, resolvedPrompt);

        // Model endpoints: primary first, then any configured fallbacks.
        List<ModelEndpoint> endpoints = resolveEndpoints(nodeParam);

        // The real streaming callback pushes tokens to the UI. During a fallback attempt we must
        // NOT emit partial tokens from a model that is about to fail, so we buffer chunks and only
        // flush them to the real callback once an attempt actually succeeds.
        LlmCallback realStreamCallback = chatResponse -> {
            if (!CollectionUtils.isEmpty(chatResponse.getResults())) {
                nodeState.callback().onNodeProcess(0, node.getId(), node.getData().getNodeMeta().getAliasName(),
                        chatResponse.getResult().getOutput().getText(),
                        (String) chatResponse.getResult().getOutput().getMetadata().get("reasoningContent"));
            }
        };

        ModelInvocationException lastFailure = null;
        for (int i = 0; i < endpoints.size(); i++) {
            ModelEndpoint endpoint = endpoints.get(i);
            BufferingLlmCallback buffer = new BufferingLlmCallback();
            try {
                LlmReqBo req = buildRequest(nodeParam, systemPrompt, resolvedPrompt, history, node, endpoint);
                LlmResVo llmOutput = modelServiceClient.chatCompletion(req, buffer);

                // Success on this endpoint: flush the buffered stream, persist, and return.
                buffer.flushTo(realStreamCallback);
                LlmChatHistory.addMessage(chatId, node.getId(), MsgTypeEnum.ASSISTANT, llmOutput.content());
                if (StringUtils.isNotBlank(llmOutput.thinkContent())) {
                    LlmChatHistory.addMessage(chatId, node.getId(), MsgTypeEnum.THINKING, llmOutput.thinkContent());
                }

                Map<String, Object> outputs = formatOutputs(llmOutput, node.getData().getOutputs());
                NodeRunResult result = new NodeRunResult();
                result.setInputs(inputs);
                result.setOutputs(outputs);
                result.setRawOutput(llmOutput.content());
                result.setTokenCost(formatUsage(llmOutput.usage()));
                result.setStatus(NodeExecStatusEnum.SUCCESS);
                result.setModelUsed(endpoint.domain());
                result.setModelAttempts(endpoints.size());
                if (endpoints.size() > 1) {
                    log.info("LLM node {} served by model '{}' (attempt {}/{}); {} fallback model(s) configured",
                            node.getId(), endpoint.domain(), i + 1, endpoints.size(), endpoints.size() - 1);
                }
                return result;
            } catch (ModelInvocationException ex) {
                lastFailure = ex;
                if (ModelFallbackPolicy.isFallbackEligible(ex) && i + 1 < endpoints.size()) {
                    log.warn("LLM node {} model '{}' failed (attempt {}/{}): {} - falling back to next model",
                            node.getId(), endpoint.domain(), i + 1, endpoints.size(), ex.getMessage());
                } else {
                    log.error("LLM node {} model '{}' failed (attempt {}/{}): {} - no fallback available",
                            node.getId(), endpoint.domain(), i + 1, endpoints.size(), ex.getMessage());
                }
                // buffer is discarded; partial tokens from this failed attempt are never shown
            }
        }

        String detail = lastFailure != null ? lastFailure.getMessage() : "unknown error";
        throw new NodeCustomException(ErrorCode.MODEL_INVOCATION_FAILED,
                "All " + endpoints.size() + " model(s) failed for node " + node.getId() + ". Last error: " + detail);
    }

    /**
     * Build the ordered list of model endpoints to try: the primary model (from the top-level
     * {@code domain}/{@code url}/{@code apiKey}) followed by any {@code fallbackModels}.
     */
    private List<ModelEndpoint> resolveEndpoints(Map<String, Object> nodeParam) {
        List<ModelEndpoint> endpoints = new ArrayList<>();
        endpoints.add(new ModelEndpoint(
                asString(nodeParam.get("domain")),
                asString(nodeParam.get("url")),
                asString(nodeParam.get("apiKey"))));

        Object fallbackObj = nodeParam.get("fallbackModels");
        if (fallbackObj instanceof List<?> fallbackList) {
            for (Object item : fallbackList) {
                if (item instanceof Map<?, ?> map) {
                    String domain = asString(map.get("domain"));
                    if (StringUtils.isBlank(domain)) {
                        continue; // skip fallback entries without a model name
                    }
                    endpoints.add(new ModelEndpoint(domain,
                            asString(map.get("url")), asString(map.get("apiKey"))));
                }
            }
        }
        return endpoints;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private LlmReqBo buildRequest(Map<String, Object> nodeParam, String systemPrompt, String resolvedPrompt,
                                  List<LlmChatHistory.ChatItem> history, Node node, ModelEndpoint endpoint) {
        LlmReqBo req = JSONObject.parseObject(JSONObject.toJSONString(nodeParam), LlmReqBo.class);
        req.setSystemMsg(systemPrompt);
        req.setUserMsg(resolvedPrompt);
        req.setHistory(history);
        req.setNodeId(node.getId());
        req.setModel(endpoint.domain());
        req.setUrl(endpoint.url());
        req.setApiKey(endpoint.apiKey());
        return req;
    }

    private Integer getModelId(Map<String, Object> nodeParam) {
        Object modelIdObj = nodeParam.get("modelId");
        if (modelIdObj instanceof Integer) {
            return (Integer) modelIdObj;
        } else if (modelIdObj instanceof String) {
            return Integer.parseInt((String) modelIdObj);
        } else if (modelIdObj instanceof Number) {
            return ((Number) modelIdObj).intValue();
        }
        throw new IllegalArgumentException("Invalid modelId in LLM node: " + modelIdObj);
    }

    /**
     * Resolve user prompt template
     *
     * @param nodeParam
     * @param input
     * @return
     */
    private String getPrompt(Map<String, Object> nodeParam, Map<String, Object> input) {
        String userTemplate = getParam(nodeParam, "template");
        if (userTemplate == null) {
            throw new IllegalArgumentException("Missing 'prompt' in LLM node parameters");
        }
        return VariableTemplateRender.render(userTemplate, input);
    }

    /**
     * Get system prompt
     *
     * @param nodeParam
     * @param input
     * @return
     */
    private String getSystemPrompt(Map<String, Object> nodeParam, Map<String, Object> input) {
        String sys = getParam(nodeParam, "systemTemplate");
        if (sys == null) {
            return null;
        }
        return VariableTemplateRender.render(sys, input);
    }

    /**
     * Parse request parameter
     *
     * @param nodeParam
     * @param key
     * @return
     */
    private String getParam(Map<String, Object> nodeParam, String key) {
        Object promptObj = nodeParam.get(key);
        if (promptObj == null) {
            return null;
        }
        return String.valueOf(promptObj);
    }

    private List<LlmChatHistory.ChatItem> getChatHistory(Map<String, Object> nodeParam, Node node) {
        int limit = historyLimit(nodeParam);
        if (limit == 0) {
            return List.of();
        }

        // Retrieve chat history
        return LlmChatHistory.getHistory(EngineContextHolder.get().getChatId(), node.getId(), limit);
    }

    private Integer historyLimit(Map<String, Object> nodeParam) {
        try {
            Map map = (Map) nodeParam.get("enableChatHistoryV2");
            if (map == null) {
                return 0;
            }
            if (Objects.equals(map.get("isEnabled") + "", "true")) {
                return (Integer) map.get("rounds");
            }
            return 0;
        } catch (Exception e) {
            log.error("parse enableChatHistoryV2 error", e);
            return 0;
        }
    }


    protected Map<String, Object> formatOutputs(LlmResVo llmRes, List<OutputItem> outItems) {
        Map<String, Object> outputs = new HashMap<>();
        if (CollectionUtils.isEmpty(outItems)) {
            outputs.put("output", llmRes.content());
        } else {
            // Currently handles text-only LLM output; structured and image outputs to be supported later
            OutputItem item = outItems.get(0);
            outputs.put(item.getName(), llmRes.content());

            // Set a reserved field for the reasoning process, where 'reason' represents the model's reasoning information
            outItems.stream().filter(s -> "reason".equalsIgnoreCase(s.getName())).findAny().ifPresent(s -> {
                outputs.put(s.getName(), llmRes.thinkContent());
            });
        }
        return outputs;
    }

    private GenerateUsage formatUsage(Usage usage) {
        GenerateUsage generateUsage = new GenerateUsage();
        generateUsage.setCompletionTokens(usage.getCompletionTokens());
        generateUsage.setPromptTokens(usage.getPromptTokens());
        generateUsage.setTotalTokens(usage.getTotalTokens());
        return generateUsage;
    }

    /**
     * Buffers streamed {@link ChatResponse} chunks for a single model attempt. Chunks are only
     * forwarded to the real callback via {@link #flushTo(LlmCallback)} when the attempt succeeds,
     * so a model that fails mid-stream never leaks partial tokens to the UI. {@link #discard()}
     * simply drops the buffered chunks.
     */
    static class BufferingLlmCallback implements LlmCallback {
        private final List<ChatResponse> chunks = new ArrayList<>();

        @Override
        public void onResponse(ChatResponse response) {
            chunks.add(response);
        }

        void flushTo(LlmCallback delegate) {
            chunks.forEach(delegate::onResponse);
        }

        void discard() {
            chunks.clear();
        }
    }
}
