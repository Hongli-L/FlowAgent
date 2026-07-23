package com.flowagent.common.enums;

public enum MsgTypeEnum {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    THINKING("thinking");

    private final String type;

    MsgTypeEnum(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }
}
