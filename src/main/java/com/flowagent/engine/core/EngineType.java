package com.flowagent.engine.core;

/**
 * Top-level engine adapter type selected by {@link EngineFactory}.
 */
public enum EngineType {
    LEGACY,
    LANGGRAPH;

    public static EngineType from(String value) {
        if (value == null || value.isBlank()) {
            return LEGACY;
        }
        return EngineType.valueOf(value.trim().toUpperCase());
    }
}
