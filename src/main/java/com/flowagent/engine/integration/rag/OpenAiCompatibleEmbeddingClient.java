package com.flowagent.engine.integration.rag;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * OpenAI-compatible embeddings client.
 *
 * <p>Calls {@code {url}/embeddings} with {@code {input, model}} and returns the first vector.
 * The base URL and model are configuration-only (no vendor SDK), so any OpenAI-compatible
 * service works by setting {@code paiflow.embedding.url/api-key/model}.
 */
@Slf4j
@Component
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private static final MediaType JSON_TYPE = MediaType.get("application/json");

    private final OkHttpClient httpClient = new OkHttpClient();

    @Value("${paiflow.embedding.url:}")
    private String url;

    @Value("${paiflow.embedding.api-key:}")
    private String apiKey;

    @Value("${paiflow.embedding.model:text-embedding-3-small}")
    private String model;

    @Override
    public float[] embed(String text) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("paiflow.embedding.url is not configured; cannot call the embedding endpoint");
        }
        String endpoint = url.endsWith("/") ? url + "embeddings" : url + "/embeddings";

        RequestBody body = RequestBody.create(JSON_TYPE,
                JSON.toJSONString(Map.of("input", text, "model", model)));
        Request request = new Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new RuntimeException("Embedding request failed with HTTP " + response.code());
            }
            JSONObject json = JSON.parseObject(response.body().string());
            JSONArray data = json.getJSONArray("data");
            if (data == null || data.isEmpty()) {
                throw new RuntimeException("Embedding response contained no data");
            }
            JSONArray vector = data.getJSONObject(0).getJSONArray("embedding");
            float[] out = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                out[i] = vector.getFloatValue(i);
            }
            return out;
        } catch (IOException e) {
            throw new RuntimeException("Embedding request IO error", e);
        }
    }
}
