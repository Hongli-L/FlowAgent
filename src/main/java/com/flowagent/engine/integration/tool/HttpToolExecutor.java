package com.flowagent.engine.integration.tool;

import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OkHttp-based executor for external HTTP tool invocations.
 *
 * <p>Supports GET/POST/PUT/DELETE with a single connection/read/write timeout
 * sourced from {@code tool.timeout-seconds}. JSON is assumed for request/response
 * bodies; non-2xx responses are returned (not thrown) so the node can apply its
 * configured error strategy.
 */
@Slf4j
@Component
public class HttpToolExecutor {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String GET = "GET";
    private static final String DELETE = "DELETE";

    private final OkHttpClient httpClient;

    public HttpToolExecutor(@Value("${tool.timeout-seconds:10}") int timeoutSeconds) {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build();
    }

    public ToolInvocationResponse execute(ToolInvocationRequest request) throws Exception {
        String method = request.getMethod() == null || request.getMethod().isBlank()
                ? GET : request.getMethod().trim().toUpperCase();

        Request.Builder builder = new Request.Builder().url(request.getUrl());
        if (request.getHeaders() != null) {
            request.getHeaders().forEach(builder::addHeader);
        }

        RequestBody body = null;
        if (request.getBody() != null && !request.getBody().isEmpty()
                && !GET.equals(method) && !DELETE.equals(method)) {
            body = RequestBody.create(request.getBody(), JSON);
        }
        builder.method(method, body);

        log.info("HTTP tool call: {} {}", method, request.getUrl());
        try (Response response = httpClient.newCall(builder.build()).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            Map<String, String> respHeaders = extractHeaders(response);
            boolean ok = response.isSuccessful();
            if (log.isDebugEnabled()) {
                log.debug("HTTP tool response: status={}, bodyLength={}", response.code(), respBody.length());
            }
            return new ToolInvocationResponse(response.code(), respBody, respHeaders, ok);
        }
    }

    private Map<String, String> extractHeaders(Response response) {
        Map<String, String> headers = new HashMap<>();
        response.headers().toMultimap().forEach((k, v) -> headers.put(k, String.join(",", v)));
        return headers;
    }
}
