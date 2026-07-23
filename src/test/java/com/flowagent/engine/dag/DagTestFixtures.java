package com.flowagent.engine.dag;

import com.flowagent.engine.dsl.model.WorkflowDSL;
import com.flowagent.engine.dsl.model.Edge;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.engine.dsl.model.NodeData;

import java.util.ArrayList;
import java.util.List;

public final class DagTestFixtures {

    private DagTestFixtures() {
    }

    public static Node node(String id) {
        Node node = new Node();
        node.setId(id);
        node.setData(new NodeData());
        return node;
    }

    public static Edge edge(String source, String target) {
        return edge(source, target, null);
    }

    public static Edge edge(String source, String target, String sourceHandle) {
        Edge edge = new Edge();
        edge.setSourceNodeId(source);
        edge.setTargetNodeId(target);
        edge.setSourceHandle(sourceHandle);
        return edge;
    }

    public static WorkflowDSL workflow(Node... nodes) {
        WorkflowDSL dsl = new WorkflowDSL();
        dsl.setNodes(List.of(nodes));
        dsl.setEdges(new ArrayList<>());
        return dsl;
    }

    public static WorkflowDSL workflow(List<Node> nodes, List<Edge> edges) {
        WorkflowDSL dsl = new WorkflowDSL();
        dsl.setNodes(nodes);
        dsl.setEdges(edges);
        return dsl;
    }
}
