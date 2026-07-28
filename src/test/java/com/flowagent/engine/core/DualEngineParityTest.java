package com.flowagent.engine.core;

import com.flowagent.common.enums.NodeExecStatusEnum;
import com.flowagent.common.enums.NodeStatusEnum;
import com.flowagent.engine.ParallelWorkflowEngine;
import com.flowagent.engine.core.EngineProperties;
import com.flowagent.engine.WorkflowContextStore;
import com.flowagent.engine.DagWorkflowEngine;
import com.flowagent.engine.constants.NodeTypeEnum;
import com.flowagent.engine.dag.GraphBuilder;
import com.flowagent.engine.dag.TopologyValidator;
import com.flowagent.engine.dsl.model.*;
import com.flowagent.engine.node.AbstractNodeHandler;
import com.flowagent.engine.node.WorkflowNodeHandler;
import com.flowagent.engine.node.FlowEventCallback;
import com.flowagent.engine.node.callback.WorkflowMsgCallback;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.persistence.service.ExecutionHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dual-engine parity test: verifies that LEGACY (sequential + parallel)
 * and LANGGRAPH engines produce equivalent outcomes for the same DSL.
 *
 * Uses deterministic stub executors to avoid external LLM calls,
 * while preserving the full AbstractNodeHandler pipeline (input resolution,
 * output storage, callback events) for realistic execution flow.
 */
@ExtendWith(MockitoExtension.class)
class DualEngineParityTest {

    private TopologyValidator topologyValidator;
    private GraphBuilder graphBuilder;
    private List<WorkflowNodeHandler> stubExecutors;

    @Mock
    private ExecutionHistoryService executionHistoryService;

    @BeforeEach
    void setUp() {
        topologyValidator = new TopologyValidator();
        graphBuilder = new GraphBuilder();
        stubExecutors = List.of(
                new com.flowagent.engine.node.impl.StartNodeHandler(),
                new DeterministicLLMNodeHandler(),
                new com.flowagent.engine.node.impl.EndNodeHandler()
        );
    }

    // =====================================================================
    // Test cases
    // =====================================================================

    /**
     * Simplest parity check: linear start → llm → end.
     * All three engine modes should produce identical WorkflowContextStore and node statuses.
     */
    @Test
    void linearWorkflow_allEnginesProduceSameWorkflowContextStoreAndNodeStatuses() throws Exception {
        WorkflowDSL dsl = createLinearDsl();
        Map<String, Object> inputs = Map.of("user_input", "Hello");

        // --- LEGACY sequential ---
        WorkflowContextStore seqPool = new WorkflowContextStore();
        CapturingFlowEventCallback seqCb = new CapturingFlowEventCallback();
        DagWorkflowEngine seqEngine = new DagWorkflowEngine(stubExecutors, topologyValidator, graphBuilder, executionHistoryService);
        seqEngine.execute(dsl, seqPool, inputs, seqCb);
        Map<String, NodeStatusEnum> seqStatuses = captureNodeStatuses(dsl);
        Map<String, Map<String, Object>> seqPoolSnap = snapshotWorkflowContextStore(seqPool, dsl);

        // --- LEGACY parallel ---
        WorkflowContextStore parPool = new WorkflowContextStore();
        CapturingFlowEventCallback parCb = new CapturingFlowEventCallback();
        ParallelWorkflowEngine parEngine = new ParallelWorkflowEngine(stubExecutors, topologyValidator, graphBuilder, new EngineProperties(), executionHistoryService);
        parEngine.execute(dsl, parPool, inputs, parCb);
        Map<String, NodeStatusEnum> parStatuses = captureNodeStatuses(dsl);
        Map<String, Map<String, Object>> parPoolSnap = snapshotWorkflowContextStore(parPool, dsl);

        // --- LANGGRAPH ---
        WorkflowContextStore lgPool = new WorkflowContextStore();
        CapturingFlowEventCallback lgCb = new CapturingFlowEventCallback();
        LangGraphEngine lgEngine = new LangGraphEngine(stubExecutors, topologyValidator, graphBuilder);
        lgEngine.execute(dsl, lgPool, inputs, lgCb);
        Map<String, NodeStatusEnum> lgStatuses = captureNodeStatuses(dsl);
        Map<String, Map<String, Object>> lgPoolSnap = snapshotWorkflowContextStore(lgPool, dsl);

        // --- Node status assertions ---
        for (String nodeId : seqStatuses.keySet()) {
            assertEquals(seqStatuses.get(nodeId), parStatuses.get(nodeId),
                    "LEGACY-SEQ vs LEGACY-PAR node status mismatch for " + nodeId);
            assertEquals(seqStatuses.get(nodeId), lgStatuses.get(nodeId),
                    "LEGACY-SEQ vs LANGGRAPH node status mismatch for " + nodeId);
        }

        // All nodes should be SUCCESS for a linear happy-path
        for (String nodeId : seqStatuses.keySet()) {
            assertEquals(NodeStatusEnum.SUCCESS, seqStatuses.get(nodeId),
                    "Expected SUCCESS for " + nodeId + " in SEQ run");
        }

        // --- WorkflowContextStore assertions ---
        // Start node: user_input should be stored
        assertVariableEquals(seqPoolSnap, parPoolSnap, "node-start::001");
        assertVariableEquals(seqPoolSnap, lgPoolSnap, "node-start::001");

        // LLM node: output should be stored
        assertVariableEquals(seqPoolSnap, parPoolSnap, "llm::002");
        assertVariableEquals(seqPoolSnap, lgPoolSnap, "llm::002");

        // End node: content should be stored
        assertVariableEquals(seqPoolSnap, parPoolSnap, "node-end::003");
        assertVariableEquals(seqPoolSnap, lgPoolSnap, "node-end::003");

        // --- Specific value checks (LEGACY-SEQ vs LANGGRAPH) ---
        assertEquals(seqPool.get("llm::002", "output"), lgPool.get("llm::002", "output"),
                "LLM output value should match across engines");
    }

        /**
         * Branching workflow: start → llm → (success: end_success) / (fail: end_error)
         * Since stub LLM always succeeds, the success-branch node should be SUCCESS.
         *
         * Unreachable fail-path nodes are normalized to SKIP at end of execution:
         * - LEGACY marks them MARK, then the MARK-to-SKIP sweep converts to SKIP.
         * - LANGGRAPH marks them MARK, then the sweep converts to SKIP.
         * Both engines agree on the final SKIP status (parity preserved).
         */
    @Test
    void branchingWorkflow_successPathExecuted_failPathSkipped() throws Exception {
        WorkflowDSL dsl = createBranchingDsl();
        Map<String, Object> inputs = Map.of("user_input", "Hello");

        // --- LEGACY sequential ---
        WorkflowContextStore seqPool = new WorkflowContextStore();
        CapturingFlowEventCallback seqCb = new CapturingFlowEventCallback();
        DagWorkflowEngine seqEngine = new DagWorkflowEngine(stubExecutors, topologyValidator, graphBuilder, executionHistoryService);
        seqEngine.execute(dsl, seqPool, inputs, seqCb);
        Map<String, NodeStatusEnum> seqStatuses = captureNodeStatuses(dsl);

        // --- LANGGRAPH ---
        WorkflowContextStore lgPool = new WorkflowContextStore();
        CapturingFlowEventCallback lgCb = new CapturingFlowEventCallback();
        LangGraphEngine lgEngine = new LangGraphEngine(stubExecutors, topologyValidator, graphBuilder);
        lgEngine.execute(dsl, lgPool, inputs, lgCb);
        Map<String, NodeStatusEnum> lgStatuses = captureNodeStatuses(dsl);

        // --- Success path: LLM node and success end should be SUCCESS in both engines ---
        assertEquals(NodeStatusEnum.SUCCESS, seqStatuses.get("llm::002"));
        assertEquals(NodeStatusEnum.SUCCESS, lgStatuses.get("llm::002"));

        assertEquals(NodeStatusEnum.SUCCESS, seqStatuses.get("node-end::003"),
                "Success-path end node should be SUCCESS in LEGACY");
        assertEquals(NodeStatusEnum.SUCCESS, lgStatuses.get("node-end::003"),
                "Success-path end node should be SUCCESS in LANGGRAPH");

        // --- Fail path: unreachable nodes are normalized to SKIP (parity preserved) ---
        // Both engines mark unreachable fail-path nodes as MARK via markOppositeBranch / executeNormalCondition,
        // then the MARK-to-SKIP sweep converts them to SKIP for cleaner state reporting.
        assertEquals(NodeStatusEnum.SKIP, seqStatuses.get("node-end::004"),
                "Unreachable fail-path nodes are resolved to SKIP by MARK-to-SKIP normalization");
        assertEquals(NodeStatusEnum.SKIP, lgStatuses.get("node-end::004"),
                "Unreachable fail-path nodes are resolved to SKIP by MARK-to-SKIP normalization");

        // Both engines agree on the status — parity is maintained even for the gap
        assertEquals(seqStatuses.get("node-end::004"), lgStatuses.get("node-end::004"),
                "LEGACY and LANGGRAPH should agree on fail-path node status");

        // SKIP is a terminal state in the engine model: executed() returns true
        assertTrue(seqStatuses.get("node-end::004").executed(),
                "Fail-path node (SKIP) is a terminal state in the engine model");
    }

    // =====================================================================
    // Deterministic stub executors
    // =====================================================================

    /**
     * Stub LLM executor that returns a fixed deterministic output.
     * Extends AbstractNodeHandler to preserve the full execution pipeline
     * (input resolution, output storage, callback events).
     */
    static class DeterministicLLMNodeHandler extends AbstractNodeHandler {

        static final String FIXED_RESPONSE = "deterministic-llm-response";

        @Override
        public NodeTypeEnum getNodeType() {
            return NodeTypeEnum.LLM;
        }

        @Override
        protected NodeRunResult executeNode(NodeState nodeState, Map<String, Object> inputs) {
            NodeRunResult result = new NodeRunResult();
            result.setInputs(inputs);
            result.setOutputs(Map.of("output", FIXED_RESPONSE));
            result.setRawOutput(FIXED_RESPONSE);
            result.setStatus(NodeExecStatusEnum.SUCCESS);
            return result;
        }
    }

    // =====================================================================
    // Capturing stream callback
    // =====================================================================

    /**
     * Simple FlowEventCallback that records all event types for comparison.
     * Does not attempt to compare event ordering (async queues make this
     * non-deterministic), only verifies the set of emitted event types.
     */
    static class CapturingFlowEventCallback implements FlowEventCallback {

        private final List<String> eventTypes = new CopyOnWriteArrayList<>();
        private final List<Object> eventData = new CopyOnWriteArrayList<>();

        @Override
        public void callback(String eventType, Object data) {
            eventTypes.add(eventType);
            eventData.add(data);
        }

        @Override
        public void finished() {
            eventTypes.add("finished");
        }

        List<String> getEventTypes() {
            return Collections.unmodifiableList(eventTypes);
        }

        List<Object> getEventData() {
            return Collections.unmodifiableList(eventData);
        }
    }

    // =====================================================================
    // DSL fixture builders
    // =====================================================================

    /**
     * Linear workflow: start → llm → end
     */
    static WorkflowDSL createLinearDsl() {
        WorkflowDSL dsl = new WorkflowDSL();
        dsl.setFlowId("parity-test-linear");
        dsl.setUuid("parity-test-linear-uuid");

        Node startNode = buildNode("node-start::001", "node-start", "Start");
        startNode.getData().setNodeParam(new HashMap<>());

        Node llmNode = buildNode("llm::002", "llm", "LLM Node");
        llmNode.getData().setInputs(List.of(
                buildRefInput("input-1", "user_input", "node-start::001", "user_input")
        ));
        Map<String, Object> llmParam = new HashMap<>();
        llmParam.put("template", "Please answer: {{user_input}}");
        llmParam.put("domain", "test-model");
        llmNode.getData().setNodeParam(llmParam);
        llmNode.getData().setOutputs(List.of(
                buildOutput("output-1", "output")
        ));

        Node endNode = buildNode("node-end::003", "node-end", "End");
        endNode.getData().setInputs(List.of(
                buildRefInput("input-2", "llm_output", "llm::002", "output")
        ));
        Map<String, Object> endParam = new HashMap<>();
        endParam.put("outputMode", 0); // DIRECT_MODE
        endNode.getData().setNodeParam(endParam);

        dsl.setNodes(List.of(startNode, llmNode, endNode));
        dsl.setEdges(List.of(
                buildEdge("node-start::001", "llm::002"),
                buildEdge("llm::002", "node-end::003")
        ));

        return dsl;
    }

    /**
     * Branching workflow: start → llm → (success: end_success) / (fail: end_error)
     * Success edge has no sourceHandle (→ nextNodes).
     * Fail edge has sourceHandle starting with "fail_" (→ failNodes).
     */
    static WorkflowDSL createBranchingDsl() {
        WorkflowDSL dsl = new WorkflowDSL();
        dsl.setFlowId("parity-test-branch");
        dsl.setUuid("parity-test-branch-uuid");

        Node startNode = buildNode("node-start::001", "node-start", "Start");
        startNode.getData().setNodeParam(new HashMap<>());

        Node llmNode = buildNode("llm::002", "llm", "LLM Node");
        llmNode.getData().setInputs(List.of(
                buildRefInput("input-1", "user_input", "node-start::001", "user_input")
        ));
        Map<String, Object> llmParam = new HashMap<>();
        llmParam.put("template", "Please answer: {{user_input}}");
        llmParam.put("domain", "test-model");
        llmNode.getData().setNodeParam(llmParam);
        llmNode.getData().setOutputs(List.of(
                buildOutput("output-1", "output")
        ));

        Node successEnd = buildNode("node-end::003", "node-end", "Success End");
        successEnd.getData().setInputs(List.of(
                buildRefInput("input-2", "llm_output", "llm::002", "output")
        ));
        Map<String, Object> successParam = new HashMap<>();
        successParam.put("outputMode", 0);
        successEnd.getData().setNodeParam(successParam);

        Node failEnd = buildNode("node-end::004", "node-end", "Error End");
        failEnd.getData().setInputs(List.of(
                buildRefInput("input-3", "error_info", "llm::002", "output")
        ));
        Map<String, Object> failParam = new HashMap<>();
        failParam.put("outputMode", 0);
        failEnd.getData().setNodeParam(failParam);

        dsl.setNodes(List.of(startNode, llmNode, successEnd, failEnd));
        dsl.setEdges(List.of(
                buildEdge("node-start::001", "llm::002"),
                // Success path: no sourceHandle → nextNodes
                buildEdge("llm::002", "node-end::003"),
                // Fail path: sourceHandle starts with "fail_" → failNodes
                buildEdge("llm::002", "node-end::004", "fail_branch_1")
        ));

        return dsl;
    }

    // =====================================================================
    // DSL model helpers
    // =====================================================================

    static Node buildNode(String id, String nodeTypeValue, String aliasName) {
        Node node = new Node();
        node.setId(id);
        NodeData data = new NodeData();
        NodeMeta meta = new NodeMeta();
        meta.setNodeType(nodeTypeValue);
        meta.setAliasName(aliasName);
        data.setNodeMeta(meta);
        node.setData(data);
        return node;
    }

    static InputItem buildRefInput(String id, String name, String refNodeId, String refName) {
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

    static InputItem buildLiteralInput(String id, String name, Object literalValue) {
        InputItem item = new InputItem();
        item.setId(id);
        item.setName(name);
        InputSchema schema = new InputSchema();
        Value value = new Value();
        value.setType("literal");
        value.setContent(literalValue);
        schema.setValue(value);
        item.setSchema(schema);
        return item;
    }

    static OutputItem buildOutput(String id, String name) {
        OutputItem item = new OutputItem();
        item.setId(id);
        item.setName(name);
        return item;
    }

    static Edge buildEdge(String source, String target) {
        Edge edge = new Edge();
        edge.setSourceNodeId(source);
        edge.setTargetNodeId(target);
        return edge;
    }

    static Edge buildEdge(String source, String target, String sourceHandle) {
        Edge edge = new Edge();
        edge.setSourceNodeId(source);
        edge.setTargetNodeId(target);
        edge.setSourceHandle(sourceHandle);
        return edge;
    }

    // =====================================================================
    // Assertion helpers
    // =====================================================================

    static Map<String, NodeStatusEnum> captureNodeStatuses(WorkflowDSL dsl) {
        Map<String, NodeStatusEnum> map = new LinkedHashMap<>();
        for (Node node : dsl.getNodes()) {
            map.put(node.getId(), node.getStatus());
        }
        return map;
    }

    static Map<String, Map<String, Object>> snapshotWorkflowContextStore(WorkflowContextStore pool, WorkflowDSL dsl) {
        Map<String, Map<String, Object>> snap = new LinkedHashMap<>();
        for (Node node : dsl.getNodes()) {
            Map<String, Object> nodeVars = pool.get(node.getId());
            if (nodeVars != null && !nodeVars.isEmpty()) {
                snap.put(node.getId(), new LinkedHashMap<>(nodeVars));
            }
        }
        return snap;
    }

    static void assertVariableEquals(
            Map<String, Map<String, Object>> expected,
            Map<String, Map<String, Object>> actual,
            String nodeId) {
        Map<String, Object> expVars = expected.getOrDefault(nodeId, Map.of());
        Map<String, Object> actVars = actual.getOrDefault(nodeId, Map.of());
        assertEquals(expVars.keySet(), actVars.keySet(),
                "WorkflowContextStore key set mismatch for node " + nodeId);
        for (String key : expVars.keySet()) {
            assertEquals(String.valueOf(expVars.get(key)), String.valueOf(actVars.get(key)),
                    "WorkflowContextStore value mismatch for " + nodeId + "." + key);
        }
    }
}
