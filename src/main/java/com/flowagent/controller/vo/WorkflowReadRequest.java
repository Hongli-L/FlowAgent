package com.flowagent.controller.vo;

import lombok.Data;

/**
 * Workflow read request VO.
 */
@Data
public class WorkflowReadRequest {
    private String flowId;
    private String appId;
}
