package com.flowagent.engine.constants;

public enum NodeTypeEnum {
    START("node-start"),
    END("node-end"),
    LLM("llm"),
    IF_ELSE("if-else"),
    CONDITION_SWITCH("condition-switch"),
    TOOL("tool"),
    ;

    private final String value;

    NodeTypeEnum(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static NodeTypeEnum fromValue(String value) {
        for (NodeTypeEnum type : NodeTypeEnum.values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return value;
    }
}
