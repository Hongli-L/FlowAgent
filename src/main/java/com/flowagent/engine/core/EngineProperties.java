package com.flowagent.engine.core;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "workflow.engine")
public class EngineProperties {

    /**
     * Active engine adapter: LEGACY or LANGGRAPH.
     */
    private String type = EngineType.LEGACY.name();

    /**
     * Legacy engine mode: SEQUENTIAL or PARALLEL.
     */
    private String mode = ExecutionMode.SEQUENTIAL.name();

    private int nodeTimeout = 300;

    private int workflowTimeout = 600;

    // ---- Parallel scheduler thread pool ----

    /**
     * Core thread count for the parallel scheduler pool.
     * Defaults to CPU count * 2 (IO-intensive profile).
     */
    private int corePoolSize = Runtime.getRuntime().availableProcessors() * 2;

    /**
     * Maximum thread count for the parallel scheduler pool.
     */
    private int maxPoolSize = 50;

    /**
     * Queue capacity for the parallel scheduler pool.
     * Tasks exceeding this capacity trigger CallerRunsPolicy backpressure.
     */
    private int queueCapacity = 200;

    /**
     * Keep-alive seconds for idle threads beyond corePoolSize.
     */
    private int keepAliveSeconds = 60;

    // ---- LLM context window ----

    /**
     * Maximum token budget for LLM chat history.
     * Sliding-window strategy: when estimated tokens exceed this budget,
     * the oldest messages are discarded, retaining system prompt + recent N rounds.
     */
    private int maxContextTokens = 8192;

    public EngineType resolveType() {
        return EngineType.from(type);
    }

    public ExecutionMode resolveMode() {
        return ExecutionMode.from(mode);
    }
}
