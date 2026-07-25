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
import java.util.List;
import java.util.Map;

/**
 * Multi-way branch node: evaluates an ordered list of conditions and routes to the
 * first branch whose condition is satisfied ("first-match wins").
 *
 * <p>Routing contract (reuses the engine's existing branch convention):
 * <ul>
 *   <li>a condition matches - reports SUCCESS, engine follows the normal (non-fail) edge;
 *       the matched {@code branch} key is written to the variable pool</li>
 *   <li>no condition matches - reports ERR_FAIL_CONDITION, engine follows the fail edge
 *       (the {@code defaultBranch}, or {@code "default"} when unspecified)</li>
 * </ul>
 *
 * <p>The underlying DAG engine supports two outgoing edges per node (normal and fail).
 * ConditionSwitch implements "first-match wins" routing to the normal edge and "no-match"
 * routing to the default edge; the matched branch key is exposed to downstream nodes via
 * the variable pool for further inspection.
 */
@Slf4j
@Component
public class ConditionSwitchNodeHandler extends AbstractNodeHandler {

    @Override
    public NodeTypeEnum getNodeType() {
        return NodeTypeEnum.CONDITION_SWITCH;
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
            storeOutputs(node, result.getOutputs(), variablePool);
            nodeState.callback().onNodeEnd(node.getId(), alias, result);
            return result;
        } catch (Exception e) {
            log.error("ConditionSwitch node {} execution failed: {}", node.getId(), e.getMessage(), e);
            NodeRunResult result = new NodeRunResult();
            result.setInputs(inputs);
            result.setError(new NodeCustomException(ErrorCode.NODE_RUN_ERROR, e.getMessage()));
            result.setStatus(NodeExecStatusEnum.ERR_INTERUPT);
            nodeState.callback().onNodeInterrupt(FlowUtil.genInterruptEventId(), Map.of(),
                    node.getId(), alias, result.getError().getCode(), "interrupt", false);
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    protected NodeRunResult executeNode(NodeState nodeState, Map<String, Object> inputs) {
        Node node = nodeState.node();
        Map<String, Object> outputs = new HashMap<>();
        Object conditionsObj = node.getData().getNodeParam().get("conditions");
        Object defaultObj = node.getData().getNodeParam().get("defaultBranch");
        String defaultBranch = defaultObj == null ? "default" : String.valueOf(defaultObj);

        String matchedBranch = null;
        if (conditionsObj instanceof List<?> conditions) {
            for (Object item : conditions) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<String, Object> entry = (Map<String, Object>) item;
                Object exprObj = entry.get("condition");
                Object branchObj = entry.get("branch");
                String expr = exprObj == null ? "" : String.valueOf(exprObj);
                String branch = branchObj == null ? null : String.valueOf(branchObj);
                if (ConditionEvaluator.evaluate(expr, nodeState.variablePool())) {
                    matchedBranch = branch;
                    break;
                }
            }
        }

        boolean matched = matchedBranch != null;
        outputs.put("matched", matched);
        outputs.put("branch", matched ? matchedBranch : defaultBranch);
        if (matchedBranch != null) {
            outputs.put("matchedBranch", matchedBranch);
        }
        log.info("ConditionSwitch node {} matched={}, branch={}", node.getId(), matched, outputs.get("branch"));

        NodeRunResult result = new NodeRunResult();
        result.setInputs(inputs);
        result.setOutputs(outputs);
        result.setStatus(matched ? NodeExecStatusEnum.SUCCESS : NodeExecStatusEnum.ERR_FAIL_CONDITION);
        return result;
    }
}
