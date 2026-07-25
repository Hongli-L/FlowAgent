package com.flowagent.engine.node.impl;

import com.flowagent.common.exception.ErrorCode;
import com.flowagent.common.enums.NodeExecStatusEnum;
import com.flowagent.common.exception.NodeCustomException;
import com.flowagent.engine.constants.NodeTypeEnum;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.engine.dsl.ConditionEvaluator;
import com.flowagent.engine.node.AbstractNodeHandler;
import com.flowagent.engine.WorkflowContextStore;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.engine.node.callback.WorkflowMsgCallback;
import com.flowagent.engine.util.FlowUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Conditional branch node: evaluates a single boolean expression.
 *
 * <p>Routing contract (reuses the engine's existing branch convention):
 * <ul>
 *   <li>condition TRUE  - reports SUCCESS, engine follows the normal (non-fail) outgoing edge</li>
 *   <li>condition FALSE - reports ERR_FAIL_CONDITION, engine follows the fail outgoing edge
 *       (the edge whose {@code sourceHandle} starts with {@code fail_})</li>
 * </ul>
 *
 * <p>The decision is also written to the variable pool
 * ({@code branch = "true"|"false"}, {@code matched = true|false}) so downstream nodes can inspect it.
 *
 * <p>Branch nodes bypass the retry/timeout template ({@code AbstractNodeHandler.execute}) and report
 * the status directly, because the fail-branch status must survive untouched for engine routing.
 */
@Slf4j
@Component
public class IfElseNodeHandler extends AbstractNodeHandler {

    @Override
    public NodeTypeEnum getNodeType() {
        return NodeTypeEnum.IF_ELSE;
    }

    @Override
    public NodeRunResult execute(NodeState nodeState) {
        Node node = nodeState.node();
        WorkflowContextStore variablePool = nodeState.variablePool();
        String alias = node.getData().getNodeMeta() != null
                ? node.getData().getNodeMeta().getAliasName() : node.getId();
        nodeState.callback().onNodeStart(0, node.getId(), alias);

        Map<String, Object> inputs = resolveInputs(node, variablePool);
        try {
            NodeRunResult result = executeNode(nodeState, inputs);
            // Store decision outputs for downstream nodes
            storeOutputs(node, result.getOutputs(), variablePool);
            // Report node end with the (possibly fail-branch) status untouched;
            // the engine routes on result.getStatus() (SUCCESS -> nextNodes, ERR_FAIL_CONDITION -> failNodes).
            nodeState.callback().onNodeEnd(node.getId(), alias, result);
            return result;
        } catch (Exception e) {
            log.error("IfElse node {} execution failed: {}", node.getId(), e.getMessage(), e);
            NodeRunResult result = new NodeRunResult();
            result.setInputs(inputs);
            result.setError(new NodeCustomException(ErrorCode.NODE_RUN_ERROR, e.getMessage()));
            result.setStatus(NodeExecStatusEnum.ERR_INTERUPT);
            nodeState.callback().onNodeInterrupt(FlowUtil.genInterruptEventId(), Map.of(),
                    node.getId(), alias, result.getError().getCode(), "interrupt", false);
            return result;
        }
    }

    @Override
    protected NodeRunResult executeNode(NodeState nodeState, Map<String, Object> inputs) {
        Node node = nodeState.node();
        Map<String, Object> outputs = new HashMap<>();
        Object condObj = node.getData().getNodeParam().get("condition");
        String condition = condObj == null ? "" : String.valueOf(condObj);
        boolean matched = ConditionEvaluator.evaluate(condition, nodeState.variablePool());
        outputs.put("condition", condition);
        outputs.put("branch", matched ? "true" : "false");
        outputs.put("matched", matched);
        log.info("IfElse node {} evaluated '{}' => {}", node.getId(), condition, matched);

        NodeRunResult result = new NodeRunResult();
        result.setInputs(inputs);
        result.setOutputs(outputs);
        result.setStatus(matched ? NodeExecStatusEnum.SUCCESS : NodeExecStatusEnum.ERR_FAIL_CONDITION);
        return result;
    }
}
