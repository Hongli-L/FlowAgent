package com.flowagent.controller.vo;

import com.flowagent.persistence.entity.NodeRunLogEntity;
import com.flowagent.persistence.entity.WorkflowExecutionEntity;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * View object for execution history. Used both for the paginated list (nodeLogs null)
 * and the detail view (nodeLogs populated).
 */
@Data
public class ExecutionHistoryVo {

    private Long executionId;
    private String workflowId;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;
    private String triggerSource;
    private List<NodeRunLogEntity> nodeLogs;

    public static ExecutionHistoryVo fromExecution(WorkflowExecutionEntity entity) {
        ExecutionHistoryVo vo = new ExecutionHistoryVo();
        vo.setExecutionId(entity.getExecutionId());
        vo.setWorkflowId(entity.getWorkflowId());
        vo.setStatus(entity.getStatus());
        vo.setStartTime(entity.getStartTime());
        vo.setEndTime(entity.getEndTime());
        vo.setDurationMs(entity.getDurationMs());
        vo.setTriggerSource(entity.getTriggerSource());
        return vo;
    }
}
