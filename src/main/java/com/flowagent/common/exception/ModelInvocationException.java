package com.flowagent.common.exception;

import lombok.Getter;

/**
 * Typed exception for a single model invocation failure.
 *
 * <p>Unlike a plain {@link RuntimeException}, it carries the HTTP status (when the failure
 * originated from the model gateway) and a {@code recoverable} flag so that the multi-model
 * fallback policy can decide whether switching to the next model is worthwhile without parsing
 * exception message strings.</p>
 */
@Getter
public class ModelInvocationException extends RuntimeException {

    /**
     * HTTP status code if the failure came from an HTTP response, otherwise {@code null}
     * (e.g. network error, timeout, response parse failure).
     */
    private final Integer httpStatus;

    /**
     * Whether retrying on a different model endpoint is expected to help.
     * v1 marks every call failure as recoverable; a future model registry can set this to
     * {@code false} for permanent misconfigurations.
     */
    private final boolean recoverable;

    public ModelInvocationException(String message, Throwable cause, Integer httpStatus, boolean recoverable) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.recoverable = recoverable;
    }
}
