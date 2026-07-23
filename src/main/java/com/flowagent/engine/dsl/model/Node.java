package com.flowagent.engine.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowagent.common.enums.NodeStatusEnum;
import com.flowagent.engine.constants.NodeTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Workflow node with ID and data.
 * 
 * This class represents a single node in a workflow,
 * with a unique identifier, data configuration, and execution status.
 * 
 * @version 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Node {

    /**
     * Node ID in format: "node-type::sequenceId"
     * Examples: "node-start::001", "llm::002", "condition-switch::003", "node-end::004"
     */
    @JsonProperty("id")
    private String id;

    /**
     * Node data containing configuration and parameters
     */
    @JsonProperty("data")
    private NodeData data;

    private NodeStatusEnum status;

    /**
     * Predecessor nodes, the current node will only execute after all preceding nodes have completed
     */
    private List<Node> preNodes;

    /**
     * Successor nodes, executed after the current node completes successfully
     */
    private List<Node> nextNodes;

    /**
     * Fail-branch nodes, executed after the current node fails
     */
    private List<Node> failNodes;

    /**
     * Number of times this node has been executed
     */
    private AtomicInteger executedCount;

    /**
     * Extract node type from ID
     *
     * @return node type (e.g., START, END, LLM, CONDITION_SWITCH)
     */
    public NodeTypeEnum getNodeType() {
        if (id != null && id.contains("::")) {
            return NodeTypeEnum.fromValue(id.split("::")[0]);
        }
        return null;
    }

    public void init() {
        status = NodeStatusEnum.INIT;
        preNodes = new ArrayList<>();
        nextNodes = new ArrayList<>();
        failNodes = new ArrayList<>();
        executedCount = new AtomicInteger(0);
    }
}
