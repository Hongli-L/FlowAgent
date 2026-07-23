package com.flowagent.engine.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Node metadata.
 * 
 * This class represents the metadata associated with a single node in a workflow,
 * including the node type and human-readable alias name.
 *
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NodeMeta {
    
    /**
     * Type of the node (e.g., "node-start", "node-llm", "node-if-else", "node-end")
     */
    @JsonProperty("nodeType")
    private String nodeType;
    
    /**
     * Human-readable alias name
     */
    @JsonProperty("aliasName")
    private String aliasName;
}
