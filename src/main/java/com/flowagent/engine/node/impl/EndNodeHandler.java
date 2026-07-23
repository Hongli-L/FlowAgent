package com.flowagent.engine.node.impl;

import com.flowagent.common.enums.EndNodeOutputModeEnum;
import com.flowagent.common.enums.NodeExecStatusEnum;
import com.flowagent.engine.constants.NodeTypeEnum;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.engine.node.AbstractNodeHandler;
import com.flowagent.engine.dsl.VariableTemplateRender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * End node executor
 * Formats the final output using a template
 */
@Slf4j
@Component
public class EndNodeHandler extends AbstractNodeHandler {

    @Override
    public NodeTypeEnum getNodeType() {
        return NodeTypeEnum.END;
    }

    @Override
    protected NodeRunResult executeNode(NodeState node, Map<String, Object> inputs) {
        Map<String, Object> nodeParam = node.node().getData().getNodeParam();

        // End node has two output modes
        // 2. Direct return
        // 1. Template-formatted return
        Integer outputMode = getOutputMode(nodeParam);

        String finalOutput;
        String finalReason = "";

        if (Objects.equals(outputMode, EndNodeOutputModeEnum.VARIABLE_MODE.getMode())) {
            String template = getTemplate(nodeParam);
            if (!StringUtils.isEmpty(template)) {
                finalOutput = VariableTemplateRender.render(template, inputs);
                log.info("End node: formatted output using template (length={})", finalOutput.length());
            } else {
                finalOutput = toStr(inputs);
            }

            String reasoningTemplate = getReasonTemplate(nodeParam);
            if (!StringUtils.isEmpty(reasoningTemplate)) {
                finalReason = VariableTemplateRender.render(reasoningTemplate, inputs);
            }
        } else {
            finalOutput = toStr(inputs);
        }

        Map<String, Object> outputs = new HashMap<>();
        // Final output
        outputs.put("content", finalOutput);
        // Reasoning content output
        outputs.put("reasoning_content", finalReason);

        NodeRunResult result = new NodeRunResult();
        result.setInputs(inputs);
        result.setOutputs(outputs);
        result.setStatus(NodeExecStatusEnum.SUCCESS);
        return result;
    }

    private Integer getOutputMode(Map<String, Object> nodeParam) {
        Object outputModeObj = nodeParam.get("outputMode");
        if (outputModeObj instanceof Integer) {
            return (Integer) outputModeObj;
        } else if (outputModeObj instanceof Number) {
            return ((Number) outputModeObj).intValue();
        }
        return 1;
    }

    private String getTemplate(Map<String, Object> nodeParam) {
        Object templateObj = nodeParam.get("template");
        return templateObj != null ? String.valueOf(templateObj) : "";
    }

    private String getReasonTemplate(Map<String, Object> nodeParam) {
        Object templateObj = nodeParam.get("reasoningTemplate");
        return templateObj != null ? String.valueOf(templateObj) : "";
    }

    private String toStr(Map<String, Object> inputs) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : inputs.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }
}
