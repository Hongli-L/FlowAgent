package com.flowagent.engine.integration.model.bo;

import org.springframework.ai.chat.model.ChatResponse;

/**
 */
public interface LlmCallback {

    void onResponse(ChatResponse response);

}
