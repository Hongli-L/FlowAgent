package com.flowagent.engine.node.impl;

import com.flowagent.common.enums.EndNodeOutputModeEnum;
import com.flowagent.common.enums.NodeExecStatusEnum;
import com.flowagent.engine.WorkflowContextStore;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.engine.domain.callbacks.ChatCallBackStreamResult;
import com.flowagent.engine.domain.callbacks.LLMGenerate;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.engine.dsl.model.NodeData;
import com.flowagent.engine.dsl.model.NodeMeta;
import com.flowagent.engine.dsl.model.OutputItem;
import com.flowagent.engine.integration.tool.HttpToolExecutor;
import com.flowagent.engine.node.FlowEventCallback;
import com.flowagent.engine.node.callback.WorkflowMsgCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolNodeHandler: external HTTP invocation with template rendering,
 * success output mapping, and non-2xx error routing. Uses the JDK built-in HttpServer
 * (no extra test dependency) so no real network calls leave the process.
 */
public class ToolNodeHandlerTest {

    private HttpToolExecutor executor;
    private ToolNodeHandler handler;

    @BeforeEach
    void setUp() {
        executor = new HttpToolExecutor(5);
        handler = new ToolNodeHandler(executor);
    }

    /**
     * Start a throwaway HTTP server on an ephemeral port that answers {@code path}
     * with the given status code and body.
     */
    private HttpServer startServer(String path, int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();
        return server;
    }

    private String baseUrl(HttpServer server, String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    private WorkflowMsgCallback noopCallback() {
        FlowEventCallback clientCallback = (eventType, data) -> { };
        BlockingQueue<LLMGenerate> streamQueue = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<ChatCallBackStreamResult> orderQueue = new LinkedBlockingQueue<>();
        return new WorkflowMsgCallback("test-sid", clientCallback, EndNodeOutputModeEnum.DIRECT_MODE,
                streamQueue, orderQueue);
    }

    @SuppressWarnings("unchecked")
    private Node makeToolNode(Map<String, Object> toolConfig) {
        Node node = new Node();
        node.setId("tool::001");
        node.setExecutedCount(new AtomicInteger(0));
        NodeData data = new NodeData();
        NodeMeta meta = new NodeMeta();
        meta.setAliasName("test-tool");
        data.setNodeMeta(meta);
        data.setNodeParam(Map.of("toolConfig", toolConfig));
        node.setData(data);
        return node;
    }

    private NodeRunResult run(Map<String, Object> toolConfig, WorkflowContextStore pool) {
        return handler.execute(new NodeState(makeToolNode(toolConfig), pool, noopCallback()));
    }

    @Test
    void successfulPostStoresStatusCodeAndBody() throws Exception {
        HttpServer server = startServer("/solve", 200, "{\"answer\":42}");
        try {
            Map<String, Object> toolConfig = Map.of(
                    "url", baseUrl(server, "/solve"),
                    "method", "POST",
                    "headers", Map.of("Content-Type", "application/json"),
                    "body", "{\"q\":\"{{node-start::001.q}}\"}"
            );
            WorkflowContextStore pool = new WorkflowContextStore();
            pool.set("node-start::001", "q", "life");

            NodeRunResult result = run(toolConfig, pool);
            assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
            assertEquals(200, result.getOutputs().get("statusCode"));
            assertEquals("{\"answer\":42}", result.getOutputs().get("body"));
            // Body also exposed to downstream nodes via the context store.
            assertEquals("{\"answer\":42}", pool.get("tool::001", "body"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getRequestWithoutBodySucceeds() throws Exception {
        HttpServer server = startServer("/ping", 200, "ok");
        try {
            Map<String, Object> toolConfig = Map.of(
                    "url", baseUrl(server, "/ping"),
                    "method", "GET"
            );
            NodeRunResult result = run(toolConfig, new WorkflowContextStore());
            assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
            assertEquals("ok", result.getOutputs().get("body"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void declaredOutputNameReceivesBody() throws Exception {
        HttpServer server = startServer("/ping", 200, "pong");
        try {
            Map<String, Object> toolConfig = Map.of(
                    "url", baseUrl(server, "/ping"),
                    "method", "GET"
            );
            Node node = makeToolNode(toolConfig);
            NodeData data = node.getData();
            OutputItem out = new OutputItem();
            out.setId("reply");
            out.setName("reply");
            data.setOutputs(List.of(out));

            NodeRunResult result = handler.execute(new NodeState(node, new WorkflowContextStore(), noopCallback()));
            assertEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
            assertEquals("pong", result.getOutputs().get("reply"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void non2xxResponseRoutesToErrorBranch() throws Exception {
        HttpServer server = startServer("/fail", 500, "boom");
        try {
            Map<String, Object> toolConfig = Map.of(
                    "url", baseUrl(server, "/fail"),
                    "method", "POST",
                    "body", "{}"
            );
            // No retry config -> framework default is interrupt on error.
            WorkflowContextStore pool = new WorkflowContextStore();
            NodeRunResult result = run(toolConfig, pool);
            assertNotEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
            assertNotNull(result.getError());
            // Response body still preserved in the variable pool for inspection by fail-branch nodes.
            assertEquals("boom", pool.get("tool::001", "body"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingToolConfigThrows() {
        Node node = new Node();
        node.setId("tool::001");
        node.setExecutedCount(new AtomicInteger(0));
        NodeData data = new NodeData();
        NodeMeta meta = new NodeMeta();
        meta.setAliasName("test-tool");
        data.setNodeMeta(meta);
        data.setNodeParam(Map.of());
        node.setData(data);

        NodeRunResult result = handler.execute(new NodeState(node, new WorkflowContextStore(), noopCallback()));
        assertNotEquals(NodeExecStatusEnum.SUCCESS, result.getStatus());
        assertNotNull(result.getError());
    }
}
