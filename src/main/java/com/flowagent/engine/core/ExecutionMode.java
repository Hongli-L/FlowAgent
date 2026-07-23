package com.flowagent.engine.core;

/**
 * Execution strategy inside {@link LegacyDagEngine}.
 */
public enum ExecutionMode {
    SEQUENTIAL,
    PARALLEL;

    public static ExecutionMode from(String value) {
        if (value == null || value.isBlank()) {
            return SEQUENTIAL;
        }
        return ExecutionMode.valueOf(value.trim().toUpperCase());
    }
}
