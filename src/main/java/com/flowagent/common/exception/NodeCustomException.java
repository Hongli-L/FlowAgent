package com.flowagent.common.exception;

public class NodeCustomException extends RuntimeException {
    private final int code;
    private final String message;

    public NodeCustomException(int code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public NodeCustomException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.code = errorCode.getCode();
        this.message = errorCode.getMsg();
    }

    public NodeCustomException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
