package com.flowagent.engine.integration.model.bo;

import org.springframework.ai.chat.metadata.Usage;

/**
 */
public record LlmResVo(Usage usage, String content, String thinkContent) {
}
