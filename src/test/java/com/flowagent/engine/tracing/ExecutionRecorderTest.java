package com.flowagent.engine.tracing;

import com.flowagent.common.enums.NodeExecStatusEnum;
import com.flowagent.engine.WorkflowContextStore;
import com.flowagent.engine.context.EngineContextHolder;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.engine.dsl.model.NodeData;
import com.flowagent.engine.dsl.model.NodeMeta;
import com.flowagent.persistence.entity.NodeRunLogEntity;
import com.flowagent.persistence.service.ExecutionHistoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit test for ExecutionRecorder. Uses a Mockito stub for ExecutionHistoryService and a
 * manually populated EngineContext (no Spring / no MySQL required).
 */
@ExtendWith(MockitoExtension.class)
class ExecutionRecorderTest {

    @Mock
    private ExecutionHistoryService historyService;

    private ExecutionRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new ExecutionRecorder(historyService);

        EngineContextHolder.EngineContext ctx = new EngineContextHolder.EngineContext();
        ctx.setFlowId("wf-trace-1");
        ctx.setExecutionId(987654321L);
        EngineContextHolder.set(ctx);
    }

    @AfterEach
    void tearDown() {
        EngineContextHolder.remove();
    }

    @Test
    void recordsNodeLogWithResolvedContext() {
        Node node = new Node();
        node.setId("llm::001");
        NodeData data = new NodeData();
        NodeMeta meta = new NodeMeta();
        meta.setAliasName("LLM");
        data.setNodeMeta(meta);
        node.setData(data);
        node.setExecutedCount(new AtomicInteger(2)); // second attempt (1 retry)

        NodeRunResult result = new NodeRunResult();
        result.setStatus(NodeExecStatusEnum.SUCCESS);
        result.setInputs(Map.of("question", "hi"));
        result.setOutputs(Map.of("answer", "hello"));

        NodeState nodeState = new NodeState(node, new WorkflowContextStore(), null);

        recorder.record(nodeState, result, 150L);

        verify(historyService).recordNodeLog(any(NodeRunLogEntity.class));
    }

    @Test
    void mapsNodeFieldsAndDurationIntoLog() {
        Node node = new Node();
        node.setId("condition-switch::002");
        NodeData data = new NodeData();
        NodeMeta meta = new NodeMeta();
        meta.setAliasName("Switch");
        data.setNodeMeta(meta);
        node.setData(data);
        node.setExecutedCount(new AtomicInteger(1));

        NodeRunResult result = new NodeRunResult();
        result.setStatus(NodeExecStatusEnum.SUCCESS);
        result.setInputs(Map.of("x", 1));
        result.setOutputs(Map.of("y", 2));

        NodeState nodeState = new NodeState(node, new WorkflowContextStore(), null);

        recorder.record(nodeState, result, 250L);

        NodeRunLogEntity[] captured = new NodeRunLogEntity[1];
        verify(historyService).recordNodeLog(org.mockito.ArgumentMatchers.argThat(log -> {
            captured[0] = log;
            return true;
        }));

        NodeRunLogEntity log = captured[0];
        assertNotNull(log);
        assertEquals(987654321L, log.getExecutionId());
        assertEquals("condition-switch::002", log.getNodeId());
        assertEquals("CONDITION_SWITCH", log.getNodeType());
        assertEquals("SUCCESS", log.getStatus());
        assertEquals(250L, log.getDurationMs());
        assertEquals(1, log.getRetryCount());
        assertEquals("{\"x\":1}", log.getInputData());
        assertEquals("{\"y\":2}", log.getOutputData());
    }

    @Test
    void skipsRecordingWhenNoExecutionContext() {
        EngineContextHolder.remove();
        Node node = new Node();
        node.setId("llm::001");
        NodeData data = new NodeData();
        NodeMeta meta = new NodeMeta();
        data.setNodeMeta(meta);
        node.setData(data);
        node.setExecutedCount(new AtomicInteger(1));

        NodeRunResult result = new NodeRunResult();
        result.setStatus(NodeExecStatusEnum.SUCCESS);

        recorder.record(new NodeState(node, new WorkflowContextStore(), null), result, 10L);

        // No execution context => no persistence call
        verify(historyService, never()).recordNodeLog(any());
    }
}
