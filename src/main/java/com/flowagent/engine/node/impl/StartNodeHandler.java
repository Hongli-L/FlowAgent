package com.flowagent.engine.node.impl;

import com.flowagent.common.enums.NodeExecStatusEnum;
import com.flowagent.engine.constants.NodeTypeEnum;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.engine.node.AbstractNodeHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Start node executor
 * Simply passes through the initial inputs to outputs
 */
@Slf4j
@Component
public class StartNodeHandler extends AbstractNodeHandler {

    @Override
    public NodeTypeEnum getNodeType() {
        return NodeTypeEnum.START;
    }

    @Override
    protected NodeRunResult executeNode(NodeState nodeState, Map<String, Object> inputs) {
        Map<String, Object> outputs = new HashMap<>(inputs);

        NodeRunResult result = new NodeRunResult();
        result.setInputs(inputs);
        result.setOutputs(outputs);
        result.setStatus(NodeExecStatusEnum.SUCCESS);
        return result;
    }
}
