package com.flowagent.engine.integration.rag;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates document ingestion for the RAG pipeline: split -> embed -> store.
 *
 * <p>Kept separate from the retrieval node so a future controller / CLI can ingest documents
 * without pulling in retrieval logic. The store owns persistence, so this service only wires
 * the chunker, embedder and store together.
 */
@Service
public class KnowledgeIngestionService {

    private final Chunker chunker;
    private final EmbeddingClient embedder;
    private final VectorStore vectorStore;

    public KnowledgeIngestionService(Chunker chunker, EmbeddingClient embedder, VectorStore vectorStore) {
        this.chunker = chunker;
        this.embedder = embedder;
        this.vectorStore = vectorStore;
    }

    /**
     * Ingest a document into a collection. Returns the generated document id.
     */
    public String ingest(String collection, String title, String content) {
        String documentId = UUID.randomUUID().toString();
        List<String> chunks = chunker.chunk(content);
        for (int i = 0; i < chunks.size(); i++) {
            vectorStore.upsert(new VectorStore.ChunkRecord(
                    collection, documentId, title, i, chunks.get(i), embedder.embed(chunks.get(i))));
        }
        return documentId;
    }
}
