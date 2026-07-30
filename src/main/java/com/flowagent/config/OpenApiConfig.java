package com.flowagent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata for the interactive API console (F0).
 * Surfaced at /swagger-ui.html; documents the workflow protocol and SSE execution endpoints.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI flowAgentOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("FlowAgent Workflow Engine API")
                        .description("Self-developed DAG workflow orchestration engine. " +
                                "Exposes workflow CRUD, SSE streaming execution, and execution history.")
                        .version("1.0.0"));
    }
}
