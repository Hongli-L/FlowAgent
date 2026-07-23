package com.flowagent.common.enums;

import lombok.Getter;

@Getter
public enum NodeExecStatusEnum {
    SUCCESS(true, "success"),
    MANUAL_INTERRUPT(false, "success, requires human intervention"),
    ERR_RETRY(false, "failed, retryable"),
    ERR_INTERUPT(false, "interrupted"),
    ERR_CODE_MSG(false, "failed, preset error code and message, normal branch"),
    ERR_FAIL_CONDITION(false, "failed, fail branch");

    private final boolean success;
    private final String desc;

    NodeExecStatusEnum(boolean success, String desc) {
        this.success = success;
        this.desc = desc;
    }
}
