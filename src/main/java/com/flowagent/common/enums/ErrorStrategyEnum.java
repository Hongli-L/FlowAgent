package com.flowagent.common.enums;

import lombok.Getter;

@Getter
public enum ErrorStrategyEnum {
    INTERUPT(0, "interrupt"),
    ERR_CODE(1, "error_code"),
    ERR_CONDITION(2, "error_condition");

    private final int code;
    private final String msg;

    ErrorStrategyEnum(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public static ErrorStrategyEnum fromCode(int code) {
        for (ErrorStrategyEnum value : ErrorStrategyEnum.values()) {
            if (value.code == code) {
                return value;
            }
        }
        return null;
    }
}
