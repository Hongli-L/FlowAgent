package com.flowagent.engine.integration.rag;

import java.util.Comparator;
import java.util.List;

/**
 * Vector store abstraction over knowledge chunks.
 *
 * <p>The MySQL implementation persists chunks (with their embedding as a JSON column) and runs
 * an in-JVM cosine search; a lightweight in-memory implementation backs unit tests and the
 * local profile. Both avoid any new middleware so the infrastructure stays at MySQL + Redis.
 */
public interface VectorStore {

    /**
     * Store a single chunk (auto-creating its parent document row in the MySQL implementation).
     */
    void upsert(ChunkRecord record);

    /**
     * Return the top-{@code topK} chunks for a query vector, optionally scoped to a collection.
     */
    List<ScoredChunk> retrieve(String collection, float[] queryVector, int topK);

    /**
     * A chunk together with the document it belongs to, ready to be embedded.
     */
    record ChunkRecord(String collection, String documentId, String title, int chunkIndex, String text, float[] embedding) {
    }

    /**
     * A retrieved chunk with its similarity score (higher is better).
     */
    record ScoredChunk(String collection, String documentId, String title, int chunkIndex, String text, double score) {
    }

    /**
     * Cosine similarity between two vectors. Returns 0 when either vector is empty.
     */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return 0d;
        }
        int dim = Math.min(a.length, b.length);
        double dot = 0d;
        double na = 0d;
        double nb = 0d;
        for (int i = 0; i < dim; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        return denom == 0d ? 0d : dot / denom;
    }

    static Comparator<ScoredChunk> byScoreDescending() {
        return Comparator.comparingDouble(ScoredChunk::score).reversed();
    }
}
