package com.flowagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Per-node execution log. One row per node activation within a workflow run,
 * recording input/output, duration, retry count and error message for tracing.
 */
@Data
@TableName("node_run_log")
public class NodeRunLogEntity {

    /** Log id (snowflake) */
    @TableId(type = IdType.INPUT)
    private Long logId;

    /** Owning execution id */
    @TableField("execution_id")
    private Long executionId;

    /** Node id from the DSL, e.g. llm::002 */
    @TableField("node_id")
    private String nodeId;

    /** Node type, e.g. LLM / START / END */
    @TableField("node_type")
    private String nodeType;

    /** SUCCESS / FAILED / SKIP */
    @TableField("status")
    private String status;

    /** Node input data as JSON */
    @TableField("input_data")
    private String inputData;

    /** Node output data as JSON */
    @TableField("output_data")
    private String outputData;

    @TableField("start_time")
    private LocalDateTime startTime;

    @TableField("end_time")
    private LocalDateTime endTime;

    /** Node execution duration in milliseconds */
    @TableField("duration_ms")
    private Long durationMs;

    /** Number of execution attempts (including retries) */
    @TableField("retry_count")
    private Integer retryCount;

    /** Error message if the node failed */
    @TableField("error_message")
    private String errorMessage;

    @TableField("create_at")
    private LocalDateTime createAt;
}
