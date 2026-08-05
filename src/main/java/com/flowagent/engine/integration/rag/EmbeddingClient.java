package com.flowagent.engine.integration.rag;

/**
 * Embedding client abstraction. Implementations turn text into a fixed-length float vector.
 *
 * <p>The production implementation talks to an OpenAI-compatible embeddings endpoint so the
 * underlying provider (OpenAI, Qwen, a local model server, ...) is selected purely by
 * configuration and never hard-coded. Tests inject a deterministic fake.
 */
public interface EmbeddingClient {

    float[] embed(String text);
}
