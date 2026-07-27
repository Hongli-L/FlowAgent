package com.flowagent.controller;

import com.flowagent.common.response.ApiResponse;
import com.flowagent.controller.vo.WorkflowAddRequest;
import com.flowagent.controller.vo.WorkflowReadRequest;
import com.flowagent.controller.vo.WorkflowUpdateRequest;
import com.flowagent.common.exception.ErrorCode;
import com.flowagent.persistence.entity.WorkflowEntity;
import com.flowagent.persistence.service.WorkflowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/workflow/v1/protocol")
public class ProtocolController {

    private final WorkflowService workflowService;

    public ProtocolController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping("/add")
    public ApiResponse addWorkflow(@RequestBody WorkflowAddRequest request) {
        try {
            log.info("Adding workflow: {}", request);

            WorkflowEntity savedEntity = workflowService.saveWorkflow(request.getData());

            if (request.getData() != null && !request.getData().isEmpty() && !"{}".equals(request.getData())) {
                log.info("Starting workflow validation");
                try {
                    workflowService.validateWorkflow(request.getData());
                    log.info("Workflow validation completed");
                } catch (Exception err) {
                    log.error("Workflow validation failed", err);
                    return ApiResponse.fail(ErrorCode.PROTOCOL_VALIDATION_ERROR.getCode(),
                            ErrorCode.PROTOCOL_VALIDATION_ERROR.getMsg(), ApiResponse.generateTraceId());
                }
            }

            return ApiResponse.success(Map.of("flow_id", savedEntity.getId().toString()), ApiResponse.generateTraceId());
        } catch (Exception e) {
            log.error("Failed to add workflow", e);
            return ApiResponse.fail(ErrorCode.PROTOCOL_CREATE_ERROR.getCode(),
                    ErrorCode.PROTOCOL_CREATE_ERROR.getMsg(), ApiResponse.generateTraceId());
        }
    }

    @PostMapping("/get")
    public ApiResponse getWorkflow(@RequestBody WorkflowReadRequest request) {
        try {
            WorkflowEntity flow = workflowService.getWorkflow(request.getFlowId());
            return ApiResponse.success(flow, ApiResponse.generateTraceId());
        } catch (Exception e) {
            log.error("Failed to get workflow", e);
            return ApiResponse.fail(ErrorCode.FLOW_GET_ERROR.getCode(),
                    ErrorCode.FLOW_GET_ERROR.getMsg(), ApiResponse.generateTraceId());
        }
    }

    @PostMapping("/update/{flowId}")
    public ApiResponse updateWorkflow(@PathVariable String flowId, @RequestBody WorkflowUpdateRequest request) {
        try {
            log.info("Updating workflow: {}", flowId);

            if (request.getData() != null && !request.getData().isEmpty()) {
                try {
                    workflowService.validateWorkflow(request.getData());
                    log.info("Workflow validation completed");
                } catch (Exception err) {
                    log.error("Workflow validation failed", err);
                    return ApiResponse.fail(ErrorCode.PROTOCOL_VALIDATION_ERROR.getCode(),
                            ErrorCode.PROTOCOL_VALIDATION_ERROR.getMsg(), ApiResponse.generateTraceId());
                }
            }

            workflowService.updateWorkflow(flowId, request.getData());
            return ApiResponse.success(null, ApiResponse.generateTraceId());
        } catch (Exception e) {
            log.error("Failed to update workflow", e);
            return ApiResponse.fail(ErrorCode.PROTOCOL_UPDATE_ERROR.getCode(),
                    ErrorCode.PROTOCOL_UPDATE_ERROR.getMsg(), ApiResponse.generateTraceId());
        }
    }

    @PostMapping("/delete")
    public ApiResponse deleteWorkflow(@RequestBody WorkflowReadRequest request) {
        try {
            workflowService.deleteWorkflow(request.getFlowId());
            return ApiResponse.success(null, ApiResponse.generateTraceId());
        } catch (Exception e) {
            log.error("Failed to delete workflow", e);
            return ApiResponse.fail(ErrorCode.PROTOCOL_DELETE_ERROR.getCode(),
                    ErrorCode.PROTOCOL_DELETE_ERROR.getMsg(), ApiResponse.generateTraceId());
        }
    }

}
