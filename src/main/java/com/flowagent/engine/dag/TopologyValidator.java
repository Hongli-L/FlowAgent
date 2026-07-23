package com.flowagent.engine.dag;

import com.flowagent.common.exception.ErrorCode;
import com.flowagent.common.exception.NodeCustomException;
import com.flowagent.engine.constants.NodeTypeEnum;
import com.flowagent.engine.dsl.model.WorkflowDSL;
import com.flowagent.engine.dsl.model.Edge;
import com.flowagent.engine.dsl.model.Node;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

@Component
public class TopologyValidator {

    public void validate(WorkflowDSL workflowDSL) {
        if (workflowDSL == null) {
            throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Workflow DSL is null");
        }
        if (CollectionUtils.isEmpty(workflowDSL.getNodes()) || CollectionUtils.isEmpty(workflowDSL.getEdges())) {
            throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Invalid workflow DSL: missing nodes or edges");
        }

        Set<String> nodeIds = new HashSet<>();
        boolean hasStartNode = false;
        boolean hasEndNode = false;

        for (Node node : workflowDSL.getNodes()) {
            if (node.getId() == null || node.getId().isBlank()) {
                throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Invalid workflow DSL: node id is empty");
            }
            if (!nodeIds.add(node.getId())) {
                throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Invalid workflow DSL: duplicate node id: " + node.getId());
            }
            NodeTypeEnum nodeType = node.getNodeType();
            if (nodeType == null) {
                throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Invalid workflow DSL: node type is null");
            }
            if (nodeType == NodeTypeEnum.START) {
                hasStartNode = true;
            } else if (nodeType == NodeTypeEnum.END) {
                hasEndNode = true;
            }
        }

        if (!hasStartNode || !hasEndNode) {
            throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Invalid workflow DSL: missing start or end node");
        }

        for (Edge edge : workflowDSL.getEdges()) {
            String source = edge.getSource();
            String target = edge.getTarget();
            if (source == null || source.isBlank() || target == null || target.isBlank()) {
                throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Invalid workflow DSL: edge endpoint is empty");
            }
            if (!nodeIds.contains(source)) {
                throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Invalid workflow DSL: unknown source node: " + source);
            }
            if (!nodeIds.contains(target)) {
                throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Invalid workflow DSL: unknown target node: " + target);
            }
        }

        if (hasCycle(workflowDSL)) {
            throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Invalid workflow DSL: cycle detected");
        }
    }

    boolean hasCycle(WorkflowDSL workflowDSL) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adjList = new HashMap<>();

        for (Node node : workflowDSL.getNodes()) {
            inDegree.put(node.getId(), 0);
            adjList.put(node.getId(), new ArrayList<>());
        }

        for (Edge edge : workflowDSL.getEdges()) {
            String source = edge.getSource();
            String target = edge.getTarget();
            adjList.get(source).add(target);
            inDegree.put(target, inDegree.get(target) + 1);
        }

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        int processedCount = 0;
        while (!queue.isEmpty()) {
            String current = queue.poll();
            processedCount++;
            for (String next : adjList.get(current)) {
                int degree = inDegree.get(next) - 1;
                inDegree.put(next, degree);
                if (degree == 0) {
                    queue.offer(next);
                }
            }
        }

        return processedCount != workflowDSL.getNodes().size();
    }
}
