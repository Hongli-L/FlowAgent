package com.flowagent.engine.integration.rag;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory vector store. Used by unit tests and the zero-dependency local profile so the RAG
 * node can run without MySQL. Not a Spring bean; callers construct it directly.
 */
public class InMemoryVectorStore implements VectorStore {

    private final List<ChunkRecord> chunks = new CopyOnWriteArrayList<>();

    @Override
    public void upsert(ChunkRecord record) {
        chunks.add(record);
    }

    @Override
    public List<ScoredChunk> retrieve(String collection, float[] queryVector, int topK) {
        return chunks.stream()
                .filter(r -> collection == null || collection.isBlank() || collection.equals(r.collection()))
                .map(r -> new ScoredChunk(r.collection(), r.documentId(), r.title(), r.chunkIndex(),
                        r.text(), VectorStore.cosine(queryVector, r.embedding())))
                .sorted(VectorStore.byScoreDescending())
                .limit(topK)
                .toList();
    }
}
