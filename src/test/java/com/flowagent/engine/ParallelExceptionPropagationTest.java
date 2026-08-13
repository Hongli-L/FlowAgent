package com.flowagent.engine;

import com.flowagent.common.enums.NodeStatusEnum;
import com.flowagent.common.exception.ErrorCode;
import com.flowagent.common.exception.NodeCustomException;
import com.flowagent.engine.core.EngineProperties;
import com.flowagent.engine.WorkflowContextStore;
import com.flowagent.engine.constants.NodeTypeEnum;
import com.flowagent.engine.dag.GraphBuilder;
import com.flowagent.engine.dag.TopologyValidator;
import com.flowagent.engine.dsl.model.Edge;
import com.flowagent.engine.dsl.model.InputItem;
import com.flowagent.engine.dsl.model.InputSchema;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.engine.dsl.model.NodeData;
import com.flowagent.engine.dsl.model.NodeMeta;
import com.flowagent.engine.dsl.model.OutputItem;
import com.flowagent.engine.dsl.model.Value;
import com.flowagent.engine.dsl.model.WorkflowDSL;
import com.flowagent.engine.node.AbstractNodeHandler;
import com.flowagent.engine.node.FlowEventCallback;
import com.flowagent.engine.node.WorkflowNodeHandler;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.persistence.service.ExecutionHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exception-propagation tests for {@link ParallelWorkflowEngine}.
 *
 * Verifies the JUC concurrency contract that the resume highlights: when a node
 * fails on a worker thread, the failure is propagated back to the calling thread
 * (the {@code workflowFuture.get()} awaiter) instead of being swallowed by the
 * thread pool. This is exactly the "CompletableFuture 异常怎么传递给下游" interview question.
 *
 * Two real failure paths are exercised:
 *  1. A raw {@link RuntimeException} thrown by a node handler (e.g. network/parse failure).
 *  2. A node failure that flows through the fault-tolerance interrupt strategy
 *     (no retry config -> ERR_INTERUPT), proving the error-strategy and the
 *     propagation mechanism are coupled correctly.
 */
@ExtendWith(MockitoExtension.class)
class ParallelExceptionPropagationTest {

    private TopologyValidator topologyValidator;
    private GraphBuilder graphBuilder;

    @Mock
    private ExecutionHistoryService executionHistoryService;

    @BeforeEach
    void setUp() {
        topologyValidator = new TopologyValidator();
        graphBuilder = new GraphBuilder();
    }

    /**
     * Path 1: a node handler that throws a raw RuntimeException.
     *
     * Expectation: the exception is NOT swallowed by the executor. The caller
     * (execute()) receives it, and the root cause is our simulated failure.
     * The failing node was mid-execution (RUNNING) when it blew up.
     */
    @Test
    void rawNodeException_shouldPropagateToCallerThread() throws Exception {
        List<WorkflowNodeHandler> executors = List.of(
                new com.flowagent.engine.node.impl.StartNodeHandler(),
                new RawThrowingNodeHandler(),
                new com.flowagent.engine.node.impl.EndNodeHandler()
        );
        ParallelWorkflowEngine engine = new ParallelWorkflowEngine(
                executors, topologyValidator, graphBuilder, new EngineProperties(), executionHistoryService);

        WorkflowDSL dsl = linearDsl();
        WorkflowContextStore pool = new WorkflowContextStore();

        Exception thrown = assertThrows(Exception.class,
                () -> engine.execute(dsl, pool, Map.of("user_input", "Hello"), new NoopCallback()));

        // Unwrap the ExecutionException that CompletableFuture.get() wraps our cause in.
        Throwable root = thrown instanceof ExecutionException ? thrown.getCause() : thrown;
        assertEquals(SimulatedNodeFailureException.class, root.getClass(),
                "root cause must be the simulated node failure, not swallowed");
        assertEquals("simulated LLM call failure", root.getMessage());

        // The failing node was executing (RUNNING) when it failed.
        assertEquals(NodeStatusEnum.RUNNING, findNode(dsl, "llm::002").getStatus());
    }

    /**
     * Path 2: a node that fails through the fault-tolerance interrupt strategy.
     *
     * A handler extending AbstractNodeHandler throws; doExecute() catches it and
     * converts it to an ERR_INTERUPT result (no retry config). The engine marks
     * the node ERROR and throws NodeCustomException(INTERRUPTED_ERROR), which is
     * propagated to the caller with the correct error code preserved.
     */
    @Test
    void interruptStrategy_shouldMarkNodeErrorAndPropagateWithErrorCode() throws Exception {
        List<WorkflowNodeHandler> executors = List.of(
                new com.flowagent.engine.node.impl.StartNodeHandler(),
                new InterruptingNodeHandler(),
                new com.flowagent.engine.node.impl.EndNodeHandler()
        );
        ParallelWorkflowEngine engine = new ParallelWorkflowEngine(
                executors, topologyValidator, graphBuilder, new EngineProperties(), executionHistoryService);

        WorkflowDSL dsl = linearDsl();
        WorkflowContextStore pool = new WorkflowContextStore();

        Exception thrown = assertThrows(Exception.class,
                () -> engine.execute(dsl, pool, Map.of("user_input", "Hello"), new NoopCallback()));

        Throwable root = thrown instanceof ExecutionException ? thrown.getCause() : thrown;
        assertTrue(root instanceof NodeCustomException,
                "interrupt strategy should surface a typed NodeCustomException");
        NodeCustomException nce = (NodeCustomException) root;
        assertEquals(ErrorCode.INTERRUPTED_ERROR.getCode(), nce.getCode(),
                "error code must survive the CompletableFuture boundary");

        // The failing node is marked ERROR (not left in RUNNING/SUCCESS).
        assertEquals(NodeStatusEnum.ERROR, findNode(dsl, "llm::002").getStatus());
    }

    /**
     * Downstream isolation: when an upstream node fails and propagates, the
     * downstream (end) node must NOT be executed. This proves the exception
     * short-circuits the workflow rather than being silently absorbed by a
     * sibling thread, keeping partial results from leaking downstream.
     */
    @Test
    void upstreamFailure_shouldHaltDownstreamExecution() throws Exception {
        List<WorkflowNodeHandler> executors = List.of(
                new com.flowagent.engine.node.impl.StartNodeHandler(),
                new RawThrowingNodeHandler(),
                new com.flowagent.engine.node.impl.EndNodeHandler()
        );
        ParallelWorkflowEngine engine = new ParallelWorkflowEngine(
                executors, topologyValidator, graphBuilder, new EngineProperties(), executionHistoryService);

        WorkflowDSL dsl = linearDsl();
        WorkflowContextStore pool = new WorkflowContextStore();

        assertThrows(Exception.class,
                () -> engine.execute(dsl, pool, Map.of("user_input", "Hello"), new NoopCallback()));

        // End node was never triggered by a failed upstream node (still in INIT state).
        assertEquals(NodeStatusEnum.INIT, findNode(dsl, "node-end::003").getStatus(),
                "downstream node must not run after an upstream failure");
        assertEquals(NodeStatusEnum.RUNNING, findNode(dsl, "llm::002").getStatus());
    }

    // =====================================================================
    // Simulated failure types
    // =====================================================================

    /** Raw runtime exception thrown directly by a handler (bypasses AbstractNodeHandler). */
    static class SimulatedNodeFailureException extends RuntimeException {
        SimulatedNodeFailureException(String message) {
            super(message);
        }
    }

    /** Handler that immediately throws a raw exception. */
    static class RawThrowingNodeHandler implements WorkflowNodeHandler {
        @Override
        public NodeTypeEnum getNodeType() {
            return NodeTypeEnum.LLM;
        }

        @Override
        public NodeRunResult execute(NodeState nodeState) {
            throw new SimulatedNodeFailureException("simulated LLM call failure");
        }
    }

    /** Handler that throws inside executeNode; AbstractNodeHandler wraps it as ERR_INTERUPT. */
    static class InterruptingNodeHandler extends AbstractNodeHandler {
        @Override
        public NodeTypeEnum getNodeType() {
            return NodeTypeEnum.LLM;
        }

        @Override
        protected NodeRunResult executeNode(NodeState nodeState, Map<String, Object> inputs) {
            throw new SimulatedNodeFailureException("simulated failure routed to interrupt strategy");
        }
    }

    /** Minimal callback that records nothing (engine only needs callback/finished). */
    static class NoopCallback implements FlowEventCallback {
        @Override
        public void callback(String eventType, Object data) {
            // no-op
        }
    }

    // =====================================================================
    // DSL fixtures (linear: start -> llm -> end)
    // =====================================================================

    private static WorkflowDSL linearDsl() {
        WorkflowDSL dsl = new WorkflowDSL();
        dsl.setFlowId("exception-propagation-test");
        dsl.setUuid("exception-propagation-test-uuid");

        Node startNode = node("node-start::001", "Start");
        startNode.getData().setNodeParam(new java.util.HashMap<>());

        Node llmNode = node("llm::002", "LLM");
        llmNode.getData().setInputs(List.of(refInput("in-1", "user_input", "node-start::001", "user_input")));
        java.util.Map<String, Object> llmParam = new java.util.HashMap<>();
        llmParam.put("template", "Please answer: {{user_input}}");
        llmParam.put("domain", "test-model");
        llmNode.getData().setNodeParam(llmParam);
        llmNode.getData().setOutputs(List.of(output("out-1", "output")));

        Node endNode = node("node-end::003", "End");
        endNode.getData().setInputs(List.of(refInput("in-2", "llm_output", "llm::002", "output")));
        java.util.Map<String, Object> endParam = new java.util.HashMap<>();
        endParam.put("outputMode", 0); // DIRECT_MODE
        endNode.getData().setNodeParam(endParam);

        dsl.setNodes(List.of(startNode, llmNode, endNode));
        dsl.setEdges(List.of(
                edge("node-start::001", "llm::002"),
                edge("llm::002", "node-end::003")
        ));
        return dsl;
    }

    private static Node node(String id, String nodeTypeValue) {
        Node node = new Node();
        node.setId(id);
        NodeData data = new NodeData();
        NodeMeta meta = new NodeMeta();
        meta.setNodeType(nodeTypeValue);
        data.setNodeMeta(meta);
        node.setData(data);
        return node;
    }

    private static InputItem refInput(String id, String name, String refNodeId, String refName) {
        InputItem item = new InputItem();
        item.setId(id);
        item.setName(name);
        InputSchema schema = new InputSchema();
        Value value = new Value();
        value.setType("ref");
        value.setContent(Map.of("nodeId", refNodeId, "name", refName));
        schema.setValue(value);
        item.setSchema(schema);
        return item;
    }

    private static OutputItem output(String id, String name) {
        OutputItem item = new OutputItem();
        item.setId(id);
        item.setName(name);
        return item;
    }

    private static Edge edge(String source, String target) {
        Edge edge = new Edge();
        edge.setSourceNodeId(source);
        edge.setTargetNodeId(target);
        return edge;
    }

    private static Node findNode(WorkflowDSL dsl, String id) {
        return dsl.getNodes().stream()
                .filter(n -> n.getId().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
