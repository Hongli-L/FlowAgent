package com.flowagent.engine.integration.tool;

import lombok.Data;

import java.util.Map;

/**
 * Response model for an external HTTP tool invocation.
 */
@Data
public class ToolInvocationResponse {

    /**
     * HTTP status code returned by the upstream service.
     */
    private int statusCode;

    /**
     * Response body as a string (usually JSON).
     */
    private String body;

    /**
     * Selected response headers for downstream inspection.
     */
    private Map<String, String> headers;

    /**
     * True when the status code is in the 2xx range.
     */
    private boolean success;

    public ToolInvocationResponse() {
    }

    public ToolInvocationResponse(int statusCode, String body, Map<String, String> headers, boolean success) {
        this.statusCode = statusCode;
        this.body = body;
        this.headers = headers;
        this.success = success;
    }
}
