package com.flowagent.engine.core;

import com.flowagent.engine.integration.model.LlmChatHistory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

@Configuration
@EnableConfigurationProperties(EngineProperties.class)
public class EngineConfiguration {

    private final EngineProperties engineProperties;

    public EngineConfiguration(EngineProperties engineProperties) {
        this.engineProperties = engineProperties;
    }

    @PostConstruct
    public void init() {
        // Wire token-budget config into LlmChatHistory static field
        LlmChatHistory.setMaxContextTokens(engineProperties.getMaxContextTokens());
    }
}
