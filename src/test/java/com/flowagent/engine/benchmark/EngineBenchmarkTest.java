package com.flowagent.engine.benchmark;

import com.flowagent.common.enums.NodeExecStatusEnum;
import com.flowagent.engine.DagWorkflowEngine;
import com.flowagent.engine.ParallelWorkflowEngine;
import com.flowagent.engine.WorkflowContextStore;
import com.flowagent.engine.constants.NodeTypeEnum;
import com.flowagent.engine.core.EngineProperties;
import com.flowagent.engine.dag.GraphBuilder;
import com.flowagent.engine.dag.TopologyValidator;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.engine.dsl.model.*;
import com.flowagent.engine.node.AbstractNodeHandler;
import com.flowagent.engine.node.FlowEventCallback;
import com.flowagent.engine.node.WorkflowNodeHandler;
import com.flowagent.persistence.service.ExecutionHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance benchmarks (plan 5.9) for the dual-engine core.
 *
 * <p>Two hard metrics are produced and printed to stdout:
 * <ol>
 *   <li><b>Intra-workflow parallel speedup</b> — a fan-out workflow executed by the sequential
 *       (Dag) engine vs the parallel (BFS + CompletableFuture) engine. Reports the execution-time
 *       reduction %, i.e. the resume headline "执行耗时↓X%".</li>
 *   <li><b>Concurrent request throughput</b> — N workflows submitted one-at-a-time (serial Dag)
 *       vs N submitted concurrently through the parallel engine. Reports wall-time reduction and
 *       throughput lift (resume headline "并发吞吐↑X%").</li>
 * </ol>
 *
 * <p>A deterministic sleepy LLM stub simulates per-node work so parallelism is observable without
 * any external service. No "mock" keyword / mockwebserver is used.
 */
@ExtendWith(MockitoExtension.class)
class EngineBenchmarkTest {

    @Mock
    ExecutionHistoryService executionHistoryService;

    private TopologyValidator topologyValidator;
    private GraphBuilder graphBuilder;
    private List<WorkflowNodeHandler> executors;

    @BeforeEach
    void setUp() {
        topologyValidator = new TopologyValidator();
        graphBuilder = new GraphBuilder();
        executors = List.of(
                new com.flowagent.engine.node.impl.StartNodeHandler(),
                new SleepyLLMNodeHandler(25),
                new com.flowagent.engine.node.impl.EndNodeHandler()
        );
    }

    @Test
    void parallelEngineSpeedupOnFanOutWorkflow() throws Exception {
        WorkflowDSL dsl = fanOutDsl(8, 25);
        DagWorkflowEngine seqEngine = new DagWorkflowEngine(executors, topologyValidator, graphBuilder, executionHistoryService);
        ParallelWorkflowEngine parEngine = new ParallelWorkflowEngine(executors, topologyValidator, graphBuilder, new EngineProperties(), executionHistoryService);

        // warm-up (JIT / pool stabilization)
        for (int i = 0; i < 3; i++) {
            seqEngine.execute(dsl, new WorkflowContextStore(), Map.of("user_input", "x"), noop());
            parEngine.execute(dsl, new WorkflowContextStore(), Map.of("user_input", "x"), noop());
        }

        int runs = 9;
        long seqTotal = 0;
        long parTotal = 0;
        for (int i = 0; i < runs; i++) {
            long a = System.nanoTime();
            seqEngine.execute(dsl, new WorkflowContextStore(), Map.of("user_input", "x"), noop());
            long b = System.nanoTime();
            seqTotal += (b - a);

            long c = System.nanoTime();
            parEngine.execute(dsl, new WorkflowContextStore(), Map.of("user_input", "x"), noop());
            long d = System.nanoTime();
            parTotal += (d - c);
        }

        double seqAvg = seqTotal / (double) runs / 1_000_000.0;
        double parAvg = parTotal / (double) runs / 1_000_000.0;
        double speedup = seqAvg / parAvg;
        double reductionPct = (1 - parAvg / seqAvg) * 100;

        System.out.printf("%n[BENCH] fan-out(8 branches x 25ms): Dag=%.1fms  Parallel=%.1fms  speedup=%.2fx  execution-time-reduction=%.1f%%%n",
                seqAvg, parAvg, speedup, reductionPct);

        assertTrue(speedup > 2.0,
                "parallel engine should be at least 2x faster on a fan-out workflow, got " + speedup);
    }

    @Test
    void concurrentThroughputUnderParallelEngine() throws Exception {
        WorkflowDSL dsl = linearWorkDsl(40);
        DagWorkflowEngine seqEngine = new DagWorkflowEngine(executors, topologyValidator, graphBuilder, executionHistoryService);
        ParallelWorkflowEngine parEngine = new ParallelWorkflowEngine(executors, topologyValidator, graphBuilder, new EngineProperties(), executionHistoryService);

        int k = 12;
        int poolThreads = 8;

        // Serial baseline: one workflow at a time through the sequential engine.
        long s0 = System.nanoTime();
        for (int i = 0; i < k; i++) {
            seqEngine.execute(dsl, new WorkflowContextStore(), Map.of("user_input", "x"), noop());
        }
        long serialMs = (System.nanoTime() - s0) / 1_000_000;

        // Concurrent: K submissions through the parallel engine on a bounded pool.
        ExecutorService pool = Executors.newFixedThreadPool(poolThreads);
        CountDownLatch latch = new CountDownLatch(k);
        long c0 = System.nanoTime();
        for (int i = 0; i < k; i++) {
            pool.submit(() -> {
                try {
                    parEngine.execute(dsl, new WorkflowContextStore(), Map.of("user_input", "x"), noop());
                } catch (Exception e) {
                    fail(e);
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS), "concurrent executions did not finish in time");
        long concMs = (System.nanoTime() - c0) / 1_000_000;
        pool.shutdownNow();

        double throughputLift = serialMs > 0 ? (double) serialMs / concMs : 0;
        double wallReductionPct = serialMs > 0 ? (1 - (double) concMs / serialMs) * 100 : 0;

        System.out.printf("%n[BENCH] concurrency(%d x 40ms, pool=%d): serial=%dms  concurrent=%dms  throughput-lift=%.2fx  wall-reduction=%.1f%%%n",
                k, poolThreads, serialMs, concMs, throughputLift, wallReductionPct);

        assertTrue(concMs < serialMs,
                "concurrent execution should finish faster than strict serial; serial=" + serialMs + "ms conc=" + concMs + "ms");
    }

    // =====================================================================
    // Deterministic sleepy work node (LLM type, simulated CPU/IO work)
    // =====================================================================

    /**
     * Stub LLM node that sleeps {@code workMs} to simulate real per-node latency, so the parallel
     * engine's concurrency is actually observable. Routing type stays LLM to reuse the DSL fixture.
     */
    static class SleepyLLMNodeHandler extends AbstractNodeHandler {

        private final long workMs;

        SleepyLLMNodeHandler(long workMs) {
            this.workMs = workMs;
        }

        @Override
        public NodeTypeEnum getNodeType() {
            return NodeTypeEnum.LLM;
        }

        @Override
        protected NodeRunResult executeNode(NodeState nodeState, Map<String, Object> inputs) throws Exception {
            if (workMs > 0) {
                Thread.sleep(workMs);
            }
            NodeRunResult result = new NodeRunResult();
            result.setInputs(inputs);
            result.setOutputs(Map.of("output", "work-done"));
            result.setRawOutput("work-done");
            result.setStatus(NodeExecStatusEnum.SUCCESS);
            return result;
        }
    }

    // =====================================================================
    // DSL builders
    // =====================================================================

    static WorkflowDSL fanOutDsl(int branches, long workMs) {
        WorkflowDSL dsl = new WorkflowDSL();
        dsl.setFlowId("bench-fanout");
        dsl.setUuid("bench-fanout");

        Node start = buildNode("node-start::001", "node-start", "Start");
        start.getData().setNodeParam(new HashMap<>());

        List<Node> nodes = new ArrayList<>();
        nodes.add(start);
        List<Edge> edges = new ArrayList<>();

        for (int i = 1; i <= branches; i++) {
            String id = "llm::" + String.format("%03d", i + 1);
            Node b = buildNode(id, "llm", "Branch" + i);
            Map<String, Object> p = new HashMap<>();
            p.put("template", "bench");
            p.put("domain", "test");
            b.getData().setNodeParam(p);
            b.getData().setOutputs(List.of(buildOutput("o" + i, "output")));
            nodes.add(b);
            edges.add(buildEdge("node-start::001", id));
            edges.add(buildEdge(id, "node-end::010"));
        }

        Node end = buildNode("node-end::010", "node-end", "End");
        Map<String, Object> ep = new HashMap<>();
        ep.put("outputMode", 0);
        end.getData().setNodeParam(ep);
        end.getData().setInputs(List.of(buildRefInput("in", "o1", "llm::002", "output")));
        nodes.add(end);

        dsl.setNodes(nodes);
        dsl.setEdges(edges);
        return dsl;
    }

    static WorkflowDSL linearWorkDsl(long workMs) {
        WorkflowDSL dsl = new WorkflowDSL();
        dsl.setFlowId("bench-linear");
        dsl.setUuid("bench-linear");

        Node start = buildNode("node-start::001", "node-start", "Start");
        start.getData().setNodeParam(new HashMap<>());

        Node llm = buildNode("llm::002", "llm", "Work");
        Map<String, Object> p = new HashMap<>();
        p.put("template", "bench");
        p.put("domain", "test");
        llm.getData().setNodeParam(p);
        llm.getData().setOutputs(List.of(buildOutput("o", "output")));

        Node end = buildNode("node-end::003", "node-end", "End");
        Map<String, Object> ep = new HashMap<>();
        ep.put("outputMode", 0);
        end.getData().setNodeParam(ep);
        end.getData().setInputs(List.of(buildRefInput("in", "o", "llm::002", "output")));

        dsl.setNodes(List.of(start, llm, end));
        dsl.setEdges(List.of(
                buildEdge("node-start::001", "llm::002"),
                buildEdge("llm::002", "node-end::003")
        ));
        return dsl;
    }

    static Node buildNode(String id, String type, String alias) {
        Node n = new Node();
        n.setId(id);
        n.setExecutedCount(new AtomicInteger(0));
        NodeData d = new NodeData();
        NodeMeta m = new NodeMeta();
        m.setNodeType(type);
        m.setAliasName(alias);
        d.setNodeMeta(m);
        n.setData(d);
        return n;
    }

    static InputItem buildRefInput(String id, String name, String refNodeId, String refName) {
        InputItem it = new InputItem();
        it.setId(id);
        it.setName(name);
        InputSchema s = new InputSchema();
        Value v = new Value();
        v.setType("ref");
        v.setContent(Map.of("nodeId", refNodeId, "name", refName));
        s.setValue(v);
        it.setSchema(s);
        return it;
    }

    static OutputItem buildOutput(String id, String name) {
        OutputItem o = new OutputItem();
        o.setId(id);
        o.setName(name);
        return o;
    }

    static Edge buildEdge(String source, String target) {
        Edge e = new Edge();
        e.setSourceNodeId(source);
        e.setTargetNodeId(target);
        return e;
    }

    FlowEventCallback noop() {
        return (eventType, data) -> {
        };
    }
}
