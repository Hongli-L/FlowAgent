package com.flowagent.engine.domain;

import com.flowagent.engine.WorkflowContextStore;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.engine.node.callback.WorkflowMsgCallback;

/**
 * State passed between nodes during execution.
 */
public record NodeState(Node node, WorkflowContextStore variablePool, WorkflowMsgCallback callback) {
}
