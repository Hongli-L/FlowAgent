package com.flowagent.engine;

import com.alibaba.ttl.TtlRunnable;
import com.alibaba.ttl.threadpool.TtlExecutors;
import com.flowagent.common.enums.EndNodeOutputModeEnum;
import com.flowagent.common.enums.ExecutionStatusEnum;
import com.flowagent.common.enums.NodeExecStatusEnum;
import com.flowagent.common.enums.NodeStatusEnum;
import com.flowagent.engine.constants.NodeTypeEnum;
import com.flowagent.engine.context.EngineContextHolder;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.engine.dsl.model.WorkflowDSL;
import com.flowagent.engine.domain.callbacks.ChatCallBackStreamResult;
import com.flowagent.engine.domain.callbacks.LLMGenerate;
import com.flowagent.engine.dag.GraphBuilder;
import com.flowagent.engine.dag.TopologyValidator;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.engine.node.WorkflowNodeHandler;
import com.flowagent.engine.node.FlowEventCallback;
import com.flowagent.engine.node.callback.WorkflowMsgCallback;
import com.flowagent.engine.util.FlowUtil;
import com.flowagent.engine.core.EngineProperties;
import com.flowagent.persistence.service.ExecutionHistoryService;
import com.flowagent.common.exception.ErrorCode;
import com.flowagent.common.exception.NodeCustomException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Parallel Workflow execution engine
 * Executes workflow nodes in parallel where possible
 */
@Slf4j
@Component
public class ParallelWorkflowEngine {

    private static final String TRIGGER_SOURCE_API = "API";

    private final Map<NodeTypeEnum, WorkflowNodeHandler> nodeExecutors;
    private final ExecutorService executorService;
    private final TopologyValidator topologyValidator;
    private final GraphBuilder graphBuilder;
    private final ExecutionHistoryService executionHistoryService;

    public ParallelWorkflowEngine(List<WorkflowNodeHandler> executors, TopologyValidator topologyValidator,
                                  GraphBuilder graphBuilder, EngineProperties engineProperties,
                                  ExecutionHistoryService executionHistoryService) {
        this.topologyValidator = topologyValidator;
        this.graphBuilder = graphBuilder;
        this.executionHistoryService = executionHistoryService;
        this.nodeExecutors = new HashMap<>();
        for (WorkflowNodeHandler executor : executors) {
            this.nodeExecutors.put(executor.getNodeType(), executor);
        }
        // Bounded thread pool: prevents resource exhaustion under high concurrency.
        // CallerRunsPolicy provides backpressure — when pool+queue are full,
        // the submitting thread runs the task itself, naturally throttling throughput.
        this.executorService = TtlExecutors.getTtlExecutorService(
                new ThreadPoolExecutor(
                        engineProperties.getCorePoolSize(),
                        engineProperties.getMaxPoolSize(),
                        engineProperties.getKeepAliveSeconds(),
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(engineProperties.getQueueCapacity()),
                        new ThreadPoolExecutor.CallerRunsPolicy()
                )
        );
        log.info("Registered {} node executors for ParallelEngine (pool: core={}, max={}, queue={})",
                nodeExecutors.size(), engineProperties.getCorePoolSize(), engineProperties.getMaxPoolSize(), engineProperties.getQueueCapacity());
    }

    public void execute(WorkflowDSL workflowDSL, WorkflowContextStore variablePool, Map<String, Object> inputs, FlowEventCallback callback) throws Exception {
        log.info("Starting parallel workflow execution with {} nodes", workflowDSL.getNodes().size());

        verifyWorkflow(workflowDSL);
        variablePool.clear();

        Queue<ChatCallBackStreamResult> orderStreamResultQ = new LinkedBlockingQueue<>();
        BlockingQueue<LLMGenerate> streamQueue = new LinkedBlockingQueue<>();

        Node endNode = workflowDSL.getNodes().stream().filter(s -> s.getNodeType() == NodeTypeEnum.END).findFirst().orElseThrow();
        String sid = FlowUtil.genWorkflowId(workflowDSL.getFlowId());
        WorkflowMsgCallback workflowCallback = new WorkflowMsgCallback(
                sid,
                callback,
                Objects.equals(endNode.getData().getNodeParam().get("outputMode"), 1) ? EndNodeOutputModeEnum.VARIABLE_MODE : EndNodeOutputModeEnum.DIRECT_MODE,
                streamQueue,
                orderStreamResultQ
        );

        EngineContextHolder.EngineContext ctx = EngineContextHolder.initContext(workflowDSL.getFlowId(), workflowDSL.getUuid(), workflowCallback);
        ctx.setWorkflowNodes(workflowDSL.getNodes());
        ctx.setNodeExecutors(this.nodeExecutors);
        Long executionId = executionHistoryService.createExecution(workflowDSL.getFlowId(), TRIGGER_SOURCE_API);
        EngineContextHolder.get().setExecutionId(executionId);
        workflowCallback.onWorkflowStart();

        CompletableFuture<Void> workflowFuture = new CompletableFuture<>();
        AtomicInteger activeTasks = new AtomicInteger(0);

        try {
            Node startNode = graphBuilder.build(workflowDSL).getStartNode();
            initializeStartNodeInputs(startNode, variablePool, inputs);

            // Initial task
            activeTasks.incrementAndGet();
            executorService.submit(TtlRunnable.get(() ->
                executeNode(startNode, variablePool, workflowCallback, activeTasks, workflowFuture)
            ));

            // Wait for completion
            workflowFuture.get();

            // Normalize unreachable MARK nodes to SKIP after execution
            normalizeMarkNodes(workflowDSL);

            log.info("Parallel Workflow: {} execution completed successfully", sid);
            workflowCallback.onWorkflowEnd(new NodeRunResult());
            executionHistoryService.completeExecution(executionId, ExecutionStatusEnum.SUCCESS.name());
        } catch (Exception e) {
            log.error("Workflow execution failed", e);
            workflowCallback.onWorkflowEnd(new NodeRunResult());
            executionHistoryService.completeExecution(executionId, ExecutionStatusEnum.FAILED.name());
            throw e;
        } finally {
            workflowCallback.finished();
            EngineContextHolder.remove();
        }
    }

    private void executeNode(Node node, WorkflowContextStore variablePool, WorkflowMsgCallback callback, AtomicInteger activeTasks, CompletableFuture<Void> workflowFuture) {
        try {
            boolean shouldRun = false;
            boolean isSkip = false;

            synchronized (node) {
                // If already running or executed, skip
                if (node.getStatus().executed() || node.getStatus() == NodeStatusEnum.RUNNING) {
                    return;
                }

                // Check all pre-nodes
                if (!CollectionUtils.isEmpty(node.getPreNodes())) {
                    for (Node preNode : node.getPreNodes()) {
                        if (!preNode.getStatus().executed()) {
                            // Pre-node not ready, abort. Current node will be triggered by pre-node later.
                            return;
                        }
                    }
                }

                // Determine execution status (MARK logic)
                if (node.getStatus() == NodeStatusEnum.MARK) {
                    boolean canExecute = false;
                    for (Node preNode : node.getPreNodes()) {
                        if (preNode.getStatus() == NodeStatusEnum.SKIP) {
                            continue;
                        } else if (preNode.getStatus() == NodeStatusEnum.ERROR) {
                            if (preNode.getFailNodes().contains(node)) {
                                canExecute = true;
                                break;
                            }
                        } else if (preNode.getStatus() == NodeStatusEnum.SUCCESS) {
                            if (preNode.getNextNodes().contains(node)) {
                                canExecute = true;
                                break;
                            }
                        }
                    }
                    if (!canExecute) {
                        node.setStatus(NodeStatusEnum.SKIP);
                        isSkip = true;
                    } else {
                        node.setStatus(NodeStatusEnum.RUNNING);
                        shouldRun = true;
                    }
                } else {
                    node.setStatus(NodeStatusEnum.RUNNING);
                    shouldRun = true;
                }
            }

            if (isSkip) {
                // Propagate skip to next nodes
                executeNormalCondition(node, variablePool, callback, activeTasks, workflowFuture);
                return;
            }

            if (shouldRun) {
                if (log.isDebugEnabled()) {
                    log.debug("Executing node in parallel: {}", node.getId());
                }
                
                NodeTypeEnum nodeType = node.getNodeType();
                WorkflowNodeHandler executor = nodeExecutors.get(nodeType);
                if (executor == null) {
                    throw new IllegalStateException("No executor found for node type: " + nodeType);
                }

                NodeExecStatusEnum execStatus;
                while (true) {
                    NodeRunResult res = executor.execute(new NodeState(node, variablePool, callback));
                    execStatus = res.getStatus();
                    if (execStatus != NodeExecStatusEnum.ERR_RETRY) {
                        break;
                    }
                }

                if (execStatus == NodeExecStatusEnum.ERR_INTERUPT) {
                    node.setStatus(NodeStatusEnum.ERROR);
                    throw new NodeCustomException(ErrorCode.INTERRUPTED_ERROR);
                } else if (execStatus == NodeExecStatusEnum.ERR_FAIL_CONDITION) {
                    node.setStatus(NodeStatusEnum.ERROR);
                    executeFailedCondition(node, variablePool, callback, activeTasks, workflowFuture);
                } else if (execStatus == NodeExecStatusEnum.ERR_CODE_MSG) {
                    node.setStatus(NodeStatusEnum.ERROR);
                    executeNormalCondition(node, variablePool, callback, activeTasks, workflowFuture);
                } else {
                    node.setStatus(NodeStatusEnum.SUCCESS);
                    executeNormalCondition(node, variablePool, callback, activeTasks, workflowFuture);
                }
            }
        } catch (Exception e) {
            workflowFuture.completeExceptionally(e);
        } finally {
            if (activeTasks.decrementAndGet() == 0) {
                workflowFuture.complete(null);
            }
        }
    }

    private void executeNormalCondition(Node node, WorkflowContextStore variablePool, WorkflowMsgCallback callback, AtomicInteger activeTasks, CompletableFuture<Void> workflowFuture) {
        // Mark fail nodes as MARK
        for (Node failNode : node.getFailNodes()) {
            synchronized (failNode) {
                if (!failNode.getStatus().executed()) {
                    failNode.setStatus(NodeStatusEnum.MARK);
                }
            }
        }
        
        // Trigger next nodes
        triggerNextNodes(node.getNextNodes(), variablePool, callback, activeTasks, workflowFuture);
    }

    private void executeFailedCondition(Node node, WorkflowContextStore variablePool, WorkflowMsgCallback callback, AtomicInteger activeTasks, CompletableFuture<Void> workflowFuture) {
        // Mark next nodes as MARK
        for (Node nextNode : node.getNextNodes()) {
            synchronized (nextNode) {
                if (!nextNode.getStatus().executed()) {
                    nextNode.setStatus(NodeStatusEnum.MARK);
                }
            }
        }

        // Trigger fail nodes
        triggerNextNodes(node.getFailNodes(), variablePool, callback, activeTasks, workflowFuture);
    }

    private void triggerNextNodes(List<Node> nextNodes, WorkflowContextStore variablePool, WorkflowMsgCallback callback, AtomicInteger activeTasks, CompletableFuture<Void> workflowFuture) {
        for (Node nextNode : nextNodes) {
            activeTasks.incrementAndGet();
            executorService.submit(TtlRunnable.get(() -> 
                executeNode(nextNode, variablePool, callback, activeTasks, workflowFuture)
            ));
        }
    }

    // Helper methods (copied from DagWorkflowEngine)
    private void initializeStartNodeInputs(Node startNode, WorkflowContextStore variablePool, Map<String, Object> inputs) {
        for (Map.Entry<String, Object> entry : inputs.entrySet()) {
            variablePool.set(startNode.getId(), entry.getKey(), entry.getValue());
        }
    }

    private void verifyWorkflow(WorkflowDSL workflowDSL) {
        topologyValidator.validate(workflowDSL);
        for (Node node : workflowDSL.getNodes()) {
            if (nodeExecutors.get(node.getNodeType()) == null) {
                throw new IllegalStateException("Invalid workflow DSL: executor not found");
            }
        }
    }

    /**
     * Normalize unreachable MARK nodes to SKIP after workflow execution.
     */
    private void normalizeMarkNodes(WorkflowDSL workflowDSL) {
        for (Node node : workflowDSL.getNodes()) {
            if (node.getStatus() == NodeStatusEnum.MARK) {
                node.setStatus(NodeStatusEnum.SKIP);
            }
        }
    }
}
