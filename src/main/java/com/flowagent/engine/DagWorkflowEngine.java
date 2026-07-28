package com.flowagent.engine;

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
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Workflow execution engine
 * Executes workflow nodes in sequential order based on edges
 */
@Slf4j
@Component
public class DagWorkflowEngine {

    private static final String TRIGGER_SOURCE_API = "API";

    private final Map<NodeTypeEnum, WorkflowNodeHandler> nodeExecutors;
    private final TopologyValidator topologyValidator;
    private final GraphBuilder graphBuilder;
    private final ExecutionHistoryService executionHistoryService;

    public DagWorkflowEngine(List<WorkflowNodeHandler> executors, TopologyValidator topologyValidator,
                             GraphBuilder graphBuilder, ExecutionHistoryService executionHistoryService) {
        this.topologyValidator = topologyValidator;
        this.graphBuilder = graphBuilder;
        this.executionHistoryService = executionHistoryService;
        this.nodeExecutors = new HashMap<>();
        for (WorkflowNodeHandler executor : executors) {
            this.nodeExecutors.put(executor.getNodeType(), executor);
        }
        log.info("Registered {} node executors: {}", nodeExecutors.size(), nodeExecutors.keySet());
    }

    /**
     * Execute a workflow
     *
     * @param workflowDSL workflow definition
     * @param inputs      initial input values (from user)
     * @param callback    stream callback for SSE output
     * @throws Exception if execution fails
     */
    public void execute(WorkflowDSL workflowDSL, WorkflowContextStore variablePool, Map<String, Object> inputs, FlowEventCallback callback) throws Exception {
        log.info("Starting workflow execution with {} nodes", workflowDSL.getNodes().size());

        // Pre-execution validation
        verifyWorkflow(workflowDSL);

        // Clear context store for fresh execution
        variablePool.clear();

        // Create workflow callback handler
        Queue<ChatCallBackStreamResult> orderStreamResultQ = new LinkedBlockingQueue<>();
        BlockingQueue<LLMGenerate> streamQueue = new LinkedBlockingQueue<>();

        Node endNode = workflowDSL.getNodes().stream().filter(s -> s.getNodeType() == NodeTypeEnum.END).findFirst().get();
        String sid = FlowUtil.genWorkflowId(workflowDSL.getFlowId());
        WorkflowMsgCallback workflowCallback = new WorkflowMsgCallback(
                sid,
                callback,
                Objects.equals(endNode.getData().getNodeParam().get("outputMode"), 1) ? EndNodeOutputModeEnum.VARIABLE_MODE : EndNodeOutputModeEnum.DIRECT_MODE,
                streamQueue,
                orderStreamResultQ
        );


        // Initialize execution context
        EngineContextHolder.initContext(workflowDSL.getFlowId(), workflowDSL.getUuid(), workflowCallback);
        Long executionId = executionHistoryService.createExecution(workflowDSL.getFlowId(), TRIGGER_SOURCE_API);
        EngineContextHolder.get().setExecutionId(executionId);

        // Emit workflow start event
        workflowCallback.onWorkflowStart();

        try {
            // Build execution chain from start node
            Node startNode = graphBuilder.build(workflowDSL).getStartNode();
            // Initialize start node inputs
            initializeStartNodeInputs(startNode, variablePool, inputs);

            // Execute orchestrated workflow nodes
            executeNode(startNode, variablePool, workflowCallback);

            // Normalize unreachable MARK nodes to SKIP after execution
            normalizeMarkNodes(workflowDSL);

            log.info("Workflow: {} execution completed successfully", sid);
            // Emit workflow end event
            workflowCallback.onWorkflowEnd(new NodeRunResult());
            executionHistoryService.completeExecution(executionId, ExecutionStatusEnum.SUCCESS.name());
        } catch (Exception e) {
            // Emit workflow error-end event
            workflowCallback.onWorkflowEnd(new NodeRunResult());
            executionHistoryService.completeExecution(executionId, ExecutionStatusEnum.FAILED.name());
            throw e;
        } finally {
            // Drain all pending messages
            workflowCallback.finished();
            // Remove execution context
            EngineContextHolder.remove();
        }
    }

    /**
     * Initialize start node inputs with user-provided values
     */
    private void initializeStartNodeInputs(Node startNode, WorkflowContextStore variablePool, Map<String, Object> inputs) {
        for (Map.Entry<String, Object> entry : inputs.entrySet()) {
            variablePool.set(startNode.getId(), entry.getKey(), entry.getValue());
            if (log.isDebugEnabled()) {
                log.debug("Initialized start node input: {}.{} = {}", startNode.getId(), entry.getKey(), entry.getValue());
            }
        }
    }

    private void verifyWorkflow(WorkflowDSL workflowDSL) {
        topologyValidator.validate(workflowDSL);
        for (Node node : workflowDSL.getNodes()) {
            NodeTypeEnum nodeType = node.getNodeType();
            if (nodeExecutors.get(nodeType) == null) {
                throw new IllegalStateException("Invalid workflow DSL: no executor found for node type: " + nodeType);
            }
        }
    }

    /**
     * Execute a single node
     */
    private void executeNode(Node node, WorkflowContextStore variablePool, WorkflowMsgCallback callback) throws Exception {
        if (node.getStatus().executed()) {
            // Currently supports single execution; loop execution can be added via executedCount tracking
            // Already executed: skip
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("prepare to executeNode: {}", node.getId());
        }

        // 1. Pre-execution validation: all predecessors must have completed before this node can run
        if (!CollectionUtils.isEmpty(node.getPreNodes())) {
            for (Node preNode : node.getPreNodes()) {
                if (!preNode.getStatus().executed()) {
                    executeNode(preNode, variablePool, callback);
                }
            }
        }

        // 2. If MARK, determine whether node can execute
        // Example: a MARK node has predecessors A and B; A success routes to this node, B error also routes to this node via fail branch
        // If B succeeds, it marks this node SKIP; A success still arrives here, but SKIP overrides execution
        // Rule: check all predecessors; if this node is on a predecessor execution branch, run it; otherwise mark SKIP
        if (node.getStatus() == NodeStatusEnum.MARK) {
            boolean canExecute = false;
            for (Node preNode : node.getPreNodes()) {
                if (preNode.getStatus() == NodeStatusEnum.SKIP) {
                    continue;
                } else if (preNode.getStatus() == NodeStatusEnum.ERROR) {
                    if (preNode.getFailNodes().contains(node)) {
                        // On current branch: execute normally
                        canExecute = true;
                        break;
                    }
                } else if (preNode.getStatus() == NodeStatusEnum.SUCCESS) {
                    if (preNode.getNextNodes().contains(node)) {
                        // On current branch: execute normally
                        canExecute = true;
                        break;
                    }
                }
            }
            if (!canExecute) {
                // Node not executable: mark SKIP
                node.setStatus(NodeStatusEnum.SKIP);
                return;
            }
        }


        // 3. Execute current node
        NodeTypeEnum nodeType = node.getNodeType();
        WorkflowNodeHandler executor = nodeExecutors.get(nodeType);
        if (executor == null) {
            throw new IllegalStateException("No executor found for node type: " + nodeType);
        }

        node.setStatus(NodeStatusEnum.RUNNING);
        NodeExecStatusEnum execStatus;
        while (true) {
            NodeRunResult res = executor.execute(new NodeState(node, variablePool, callback));
            execStatus = res.getStatus();
            if (execStatus != NodeExecStatusEnum.ERR_RETRY) {
                break;
            }
        }

        // 4. Node complete: propagate to downstream nodes
        if (execStatus == NodeExecStatusEnum.ERR_INTERUPT) {
            // Interrupt: halt entire workflow
            node.setStatus(NodeStatusEnum.ERROR);
            throw new NodeCustomException(ErrorCode.INTERRUPTED_ERROR);
        } else if (execStatus == NodeExecStatusEnum.ERR_FAIL_CONDITION) {
            // Node failed: execute error branch
            node.setStatus(NodeStatusEnum.ERROR);
            executeFailedCondition(node, variablePool, callback);
        } else if (execStatus == NodeExecStatusEnum.ERR_CODE_MSG) {
            // Node failed but still follows normal branch (ERR_CODE strategy)
            node.setStatus(NodeStatusEnum.ERROR);
            executeNormalCondition(node, variablePool, callback);
        } else {
            // Node succeeded
            node.setStatus(NodeStatusEnum.SUCCESS);
            executeNormalCondition(node, variablePool, callback);
        }
    }

    private void executeNormalCondition(Node node, WorkflowContextStore variablePool, WorkflowMsgCallback callback) throws Exception {
        // Mark fail-branch nodes as MARK (pending skip evaluation)
        for (Node failNode : node.getFailNodes()) {
            if (!failNode.getStatus().executed()) {
                failNode.setStatus(NodeStatusEnum.MARK);
            }
        }
        // Note: a normal-branch node may also reach this fail-branch node; MARK allows re-evaluation

        // Success path or no error-branch scenario
        for (Node nextNode : node.getNextNodes()) {
            executeNode(nextNode, variablePool, callback);
        }
    }

    private void executeFailedCondition(Node node, WorkflowContextStore variablePool, WorkflowMsgCallback callback) throws Exception {
        for (Node nextNode : node.getNextNodes()) {
            if (!nextNode.getStatus().executed()) {
                nextNode.setStatus(NodeStatusEnum.MARK);
            }
        }

        // Error path
        for (Node failNode : node.getFailNodes()) {
            executeNode(failNode, variablePool, callback);
        }
    }

    /**
     * Normalize unreachable MARK nodes to SKIP after workflow execution.
     * Nodes in MARK state were tentatively marked for skip evaluation but never visited;
     * they are definitively unreachable and should be normalized to SKIP.
     */
    private void normalizeMarkNodes(WorkflowDSL workflowDSL) {
        for (Node node : workflowDSL.getNodes()) {
            if (node.getStatus() == NodeStatusEnum.MARK) {
                node.setStatus(NodeStatusEnum.SKIP);
                if (log.isDebugEnabled()) {
                    log.debug("Normalized unreachable MARK node {} to SKIP", node.getId());
                }
            }
        }
    }
}
