package com.flowagent.engine.integration.model;

/**
 * A single model endpoint: the model identifier plus the credentials needed to call it.
 *
 * <p>In PaiFlow each LLM node carries its own {@code domain}/{@code url}/{@code apiKey} in the
 * DSL nodeParam, so a fallback target is simply another {@link ModelEndpoint}. This keeps the
 * engine free of a global model registry while still allowing per-node multi-model fallback.</p>
 *
 * @param domain  model name passed to the OpenAI-style chat API (e.g. "gpt-4o", "deepseek-chat")
 * @param url     base URL of the model service
 * @param apiKey  API key for the model service
 */
public record ModelEndpoint(String domain, String url, String apiKey) {
}
