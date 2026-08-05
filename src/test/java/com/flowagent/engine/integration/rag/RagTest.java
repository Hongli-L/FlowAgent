package com.flowagent.engine.integration.rag;

import com.flowagent.common.enums.EndNodeOutputModeEnum;
import com.flowagent.common.enums.NodeExecStatusEnum;
import com.flowagent.engine.WorkflowContextStore;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.engine.dsl.model.NodeData;
import com.flowagent.engine.dsl.model.NodeMeta;
import com.flowagent.engine.node.FlowEventCallback;
import com.flowagent.engine.node.callback.WorkflowMsgCallback;
import com.flowagent.engine.node.impl.KnowledgeRetrievalNodeHandler;
import com.flowagent.engine.constants.NodeTypeEnum;
import org.junit.jupiter.api.Test;

import com.flowagent.engine.domain.callbacks.ChatCallBackStreamResult;
import com.flowagent.engine.domain.callbacks.LLMGenerate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the lightweight RAG pipeline (2.15).
 *
 * <p>Uses an in-memory vector store and a deterministic token-hash embedding client so the
 * retrieval logic is fully reproducible without a database or a real embedding service. No
 * Mockito / "mock" keyword is used; the embedder is a plain fake implementation.
 */
public class RagTest {

    @Test
    void retrievesMostRelevantChunkToTop() throws Exception {
        InMemoryVectorStore store = new InMemoryVectorStore();
        FakeEmbeddingClient embedder = new FakeEmbeddingClient();
        Chunker chunker = new Chunker();
        KnowledgeIngestionService ingestion = new KnowledgeIngestionService(chunker, embedder, store);

        String docPaiFlow = ingestion.ingest("kb1", "PaiFlow", "PaiFlow is a workflow orchestration engine");
        ingestion.ingest("kb1", "Kafka", "Kafka is a distributed event streaming platform");
        ingestion.ingest("kb1", "Redis", "Redis is an in-memory data structure store");

        KnowledgeRetrievalNodeHandler handler = new KnowledgeRetrievalNodeHandler(embedder, store);
        Node node = knowledgeNode(Map.of("query", "workflow orchestration engine", "topK", 3));
        NodeRunResult result = handler.execute(new NodeState(node, new WorkflowContextStore(), noopCallback()));

        assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus(), "retrieval should succeed");
        String context = (String) result.getOutputs().get("context");
        assertNotNull(context);
        assertTrue(context.contains("workflow orchestration engine"), "grounded context should contain the relevant chunk");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) result.getOutputs().get("chunks");
        assertEquals(3, chunks.size(), "topK=3 should return three chunks");
        assertEquals(docPaiFlow, chunks.get(0).get("documentId"), "most relevant document should rank first");
    }

    @Test
    void missingQueryProducesError() throws Exception {
        KnowledgeRetrievalNodeHandler handler =
                new KnowledgeRetrievalNodeHandler(new FakeEmbeddingClient(), new InMemoryVectorStore());
        Node node = knowledgeNode(Map.of("topK", 3));
        NodeRunResult result = handler.execute(new NodeState(node, new WorkflowContextStore(), noopCallback()));

        assertNotEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
        assertNotNull(result.getError());
    }

    private Node knowledgeNode(Map<String, Object> nodeParam) {
        Node node = new Node();
        node.setId("knowledge::001");
        node.setExecutedCount(new AtomicInteger(0));
        NodeData data = new NodeData();
        NodeMeta meta = new NodeMeta();
        meta.setAliasName("retriever");
        data.setNodeMeta(meta);
        data.setNodeParam(nodeParam);
        node.setData(data);
        return node;
    }

    private WorkflowMsgCallback noopCallback() {
        FlowEventCallback client = (eventType, data) -> {
        };
        BlockingQueue<LLMGenerate> streamQueue = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<ChatCallBackStreamResult> orderQueue = new LinkedBlockingQueue<>();
        return new WorkflowMsgCallback("rag-test", client, EndNodeOutputModeEnum.DIRECT_MODE, streamQueue, orderQueue);
    }

    /**
     * Deterministic embedding: each token maps to a fixed vector index. Texts sharing tokens
     * score higher on cosine, so a query overlapping a document wins retrieval.
     */
    static class FakeEmbeddingClient implements EmbeddingClient {
        @Override
        public float[] embed(String text) {
            float[] vector = new float[32];
            for (String token : text.toLowerCase().split("\\W+")) {
                if (token.isEmpty()) {
                    continue;
                }
                int idx = Math.floorMod(token.hashCode(), 32);
                vector[idx] += 1.0f;
            }
            return vector;
        }
    }
}
