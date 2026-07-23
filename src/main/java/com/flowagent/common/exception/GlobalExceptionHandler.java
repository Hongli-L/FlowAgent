package com.flowagent.common.exception;

import com.flowagent.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NodeCustomException.class)
    public ApiResponse<Void> handleNodeCustomException(NodeCustomException e) {
        log.warn("Business exception: code={}, message={}", e.getCode(), e.getMessage());
        return ApiResponse.fail(e.getCode(), e.getMessage(), ApiResponse.generateTraceId());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ApiResponse<Void> handleValidationException(Exception e) {
        String message = ErrorCode.PARAM_ERROR.getMsg();
        if (e instanceof MethodArgumentNotValidException ex) {
            message = ex.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse(message);
        } else if (e instanceof BindException ex) {
            message = ex.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse(message);
        }
        return ApiResponse.fail(ErrorCode.PARAM_ERROR, message, ApiResponse.generateTraceId());
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ApiResponse<Void> handleBadRequest(Exception e) {
        log.warn("Bad request: {}", e.getMessage());
        return ApiResponse.fail(ErrorCode.PARAM_ERROR, e.getMessage(), ApiResponse.generateTraceId());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("Unhandled exception", e);
        return ApiResponse.fail(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMsg(), ApiResponse.generateTraceId());
    }
}
