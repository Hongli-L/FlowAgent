package com.flowagent.engine.core;

import com.flowagent.common.enums.EndNodeOutputModeEnum;
import com.flowagent.common.enums.NodeExecStatusEnum;
import com.flowagent.common.enums.NodeStatusEnum;
import com.flowagent.common.exception.ErrorCode;
import com.flowagent.common.exception.NodeCustomException;
import com.flowagent.engine.WorkflowContextStore;
import com.flowagent.engine.constants.NodeTypeEnum;
import com.flowagent.engine.context.EngineContextHolder;
import com.flowagent.engine.core.langgraph.WorkflowGraphState;
import com.flowagent.engine.dag.GraphBuilder;
import com.flowagent.engine.dag.GraphBuildResult;
import com.flowagent.engine.dag.TopologyValidator;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.engine.domain.callbacks.ChatCallBackStreamResult;
import com.flowagent.engine.domain.callbacks.LLMGenerate;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.engine.dsl.model.WorkflowDSL;
import com.flowagent.engine.node.WorkflowNodeHandler;
import com.flowagent.engine.node.FlowEventCallback;
import com.flowagent.engine.node.callback.WorkflowMsgCallback;
import com.flowagent.engine.util.FlowUtil;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * LangGraph4j-backed workflow engine adapter.
 * Converts FlowAgent DSL into a StateGraph and executes via existing WorkflowNodeHandlers.
 */
@Slf4j
@Component
public class LangGraphEngine implements WorkflowExecutionEngine {

    private final Map<NodeTypeEnum, WorkflowNodeHandler> nodeExecutors;
    private final TopologyValidator topologyValidator;
    private final GraphBuilder graphBuilder;

    public LangGraphEngine(List<WorkflowNodeHandler> executors,
                           TopologyValidator topologyValidator,
                           GraphBuilder graphBuilder) {
        this.topologyValidator = topologyValidator;
        this.graphBuilder = graphBuilder;
        this.nodeExecutors = new HashMap<>();
        for (WorkflowNodeHandler executor : executors) {
            this.nodeExecutors.put(executor.getNodeType(), executor);
        }
        log.info("LangGraphEngine registered {} node executors", nodeExecutors.size());
    }

    @Override
    public EngineType type() {
        return EngineType.LANGGRAPH;
    }

    @Override
    public void execute(WorkflowDSL workflowDSL,
                        WorkflowContextStore variablePool,
                        Map<String, Object> inputs,
                        FlowEventCallback callback) throws Exception {
        log.info("LangGraphEngine starting execution with {} nodes", workflowDSL.getNodes().size());

        topologyValidator.validate(workflowDSL);
        for (Node node : workflowDSL.getNodes()) {
            if (nodeExecutors.get(node.getNodeType()) == null) {
                throw new IllegalStateException("No executor found for node type: " + node.getNodeType());
            }
        }

        variablePool.clear();
        GraphBuildResult graphBuildResult = graphBuilder.build(workflowDSL);
        Node startNode = graphBuildResult.getStartNode();
        initializeStartNodeInputs(startNode, variablePool, inputs);

        Queue<ChatCallBackStreamResult> orderStreamResultQ = new LinkedBlockingQueue<>();
        BlockingQueue<LLMGenerate> streamQueue = new LinkedBlockingQueue<>();
        Node endNode = workflowDSL.getNodes().stream()
                .filter(n -> n.getNodeType() == NodeTypeEnum.END)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No end node found"));

        String sid = FlowUtil.genWorkflowId(workflowDSL.getFlowId());
        WorkflowMsgCallback workflowCallback = new WorkflowMsgCallback(
                sid,
                callback,
                Objects.equals(endNode.getData().getNodeParam().get("outputMode"), 1)
                        ? EndNodeOutputModeEnum.VARIABLE_MODE
                        : EndNodeOutputModeEnum.DIRECT_MODE,
                streamQueue,
                orderStreamResultQ
        );

        EngineContextHolder.initContext(workflowDSL.getFlowId(), workflowDSL.getUuid(), workflowCallback);
        workflowCallback.onWorkflowStart();

        try {
            CompiledGraph<WorkflowGraphState> compiled = buildCompiledGraph(
                    graphBuildResult, variablePool, workflowCallback);
            Map<String, Object> initState = new HashMap<>();
            initState.put(WorkflowGraphState.LAST_NODE_ID, "");
            initState.put(WorkflowGraphState.LAST_STATUS, "");
            initState.put(WorkflowGraphState.OUTPUTS, new HashMap<String, Object>());

            for (var ignored : compiled.stream(initState)) {
                // consume stream; node side-effects update WorkflowContextStore / callbacks
            }

            // Normalize unreachable MARK nodes to SKIP after execution
            normalizeMarkNodes(workflowDSL);

            log.info("LangGraphEngine workflow {} completed", sid);
            workflowCallback.onWorkflowEnd(new NodeRunResult());
        } catch (Exception e) {
            workflowCallback.onWorkflowEnd(new NodeRunResult());
            throw e;
        } finally {
            workflowCallback.finished();
            EngineContextHolder.remove();
        }
    }

    private CompiledGraph<WorkflowGraphState> buildCompiledGraph(GraphBuildResult graphBuildResult,
                                                                 WorkflowContextStore variablePool,
                                                                 WorkflowMsgCallback workflowCallback) throws Exception {
        StateGraph<WorkflowGraphState> stateGraph =
                new StateGraph<>(WorkflowGraphState.SCHEMA, WorkflowGraphState::new);

        Map<String, Node> nodeMap = graphBuildResult.getNodeMap();
        for (Node node : nodeMap.values()) {
            String nodeId = node.getId();
            stateGraph.addNode(nodeId, node_async(state -> runNode(node, variablePool, workflowCallback)));
        }

        Node startNode = graphBuildResult.getStartNode();
        stateGraph.addEdge(START, startNode.getId());

        for (Node node : nodeMap.values()) {
            if (node.getNodeType() == NodeTypeEnum.END) {
                stateGraph.addEdge(node.getId(), END);
                continue;
            }
            if (CollectionUtils.isEmpty(node.getNextNodes()) && CollectionUtils.isEmpty(node.getFailNodes())) {
                stateGraph.addEdge(node.getId(), END);
                continue;
            }

            Map<String, String> destinations = new HashMap<>();
            for (Node next : node.getNextNodes()) {
                destinations.put(next.getId(), next.getId());
            }
            for (Node fail : node.getFailNodes()) {
                destinations.put(fail.getId(), fail.getId());
            }
            destinations.put(END, END);

            stateGraph.addConditionalEdges(node.getId(), edge_async(state -> routeNext(node, state)), destinations);
        }

        return stateGraph.compile();
    }

    private String routeNext(Node node, WorkflowGraphState state) {
        boolean failed = NodeStatusEnum.ERROR.name().equals(state.lastStatus());
        List<Node> candidates = failed && !CollectionUtils.isEmpty(node.getFailNodes())
                ? node.getFailNodes()
                : node.getNextNodes();
        if (CollectionUtils.isEmpty(candidates)) {
            return END;
        }
        return candidates.get(0).getId();
    }

    private Map<String, Object> runNode(Node node,
                                        WorkflowContextStore variablePool,
                                        WorkflowMsgCallback callback) throws Exception {
        if (node.getStatus().executed()) {
            return Map.of(
                    WorkflowGraphState.LAST_NODE_ID, node.getId(),
                    WorkflowGraphState.LAST_STATUS, node.getStatus().name()
            );
        }

        if (!CollectionUtils.isEmpty(node.getPreNodes())) {
            for (Node preNode : node.getPreNodes()) {
                if (!preNode.getStatus().executed()) {
                    throw new IllegalStateException("Predecessor not executed: " + preNode.getId());
                }
            }
        }

        if (node.getStatus() == NodeStatusEnum.MARK) {
            boolean canExecute = false;
            for (Node preNode : node.getPreNodes()) {
                if (preNode.getStatus() == NodeStatusEnum.SKIP) {
                    continue;
                }
                if (preNode.getStatus() == NodeStatusEnum.ERROR && preNode.getFailNodes().contains(node)) {
                    canExecute = true;
                    break;
                }
                if (preNode.getStatus() == NodeStatusEnum.SUCCESS && preNode.getNextNodes().contains(node)) {
                    canExecute = true;
                    break;
                }
            }
            if (!canExecute) {
                node.setStatus(NodeStatusEnum.SKIP);
                return Map.of(
                        WorkflowGraphState.LAST_NODE_ID, node.getId(),
                        WorkflowGraphState.LAST_STATUS, NodeStatusEnum.SKIP.name()
                );
            }
        }

        WorkflowNodeHandler executor = nodeExecutors.get(node.getNodeType());
        if (executor == null) {
            throw new IllegalStateException("No executor found for node type: " + node.getNodeType());
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

        if (execStatus == NodeExecStatusEnum.ERR_INTERUPT) {
            node.setStatus(NodeStatusEnum.ERROR);
            throw new NodeCustomException(ErrorCode.INTERRUPTED_ERROR);
        }
        if (execStatus == NodeExecStatusEnum.ERR_FAIL_CONDITION) {
            node.setStatus(NodeStatusEnum.ERROR);
            markOppositeBranch(node.getNextNodes());
        } else if (execStatus == NodeExecStatusEnum.ERR_CODE_MSG) {
            node.setStatus(NodeStatusEnum.ERROR);
            markOppositeBranch(node.getFailNodes());
        } else {
            node.setStatus(NodeStatusEnum.SUCCESS);
            markOppositeBranch(node.getFailNodes());
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put(WorkflowGraphState.LAST_NODE_ID, node.getId());
        updates.put(WorkflowGraphState.LAST_STATUS, node.getStatus().name());
        updates.put(WorkflowGraphState.OUTPUTS, new HashMap<>(variablePool.get(node.getId())));
        return updates;
    }

    private void markOppositeBranch(List<Node> nodes) {
        for (Node n : nodes) {
            if (!n.getStatus().executed()) {
                n.setStatus(NodeStatusEnum.MARK);
            }
        }
    }

    private void initializeStartNodeInputs(Node startNode, WorkflowContextStore variablePool, Map<String, Object> inputs) {
        for (Map.Entry<String, Object> entry : inputs.entrySet()) {
            variablePool.set(startNode.getId(), entry.getKey(), entry.getValue());
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
            }
        }
    }
}
