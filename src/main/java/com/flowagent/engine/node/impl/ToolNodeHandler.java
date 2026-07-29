package com.flowagent.engine.node.impl;

import com.flowagent.common.enums.NodeExecStatusEnum;
import com.flowagent.common.exception.ErrorCode;
import com.flowagent.common.exception.NodeCustomException;
import com.flowagent.engine.constants.NodeTypeEnum;
import com.flowagent.engine.domain.NodeRunResult;
import com.flowagent.engine.domain.NodeState;
import com.flowagent.engine.dsl.VariableTemplateRender;
import com.flowagent.engine.dsl.model.Node;
import com.flowagent.engine.dsl.model.OutputItem;
import com.flowagent.engine.integration.tool.HttpToolExecutor;
import com.flowagent.engine.integration.tool.ToolInvocationRequest;
import com.flowagent.engine.integration.tool.ToolInvocationResponse;
import com.flowagent.engine.node.AbstractNodeHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP tool node: invokes an external API described by the node's DSL config.
 *
 * <p>The tool configuration lives under {@code nodeParam.toolConfig} and supports:
 * <ul>
 *   <li>{@code url} - target URL, supports {@code {{node.field}}} rendering</li>
 *   <li>{@code method} - GET/POST/PUT/DELETE (defaults to GET)</li>
 *   <li>{@code headers} - key/value map, values support variable rendering</li>
 *   <li>{@code body} - request body (POST/PUT), supports variable rendering</li>
 * </ul>
 *
 * <p>Outputs ({@code statusCode}, {@code body}) plus any declared output name are written
 * to the context store. A non-2xx response throws so the framework applies the node's
 * configured error strategy (ERR_CODE / ERR_CONDITION / ERR_INTERRUPT).
 */
@Slf4j
@Component
public class ToolNodeHandler extends AbstractNodeHandler {

    private final HttpToolExecutor httpToolExecutor;

    public ToolNodeHandler(HttpToolExecutor httpToolExecutor) {
        this.httpToolExecutor = httpToolExecutor;
    }

    @Override
    public NodeTypeEnum getNodeType() {
        return NodeTypeEnum.TOOL;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected NodeRunResult executeNode(NodeState nodeState, Map<String, Object> inputs) throws Exception {
        Node node = nodeState.node();
        Map<String, Object> nodeParam = node.getData().getNodeParam();
        if (nodeParam == null || !(nodeParam.get("toolConfig") instanceof Map)) {
            throw new NodeCustomException(ErrorCode.INVALID_NODE_CONFIGURATION,
                    "Missing toolConfig in tool node: " + node.getId());
        }
        Map<String, Object> toolConfig = (Map<String, Object>) nodeParam.get("toolConfig");

        String url = render(toolConfig.get("url"), inputs);
        if (url == null || url.isBlank()) {
            throw new NodeCustomException(ErrorCode.INVALID_NODE_CONFIGURATION,
                    "Empty url in tool node: " + node.getId());
        }
        String method = toolConfig.get("method") == null ? "GET" : String.valueOf(toolConfig.get("method"));
        Map<String, String> headers = renderHeaders(toolConfig.get("headers"), inputs);
        String body = toolConfig.get("body") == null ? null : render(toolConfig.get("body"), inputs);

        ToolInvocationRequest request = new ToolInvocationRequest();
        request.setUrl(url);
        request.setMethod(method);
        request.setHeaders(headers);
        request.setBody(body);

        ToolInvocationResponse response = httpToolExecutor.execute(request);

        Map<String, Object> outputs = new HashMap<>();
        outputs.put("statusCode", response.getStatusCode());
        outputs.put("body", response.getBody());

        List<OutputItem> outItems = node.getData().getOutputs();
        if (!CollectionUtils.isEmpty(outItems)) {
            outputs.put(outItems.get(0).getName(), response.getBody());
        }

        // Make response data available to downstream/fail-branch nodes.
        storeOutputs(node, outputs, nodeState.variablePool());

        NodeRunResult result = new NodeRunResult();
        result.setInputs(inputs);
        result.setOutputs(outputs);
        result.setRawOutput(response.getBody());

        if (!response.isSuccess()) {
            // Non-2xx: let the framework apply the node error strategy.
            throw new NodeCustomException(ErrorCode.NODE_RUN_ERROR,
                    "Tool node " + node.getId() + " received HTTP " + response.getStatusCode());
        }

        result.setStatus(NodeExecStatusEnum.SUCCESS);
        return result;
    }

    private String render(Object template, Map<String, Object> inputs) {
        if (template == null) {
            return null;
        }
        return VariableTemplateRender.render(String.valueOf(template), inputs);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> renderHeaders(Object rawHeaders, Map<String, Object> inputs) {
        if (!(rawHeaders instanceof Map)) {
            return null;
        }
        Map<String, Object> headerMap = (Map<String, Object>) rawHeaders;
        Map<String, String> rendered = new HashMap<>();
        headerMap.forEach((k, v) -> rendered.put(k, render(v, inputs)));
        return rendered;
    }
}
