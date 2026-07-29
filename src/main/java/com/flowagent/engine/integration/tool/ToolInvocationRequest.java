package com.flowagent.engine.integration.tool;

import lombok.Data;

import java.util.Map;

/**
 * Request model for an external HTTP tool invocation.
 *
 * <p>Urls, header values and body may contain {@code {{node.field}}} variable
 * placeholders that are rendered with upstream node outputs before the call.
 */
@Data
public class ToolInvocationRequest {

    /**
     * Fully-qualified target URL (may contain rendered variables).
     */
    private String url;

    /**
     * HTTP method: GET, POST, PUT, DELETE. Defaults to GET when null/blank.
     */
    private String method;

    /**
     * Request headers; values may contain rendered variables.
     */
    private Map<String, String> headers;

    /**
     * Request body (typically JSON); only sent for POST/PUT. May contain variables.
     */
    private String body;
}
