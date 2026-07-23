package com.flowagent.engine.integration.model.bo;

import com.flowagent.engine.integration.model.LlmChatHistory;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * LLM request information
 *
 */
@Data
public class LlmReqBo {
    private String nodeId;

    /**
     * Modelid
     */
    private String modelId;

    /**
     * User input
     */
    private String userMsg;

    /**
     * System prompt
     */
    private String systemMsg;

    private String model;

    private String url;

    private String apiKey;

    private String apiSecret;

    private String source;


    private Integer topK;

    private Integer maxTokens;

    private Boolean isThink;

    private Boolean multiMode;

    private Boolean modelEnabled;

    private Map<String, Object> extraParams;

    private List<LlmChatHistory.ChatItem> history;
}
