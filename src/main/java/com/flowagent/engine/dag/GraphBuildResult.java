package com.flowagent.engine.dag;

import com.flowagent.engine.dsl.model.Node;
import lombok.Data;

import java.util.Map;

@Data
public class GraphBuildResult {

    private Node startNode;

    private Map<String, Node> nodeMap;
}
