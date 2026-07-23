package com.flowagent.engine.dsl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.flowagent.common.exception.ErrorCode;
import com.flowagent.common.exception.NodeCustomException;
import com.flowagent.engine.dsl.model.WorkflowDSL;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DslParser {

    public WorkflowDSL parse(String json) {
        if (json == null || json.isBlank()) {
            throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Workflow DSL json is empty");
        }
        try {
            WorkflowDSL dsl = JSON.parseObject(json, WorkflowDSL.class);
            if (dsl == null) {
                throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Failed to parse workflow DSL");
            }
            return dsl;
        } catch (NodeCustomException e) {
            throw e;
        } catch (Exception e) {
            throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Invalid workflow DSL json: " + e.getMessage());
        }
    }

    public WorkflowDSL parseFromEnvelope(Map<String, Object> envelope) {
        if (envelope == null || envelope.isEmpty()) {
            throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Workflow envelope is empty");
        }
        JSONObject jsonObject = JSON.parseObject(JSON.toJSONString(envelope));
        String dslData = jsonObject.getString("data");
        if (dslData == null) {
            throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Invalid workflow data format");
        }
        return parse(dslData);
    }

    public WorkflowDSL parseFromStoredData(String entityDataJson) {
        if (entityDataJson == null || entityDataJson.isBlank()) {
            throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Stored workflow data is empty");
        }
        JSONObject jsonObject = JSON.parseObject(entityDataJson);
        String dslData = jsonObject.getString("data");
        if (dslData == null) {
            throw new NodeCustomException(ErrorCode.PROTOCOL_VALIDATION_ERROR, "Invalid stored workflow data format");
        }
        return parse(dslData);
    }
}
