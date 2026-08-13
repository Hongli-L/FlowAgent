package com.flowagent.engine.integration.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused unit tests for {@link KnowledgeIngestionService} in isolation: split -> embed -> store.
 * Uses a deterministic fake embedder and the in-memory vector store so the assertions are fully
 * reproducible without a database or a network call. (The end-to-end retrieval path is covered by
 * RagTest; here we assert the ingestion contract itself.)
 */
public class KnowledgeIngestionServiceTest {

    /** Length-based deterministic vector so different chunks still produce distinguishable scores. */
    static class LengthEmbeddingClient implements EmbeddingClient {
        @Override
        public float[] embed(String text) {
            float[] v = new float[4];
            v[0] = text.length();
            v[1] = 1.0f;
            return v;
        }
    }

    private KnowledgeIngestionService service(Chunker chunker, VectorStore store) {
        return new KnowledgeIngestionService(chunker, new LengthEmbeddingClient(), store);
    }

    @Test
    void ingestReturnsNonNullDocumentId() {
        String id = service(new Chunker(), new InMemoryVectorStore()).ingest("c", "t", "hello world");
        assertNotNull(id);
        assertFalse(id.isBlank());
    }

    @Test
    void shortTextProducesSingleChunkAndVector() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        service(new Chunker(), store).ingest("c", "t", "short");
        List<VectorStore.ScoredChunk> r = store.retrieve("c", new float[]{1, 1, 0, 0}, 10);
        assertEquals(1, r.size());
    }

    @Test
    void longTextProducesMultipleChunksAndVectors() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        service(new Chunker(10, 2), store).ingest("c", "t", "abcdefghijklmnopqrstuvwxyz0123456789");
        List<VectorStore.ScoredChunk> r = store.retrieve("c", new float[]{1, 1, 0, 0}, 50);
        assertTrue(r.size() >= 3, "one vector should be stored per chunk");
    }

    @Test
    void allChunksShareSameDocumentId() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        String docId = service(new Chunker(10, 2), store).ingest("c", "t", "abcdefghijklmnopqrstuvwxyz");
        List<VectorStore.ScoredChunk> r = store.retrieve("c", new float[]{1, 1, 0, 0}, 50);
        for (VectorStore.ScoredChunk sc : r) {
            assertEquals(docId, sc.documentId());
        }
    }

    @Test
    void retrieveIsScopedToCollection() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        KnowledgeIngestionService svc = service(new Chunker(), store);
        svc.ingest("cA", "t", "alpha content here");
        svc.ingest("cB", "t", "beta content here");
        List<VectorStore.ScoredChunk> r = store.retrieve("cA", new float[]{1, 1, 0, 0}, 10);
        assertEquals(1, r.size());
        assertEquals("cA", r.get(0).collection());
    }

    @Test
    void blankContentIngestsNothing() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        String docId = service(new Chunker(), store).ingest("c", "t", "   ");
        assertNotNull(docId);
        assertEquals(0, store.retrieve("c", new float[]{1, 1, 0, 0}, 10).size());
    }
}
