package com.flowagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Workflow execution instance. One row per workflow run, tracking overall
 * status, duration and trigger source. Node-level detail lives in node_run_log.
 */
@Data
@TableName("workflow_execution")
public class WorkflowExecutionEntity {

    /** Execution id (snowflake) */
    @TableId(type = IdType.INPUT)
    private Long executionId;

    /** Workflow id (same value carried by the DSL) */
    @TableField("workflow_id")
    private String workflowId;

    /** RUNNING / SUCCESS / FAILED */
    @TableField("status")
    private String status;

    @TableField("start_time")
    private LocalDateTime startTime;

    @TableField("end_time")
    private LocalDateTime endTime;

    /** Total execution duration in milliseconds */
    @TableField("duration_ms")
    private Long durationMs;

    /** Trigger source, e.g. API / SCHEDULER */
    @TableField("trigger_source")
    private String triggerSource;

    @TableField("create_at")
    private LocalDateTime createAt;

    @TableField("update_at")
    private LocalDateTime updateAt;
}
