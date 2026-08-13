package com.flowagent.engine.integration.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the VectorStore abstraction: cosine math and top-K retrieval against the
 * in-memory implementation (the same code path the MySQL store uses for in-JVM search).
 */
public class VectorStoreTest {

    @Test
    void cosineOfIdenticalVectorsIsOne() {
        float[] v = {1, 2, 3};
        assertEquals(1.0, VectorStore.cosine(v, v), 1e-9);
    }

    @Test
    void cosineOfOrthogonalVectorsIsZero() {
        assertEquals(0.0, VectorStore.cosine(new float[]{1, 0}, new float[]{0, 1}), 1e-9);
    }

    @Test
    void cosineOfOppositeVectorsIsMinusOne() {
        assertEquals(-1.0, VectorStore.cosine(new float[]{1, 2}, new float[]{-1, -2}), 1e-9);
    }

    @Test
    void retrieveReturnsTopKByScoreDescending() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.upsert(new VectorStore.ChunkRecord("c", "d1", "t1", 0, "alpha", new float[]{1, 0}));
        store.upsert(new VectorStore.ChunkRecord("c", "d2", "t2", 0, "beta", new float[]{0, 1}));
        store.upsert(new VectorStore.ChunkRecord("c", "d3", "t3", 0, "gamma", new float[]{1, 1}));
        List<VectorStore.ScoredChunk> top = store.retrieve("c", new float[]{1, 0}, 2);
        assertEquals(2, top.size());
        assertEquals("d1", top.get(0).documentId(), "most similar to (1,0)");
        assertEquals("d3", top.get(1).documentId(), "(1,1) is next most similar");
    }

    @Test
    void retrieveFiltersByCollection() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.upsert(new VectorStore.ChunkRecord("cA", "d1", "t", 0, "x", new float[]{1, 0}));
        store.upsert(new VectorStore.ChunkRecord("cB", "d2", "t", 0, "x", new float[]{1, 0}));
        List<VectorStore.ScoredChunk> res = store.retrieve("cA", new float[]{1, 0}, 10);
        assertEquals(1, res.size());
        assertEquals("cA", res.get(0).collection());
    }

    @Test
    void emptyQueryVectorScoresZero() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.upsert(new VectorStore.ChunkRecord("c", "d1", "t", 0, "x", new float[]{1, 0}));
        List<VectorStore.ScoredChunk> res = store.retrieve("c", new float[]{}, 5);
        assertEquals(1, res.size());
        assertEquals(0.0, res.get(0).score(), 1e-9);
    }
}
