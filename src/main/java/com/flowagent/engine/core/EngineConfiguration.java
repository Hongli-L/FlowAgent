package com.flowagent.engine.core;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EngineProperties.class)
public class EngineConfiguration {
}
