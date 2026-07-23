package com.flowagent.common.enums;

import lombok.Getter;

@Getter
public enum ChatStatusEnum {
    PING("ping"),
    RUNNING("running"),
    STOP("stop"),
    ERROR("interrupt");

    private final String status;

    ChatStatusEnum(String status) {
        this.status = status;
    }
}
