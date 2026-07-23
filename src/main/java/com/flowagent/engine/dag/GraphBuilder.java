package com.flowagent.engine.dag;

import com.flowagent.common.exception.ErrorCode;
import com.flowagent.common.exception.NodeCustomException;
import com.flowagent.engine.constants.NodeTypeEnum;
import com.flowagent.engine.dsl.model.WorkflowDSL;
import com.flowagent.engine.dsl.model.Edge;
import com.flowagent.engine.dsl.model.Node;
import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class GraphBuilder {

    public GraphBuildResult build(WorkflowDSL workflowDSL) {
        try {
            Node startNode = null;
            Map<String, Node> nodeMap = new HashMap<>();

            for (Node node : workflowDSL.getNodes()) {
                if (node.getNodeType() == NodeTypeEnum.START) {
                    startNode = node;
                }
                node.init();
                nodeMap.put(node.getId(), node);
            }

            if (startNode == null) {
                throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "No start node found in workflow");
            }

            for (Edge edge : workflowDSL.getEdges()) {
                Node sourceNode = nodeMap.get(edge.getSource());
                if (sourceNode == null) {
                    throw new NodeCustomException(ErrorCode.INVALID_NODE_CONFIGURATION, "No node found for source node ID: " + edge.getSource());
                }
                Node targetNode = nodeMap.get(edge.getTarget());
                if (targetNode == null) {
                    throw new NodeCustomException(ErrorCode.INVALID_NODE_CONFIGURATION, "No node found for target node ID: " + edge.getTarget());
                }

                targetNode.getPreNodes().add(sourceNode);

                String handle = edge.getSourceHandle();
                if (StringUtils.isNotBlank(handle) && handle.startsWith("fail_")) {
                    sourceNode.getFailNodes().add(targetNode);
                } else {
                    sourceNode.getNextNodes().add(targetNode);
                }
            }

            GraphBuildResult result = new GraphBuildResult();
            result.setStartNode(startNode);
            result.setNodeMap(nodeMap);
            return result;
        } catch (NodeCustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to build execution graph: {}", e.getMessage());
            throw new NodeCustomException(ErrorCode.INVALID_NODE_CONFIGURATION);
        }
    }
}
