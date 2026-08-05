package com.flowagent.engine.node.impl;

import com.flowagent.common.enums.NodeExecStatusEnum;
import com.flowagent.common.exception.ErrorCode;
import com.flowagent.common.exception.NodeCustomException;
import com.flowagent.engine.constants.NodeTypeEnum;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.engine.dsl.VariableTemplateRender;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.engine.integration.rag.EmbeddingClient;
import com.flowagent.engine.integration.rag.VectorStore;
import com.flowagent.engine.node.AbstractNodeHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Knowledge retrieval (RAG) node.
 *
 * <p>Embeds the incoming query, retrieves the top-K similar chunks from the vector store, and
 * emits a grounded {@code context} string (plus a {@code chunks} list) for a downstream LLM node
 * to consume via {@code {{knowledge::NNN.context}}}.
 *
 * <p>All vectors live in MySQL (JSON column) with in-JVM cosine search, so no new middleware is
 * introduced. An optional rerank blends the vector score with lexical overlap for a little extra
 * precision at zero extra cost.
 */
@Slf4j
@Component
public class KnowledgeRetrievalNodeHandler extends AbstractNodeHandler {

    private final EmbeddingClient embedder;
    private final VectorStore vectorStore;

    public KnowledgeRetrievalNodeHandler(EmbeddingClient embedder, VectorStore vectorStore) {
        this.embedder = embedder;
        this.vectorStore = vectorStore;
    }

    @Override
    public NodeTypeEnum getNodeType() {
        return NodeTypeEnum.KNOWLEDGE;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected NodeRunResult executeNode(NodeState nodeState, Map<String, Object> inputs) throws Exception {
        Node node = nodeState.node();
        Map<String, Object> nodeParam = node.getData().getNodeParam();
        if (nodeParam == null) {
            throw new NodeCustomException(ErrorCode.INVALID_NODE_CONFIGURATION,
                    "Missing nodeParam in knowledge node: " + node.getId());
        }

        String query = render(nodeParam.get("query"), inputs);
        if (query == null || query.isBlank()) {
            throw new NodeCustomException(ErrorCode.INVALID_NODE_CONFIGURATION,
                    "Empty query in knowledge node: " + node.getId());
        }

        String collection = nodeParam.get("collection") == null ? null : String.valueOf(nodeParam.get("collection"));
        int topK = parseTopK(nodeParam.get("topK"));
        boolean rerank = Boolean.parseBoolean(String.valueOf(nodeParam.getOrDefault("rerank", false)));

        float[] queryVector = embedder.embed(query);
        List<VectorStore.ScoredChunk> topChunks = vectorStore.retrieve(collection, queryVector, topK);
        if (rerank) {
            topChunks = rerank(query, topChunks);
        }

        String context = topChunks.stream()
                .map(VectorStore.ScoredChunk::text)
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");

        List<Map<String, Object>> chunks = new ArrayList<>();
        for (VectorStore.ScoredChunk c : topChunks) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("documentId", c.documentId());
            item.put("title", c.title());
            item.put("text", c.text());
            item.put("score", c.score());
            chunks.add(item);
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("context", context);
        outputs.put("chunks", chunks);

        NodeRunResult result = new NodeRunResult();
        result.setInputs(inputs);
        result.setOutputs(outputs);
        result.setRawOutput(context);
        result.setStatus(NodeExecStatusEnum.SUCCESS);
        return result;
    }

    private int parseTopK(Object raw) {
        if (raw == null) {
            return 3;
        }
        try {
            return Math.max(1, Integer.parseInt(String.valueOf(raw)));
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    private String render(Object template, Map<String, Object> inputs) {
        if (template == null) {
            return null;
        }
        return VariableTemplateRender.render(String.valueOf(template), inputs);
    }

    /**
     * Blend cosine score (0.7) with lexical Jaccard overlap between query and chunk (0.3).
     * Deterministic and dependency-free; improves precision when vector similarity is flat.
     */
    private List<VectorStore.ScoredChunk> rerank(String query, List<VectorStore.ScoredChunk> chunks) {
        Set<String> queryTokens = tokenSet(query);
        List<VectorStore.ScoredChunk> reranked = new ArrayList<>(chunks.size());
        for (VectorStore.ScoredChunk c : chunks) {
            Set<String> chunkTokens = tokenSet(c.text());
            double overlap = jaccard(queryTokens, chunkTokens);
            double blended = 0.7 * c.score() + 0.3 * overlap;
            reranked.add(new VectorStore.ScoredChunk(
                    c.collection(), c.documentId(), c.title(), c.chunkIndex(), c.text(), blended));
        }
        reranked.sort(VectorStore.byScoreDescending());
        return reranked;
    }

    private Set<String> tokenSet(String text) {
        Set<String> set = new LinkedHashSet<>();
        for (String token : text.toLowerCase().split("\\W+")) {
            if (!token.isEmpty()) {
                set.add(token);
            }
        }
        return set;
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0d;
        }
        int intersection = 0;
        for (String t : a) {
            if (b.contains(t)) {
                intersection++;
            }
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0d : (double) intersection / union;
    }
}
