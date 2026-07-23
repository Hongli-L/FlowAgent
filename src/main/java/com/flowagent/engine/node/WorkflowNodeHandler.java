package com.flowagent.engine.node;

import com.flowagent.engine.constants.NodeTypeEnum;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;

/**
 * Base interface for all node executors
 * Each node type (Start, LLM, IfElse, End) must implement this interface
 */
public interface WorkflowNodeHandler {

    /**
     * Execute the node logic
     *
     * @param nodeState workflow nodeState
     * @throws Exception if execution fails
     */
    NodeRunResult execute(NodeState nodeState) throws Exception;

    /**
     * Get the node type this executor handles
     *
     * @return node type string (e.g., "node-start", "node-llm", "node-if-else", "node-end")
     */
    NodeTypeEnum getNodeType();
}
