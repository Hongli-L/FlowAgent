package com.flowagent.engine.node.impl;

import com.flowagent.common.enums.NodeExecStatusEnum;
import com.flowagent.common.exception.ErrorCode;
import com.flowagent.common.exception.NodeCustomException;
import com.flowagent.engine.agent.ReActLoop;
import com.flowagent.engine.constants.NodeTypeEnum;
import com.flowagent.engine.context.EngineContextHolder;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.engine.dsl.VariableTemplateRender;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.engine.dsl.model.NodeData;
import com.flowagent.engine.integration.model.ModelServiceClient;
import com.flowagent.engine.node.AbstractNodeHandler;
import com.flowagent.engine.node.WorkflowNodeHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent node: agentic orchestration inside a visual DAG.
 *
 * <p>Unlike a standalone ReAct agent, this node's action space is the workflow's own
 * nodes (TOOL / LLM / branch). Given a goal, it runs a ReAct loop ({@link ReActLoop})
 * that asks the LLM which referenced node to call and with what arguments, then invokes
 * that node through the engine's executor map. The loop emits a {@code workflow_step}
 * SSE event per iteration so the reasoning trace is fully observable.
 *
 * <p>Configuration (nodeParam):
 * <ul>
 *   <li>{@code goal} - objective, supports {@code {{var}}} rendering from inputs</li>
 *   <li>{@code modelId} - model used for the reasoning loop</li>
 *   <li>{@code systemTemplate} - optional system prompt for the agent</li>
 *   <li>{@code maxIter} - reasoning budget (default 5)</li>
 *   <li>{@code toolNodeIds} - optional allow-list of referenced node ids; omitted = all eligible nodes</li>
 * </ul>
 */
@Slf4j
@Component
public class AgentNodeHandler extends AbstractNodeHandler {

    private final ModelServiceClient modelServiceClient;

    public AgentNodeHandler(ModelServiceClient modelServiceClient) {
        this.modelServiceClient = modelServiceClient;
    }

    @Override
    public NodeTypeEnum getNodeType() {
        return NodeTypeEnum.AGENT;
    }

    @Override
    protected NodeRunResult executeNode(NodeState nodeState, Map<String, Object> inputs) throws Exception {
        Node node = nodeState.node();
        Map<String, Object> nodeParam = node.getData().getNodeParam();
        if (nodeParam == null) {
            throw new NodeCustomException(ErrorCode.INVALID_NODE_CONFIGURATION,
                    "Missing nodeParam in agent node: " + node.getId());
        }

        String goal = renderGoal(nodeParam, inputs);
        if (goal == null || goal.isBlank()) {
            throw new NodeCustomException(ErrorCode.INVALID_NODE_CONFIGURATION,
                    "Agent node requires a non-empty goal: " + node.getId());
        }

        String modelId = nodeParam.get("modelId") == null ? null : String.valueOf(nodeParam.get("modelId"));
        String systemTemplate = nodeParam.get("systemTemplate") == null ? null : String.valueOf(nodeParam.get("systemTemplate"));
        int maxIter = parseMaxIter(nodeParam);

        List<ReActLoop.ToolSpec> tools = resolveToolSpecs(node);

        ReActLoop loop = new ReActLoop(
                node.getId(), goal, modelServiceClient, modelId, systemTemplate, tools,
                nodeState.variablePool(), nodeState.callback(), maxIter,
                (toolNodeId, args) -> invokeTool(toolNodeId, args, nodeState));
        ReActLoop.ReActResult result = loop.run();

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("output", result.answer());
        outputs.put("iterations", result.iterations());

        NodeRunResult runResult = new NodeRunResult();
        runResult.setInputs(inputs);
        runResult.setOutputs(outputs);
        runResult.setRawOutput(result.answer());
        runResult.setStatus(NodeExecStatusEnum.SUCCESS);
        return runResult;
    }

    private String renderGoal(Map<String, Object> nodeParam, Map<String, Object> inputs) {
        Object goal = nodeParam.get("goal");
        if (goal == null) {
            return null;
        }
        return VariableTemplateRender.render(String.valueOf(goal), inputs);
    }

    private int parseMaxIter(Map<String, Object> nodeParam) {
        Object maxIter = nodeParam.get("maxIter");
        if (maxIter instanceof Number) {
            return ((Number) maxIter).intValue();
        }
        if (maxIter instanceof String) {
            try {
                return Integer.parseInt((String) maxIter);
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return 5;
    }

    private List<ReActLoop.ToolSpec> resolveToolSpecs(Node agentNode) {
        EngineContextHolder.EngineContext ctx = EngineContextHolder.get();
        if (ctx == null) {
            return List.of();
        }
        List<Node> allNodes = ctx.getWorkflowNodes();
        Map<NodeTypeEnum, WorkflowNodeHandler> executors = ctx.getNodeExecutors();
        if (allNodes == null) {
            return List.of();
        }
        List<String> allowed = extractToolNodeIds(agentNode.getData().getNodeParam());

        List<ReActLoop.ToolSpec> specs = new ArrayList<>();
        for (Node n : allNodes) {
            if (n == agentNode) {
                continue;
            }
            NodeTypeEnum type = n.getNodeType();
            if (type == NodeTypeEnum.START || type == NodeTypeEnum.END || type == NodeTypeEnum.AGENT) {
                continue;
            }
            if (allowed != null && !allowed.contains(n.getId())) {
                continue;
            }
            if (executors != null && executors.get(type) == null) {
                continue;
            }
            specs.add(new ReActLoop.ToolSpec(n.getId(), describeTool(n)));
        }
        return specs;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractToolNodeIds(Map<String, Object> nodeParam) {
        Object ids = nodeParam.get("toolNodeIds");
        if (ids instanceof List) {
            return ((List<?>) ids).stream().map(String::valueOf).collect(Collectors.toList());
        }
        return null;
    }

    private String describeTool(Node n) {
        Map<String, Object> np = n.getData().getNodeParam();
        if (np != null && np.get("description") != null) {
            return String.valueOf(np.get("description"));
        }
        NodeData data = n.getData();
        String alias = data.getNodeMeta() == null ? null : data.getNodeMeta().getAliasName();
        return alias == null ? n.getId() : alias;
    }

    private Map<String, Object> invokeTool(String toolNodeId, Map<String, Object> args, NodeState nodeState) {
        EngineContextHolder.EngineContext ctx = EngineContextHolder.get();
        if (ctx == null || ctx.getWorkflowNodes() == null || ctx.getNodeExecutors() == null) {
            throw new NodeCustomException(ErrorCode.NODE_RUN_ERROR,
                    "Agent context unavailable for tool invocation");
        }
        Node refNode = ctx.getWorkflowNodes().stream()
                .filter(n -> n.getId().equals(toolNodeId))
                .findFirst().orElse(null);
        if (refNode == null) {
            throw new NodeCustomException(ErrorCode.NODE_RUN_ERROR,
                    "Agent referenced unknown tool node: " + toolNodeId);
        }
        WorkflowNodeHandler handler = ctx.getNodeExecutors().get(refNode.getNodeType());
        if (handler == null) {
            throw new NodeCustomException(ErrorCode.NODE_RUN_ERROR,
                    "No executor for tool node type: " + refNode.getNodeType());
        }

        // Expose action arguments to the referenced node via the context store, so the
        // tool node can read them through {{agent::xxx.args.field}}.
        nodeState.variablePool().set(nodeState.node().getId(), "args", args == null ? Map.of() : args);

        try {
            NodeRunResult res = handler.execute(new NodeState(refNode, nodeState.variablePool(), nodeState.callback()));
            return res.getOutputs() == null ? Map.of() : res.getOutputs();
        } catch (NodeCustomException e) {
            throw e;
        } catch (Exception e) {
            throw new NodeCustomException(ErrorCode.NODE_RUN_ERROR,
                    "Tool node " + toolNodeId + " failed: " + e.getMessage());
        }
    }
}
