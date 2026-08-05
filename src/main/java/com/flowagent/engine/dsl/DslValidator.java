package com.flowagent.engine.dsl;

import com.flowagent.common.exception.ErrorCode;
import com.flowagent.common.exception.NodeCustomException;
import com.flowagent.engine.constants.NodeTypeEnum;
import com.flowagent.engine.dsl.model.InputItem;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.engine.dsl.model.NodeRef;
import com.flowagent.engine.dsl.model.Value;
import com.flowagent.engine.dsl.model.WorkflowDSL;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Component
public class DslValidator {

    private static final Set<NodeTypeEnum> SUPPORTED_NODE_TYPES = EnumSet.of(
            NodeTypeEnum.START,
            NodeTypeEnum.END,
            NodeTypeEnum.LLM,
            NodeTypeEnum.IF_ELSE,
            NodeTypeEnum.CONDITION_SWITCH,
            NodeTypeEnum.TOOL,
            NodeTypeEnum.AGENT,
            NodeTypeEnum.KNOWLEDGE
    );

    public void validate(WorkflowDSL workflowDSL) {
        if (workflowDSL == null) {
            throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Workflow DSL is null");
        }
        if (CollectionUtils.isEmpty(workflowDSL.getNodes())) {
            throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Workflow DSL has no nodes");
        }

        Set<String> nodeIds = new HashSet<>();
        for (Node node : workflowDSL.getNodes()) {
            validateNode(node, nodeIds);
        }
        validateReferences(workflowDSL, nodeIds);
    }

    private void validateNode(Node node, Set<String> nodeIds) {
        if (node.getId() == null || node.getId().isBlank()) {
            throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Node id is empty");
        }
        if (!node.getId().contains("::")) {
            throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Invalid node id format: " + node.getId());
        }
        if (!nodeIds.add(node.getId())) {
            throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Duplicate node id: " + node.getId());
        }
        if (node.getData() == null) {
            throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Node data is missing: " + node.getId());
        }

        NodeTypeEnum nodeType = node.getNodeType();
        if (nodeType == null) {
            throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Unknown node type in id: " + node.getId());
        }
        if (!SUPPORTED_NODE_TYPES.contains(nodeType)) {
            throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Unsupported node type: " + nodeType.getValue());
        }
    }

    private void validateReferences(WorkflowDSL workflowDSL, Set<String> nodeIds) {
        for (Node node : workflowDSL.getNodes()) {
            if (node.getData().getInputs() == null) {
                continue;
            }
            for (InputItem input : node.getData().getInputs()) {
                if (input.getSchema() == null || input.getSchema().getValue() == null) {
                    continue;
                }
                Value value = input.getSchema().getValue();
                if (!value.isReference()) {
                    continue;
                }
                NodeRef ref = toNodeRef(value.getContent());
                if (ref == null || ref.getNodeId() == null || ref.getNodeId().isBlank()) {
                    throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR,
                            "Invalid input reference in node: " + node.getId());
                }
                if (!nodeIds.contains(ref.getNodeId())) {
                    throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR,
                            "Input references unknown node: " + ref.getNodeId());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private NodeRef toNodeRef(Object content) {
        if (content instanceof NodeRef nodeRef) {
            return nodeRef;
        }
        if (content instanceof Map<?, ?> map) {
            NodeRef ref = new NodeRef();
            ref.setNodeId((String) map.get("nodeId"));
            ref.setName((String) map.get("name"));
            return ref;
        }
        return null;
    }
}
