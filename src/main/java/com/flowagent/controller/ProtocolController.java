package com.flowagent.controller;

import com.flowagent.common.ratelimit.RateLimit;
import com.flowagent.common.response.ApiResponse;
import com.flowagent.controller.vo.ExecutionHistoryVo;
import com.flowagent.controller.vo.WorkflowAddRequest;
import com.flowagent.controller.vo.WorkflowReadRequest;
import com.flowagent.controller.vo.WorkflowUpdateRequest;
import com.flowagent.common.exception.ErrorCode;
import com.flowagent.persistence.entity.NodeRunLogEntity;
import com.flowagent.persistence.entity.WorkflowEntity;
import com.flowagent.persistence.entity.WorkflowExecutionEntity;
import com.flowagent.persistence.service.ExecutionHistoryService;
import com.flowagent.persistence.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/workflow/v1/protocol")
@Tag(name = "Workflow Protocol", description = "Workflow CRUD and execution history")
public class ProtocolController {

    private final WorkflowService workflowService;
    private final ExecutionHistoryService executionHistoryService;

    public ProtocolController(WorkflowService workflowService, ExecutionHistoryService executionHistoryService) {
        this.workflowService = workflowService;
        this.executionHistoryService = executionHistoryService;
    }

    @RateLimit(rate = 10, rateInterval = 1, key = RateLimit.Dimension.IP)
    @Operation(summary = "Create workflow", description = "Persists a workflow DSL and validates it on create.")
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

    @RateLimit(rate = 10, rateInterval = 1, key = RateLimit.Dimension.IP)
    @Operation(summary = "Get workflow", description = "Returns a stored workflow by flowId.")
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

    @RateLimit(rate = 10, rateInterval = 1, key = RateLimit.Dimension.IP)
    @Operation(summary = "Update workflow", description = "Validates and updates a workflow DSL by flowId.")
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

    @RateLimit(rate = 10, rateInterval = 1, key = RateLimit.Dimension.IP)
    @Operation(summary = "Delete workflow", description = "Deletes a workflow by flowId.")
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

    @RateLimit(rate = 20, rateInterval = 1, key = RateLimit.Dimension.IP)
    @Operation(summary = "List executions", description = "Paginated execution history for a workflow (records + total).")
    @PostMapping("/executions")
    public ApiResponse listExecutions(@RequestBody ExecutionListRequest request) {
        try {
            List<WorkflowExecutionEntity> executions = executionHistoryService
                    .listExecutions(request.getFlowId(), request.getPage(), request.getSize());
            long total = executionHistoryService.countExecutions(request.getFlowId());
            List<ExecutionHistoryVo> records = executions.stream()
                    .map(ExecutionHistoryVo::fromExecution)
                    .collect(Collectors.toList());
            return ApiResponse.success(Map.of("records", records, "total", total), ApiResponse.generateTraceId());
        } catch (Exception e) {
            log.error("Failed to list executions", e);
            return ApiResponse.fail(ErrorCode.FLOW_GET_ERROR.getCode(),
                    ErrorCode.FLOW_GET_ERROR.getMsg(), ApiResponse.generateTraceId());
        }
    }

    @RateLimit(rate = 20, rateInterval = 1, key = RateLimit.Dimension.IP)
    @Operation(summary = "Execution detail", description = "Returns one execution with its per-node run logs.")
    @PostMapping("/execution/detail")
    public ApiResponse executionDetail(@RequestBody ExecutionDetailRequest request) {
        try {
            WorkflowExecutionEntity execution = executionHistoryService.getExecution(request.getExecutionId());
            if (execution == null) {
                return ApiResponse.fail(ErrorCode.FLOW_GET_ERROR.getCode(),
                        "execution not found: " + request.getExecutionId(), ApiResponse.generateTraceId());
            }
            ExecutionHistoryVo vo = ExecutionHistoryVo.fromExecution(execution);
            List<NodeRunLogEntity> nodeLogs = executionHistoryService.getNodeLogs(request.getExecutionId());
            vo.setNodeLogs(nodeLogs);
            return ApiResponse.success(vo, ApiResponse.generateTraceId());
        } catch (Exception e) {
            log.error("Failed to get execution detail", e);
            return ApiResponse.fail(ErrorCode.FLOW_GET_ERROR.getCode(),
                    ErrorCode.FLOW_GET_ERROR.getMsg(), ApiResponse.generateTraceId());
        }
    }

    /** Paginated execution-list request. */
    @lombok.Data
    public static class ExecutionListRequest {
        private String flowId;
        private int page = 1;
        private int size = 10;
    }

    /** Execution detail request. */
    @lombok.Data
    public static class ExecutionDetailRequest {
        private Long executionId;
    }

}
