package com.flowagent.common.response;

import com.flowagent.common.exception.ErrorCode;
import lombok.Data;

import java.util.UUID;

@Data
public class ApiResponse<T> {
    private int code;
    private String message;
    private T data;
    private String traceId;

    public ApiResponse() {
    }

    public ApiResponse(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public ApiResponse(int code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMsg(), data);
    }

    public static <T> ApiResponse<T> success(T data, String traceId) {
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMsg(), data, traceId);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMsg());
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message, String traceId) {
        ApiResponse<T> response = new ApiResponse<>(errorCode.getCode(), message);
        response.setTraceId(traceId);
        return response;
    }

    public static <T> ApiResponse<T> fail(int code, String message, String traceId) {
        ApiResponse<T> response = new ApiResponse<>(code, message);
        response.setTraceId(traceId);
        return response;
    }

    public boolean isSuccess() {
        return code == ErrorCode.SUCCESS.getCode();
    }

    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
