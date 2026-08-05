package com.flowagent.engine.integration.rag;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.flowagent.persistence.entity.KnowledgeChunkEntity;
import com.flowagent.persistence.entity.KnowledgeDocumentEntity;
import com.flowagent.persistence.mapper.KnowledgeChunkMapper;
import com.flowagent.persistence.mapper.KnowledgeDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MySQL-backed vector store (2.15 lightweight RAG).
 *
 * <p>Chunks are persisted with their embedding as a JSON column; retrieval loads the candidate
 * set and scores it in the JVM with cosine similarity. For campus-scale knowledge bases (hundreds
 * to low thousands of chunks) this is plenty and adds zero new middleware. Swap to Redis Stack
 * (option B in the plan) only if the corpus grows large.
 */
@Slf4j
@Component
public class MysqlVectorStore implements VectorStore {

    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeDocumentMapper documentMapper;

    public MysqlVectorStore(KnowledgeChunkMapper chunkMapper, KnowledgeDocumentMapper documentMapper) {
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
    }

    @Override
    public void upsert(ChunkRecord record) {
        if (documentMapper.selectById(record.documentId()) == null) {
            KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
            doc.setId(record.documentId());
            doc.setCollection(record.collection());
            doc.setTitle(record.title());
            doc.setContent(null);
            doc.setCreatedAt(LocalDateTime.now());
            doc.setDeleted(0);
            documentMapper.insert(doc);
        }

        KnowledgeChunkEntity chunk = new KnowledgeChunkEntity();
        chunk.setDocumentId(record.documentId());
        chunk.setCollection(record.collection());
        chunk.setTitle(record.title());
        chunk.setChunkIndex(record.chunkIndex());
        chunk.setContent(record.text());
        chunk.setEmbeddingJson(JSON.toJSONString(record.embedding()));
        chunk.setCreatedAt(LocalDateTime.now());
        chunk.setDeleted(0);
        chunkMapper.insert(chunk);
    }

    @Override
    public List<ScoredChunk> retrieve(String collection, float[] queryVector, int topK) {
        QueryWrapper<KnowledgeChunkEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted", 0);
        if (collection != null && !collection.isBlank()) {
            wrapper.eq("collection", collection);
        }
        List<KnowledgeChunkEntity> candidates = chunkMapper.selectList(wrapper);

        return candidates.stream()
                .map(e -> new ScoredChunk(
                        e.getCollection(),
                        e.getDocumentId(),
                        e.getTitle(),
                        e.getChunkIndex(),
                        e.getContent(),
                        VectorStore.cosine(queryVector, parseEmbedding(e.getEmbeddingJson()))))
                .sorted(VectorStore.byScoreDescending())
                .limit(topK)
                .toList();
    }

    private float[] parseEmbedding(String json) {
        if (json == null || json.isBlank()) {
            return new float[0];
        }
        List<Float> list = JSON.parseArray(json, Float.class);
        float[] out = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i);
        }
        return out;
    }
}
