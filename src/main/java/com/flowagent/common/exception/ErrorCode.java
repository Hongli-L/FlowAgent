package com.flowagent.common.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {
    SUCCESS(0, "success"),
    PARAM_ERROR(460, "Parameter validation error"),
    INTERNAL_ERROR(500, "Internal server error"),

    NODE_RUN_ERROR(1001, "Node run error"),
    INVALID_NODE_CONFIGURATION(1003, "Invalid node configuration"),
    TIMEOUT_ERROR(1005, "Execution timeout"),
    INTERRUPTED_ERROR(1006, "Execution interrupted"),

    PROTOCOL_VALIDATION_ERROR(20100, "Protocol validation failed"),
    PROTOCOL_CREATE_ERROR(20102, "Protocol creation error"),
    PROTOCOL_DELETE_ERROR(20103, "Protocol deletion error"),
    PROTOCOL_UPDATE_ERROR(20104, "Protocol update failed"),

    FLOW_GET_ERROR(20209, "Workflow retrieval failed");

    private final int code;
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
