-- Phase 2.3 execution history tables.
-- Forward-looking Flyway migration; applied when Flyway is enabled (Phase 2.12).
-- The same DDL is also appended to schema.sql so the app self-bootstraps on startup.

CREATE TABLE IF NOT EXISTS `workflow_execution` (
    `execution_id`   BIGINT       NOT NULL COMMENT 'execution id (snowflake)',
    `workflow_id`    VARCHAR(64)  NOT NULL DEFAULT '' COMMENT 'workflow id from DSL',
    `status`         VARCHAR(16)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCESS/FAILED',
    `start_time`     DATETIME     DEFAULT NULL COMMENT 'execution start time',
    `end_time`       DATETIME     DEFAULT NULL COMMENT 'execution end time',
    `duration_ms`    BIGINT       DEFAULT NULL COMMENT 'total duration in milliseconds',
    `trigger_source` VARCHAR(32)  NOT NULL DEFAULT 'API' COMMENT 'API/SCHEDULER',
    `create_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`execution_id`),
    KEY `idx_execution_workflow_id_status` (`workflow_id`, `status`),
    KEY `idx_execution_created_at` (`start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='workflow execution instance';

CREATE TABLE IF NOT EXISTS `node_run_log` (
    `log_id`        BIGINT       NOT NULL COMMENT 'log id (snowflake)',
    `execution_id`  BIGINT       NOT NULL COMMENT 'owning execution id',
    `node_id`       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT 'node id from DSL',
    `node_type`     VARCHAR(32)  NOT NULL DEFAULT '' COMMENT 'LLM/START/END/...',
    `status`        VARCHAR(16)  NOT NULL DEFAULT '' COMMENT 'SUCCESS/FAILED/SKIP',
    `input_data`    LONGTEXT     COMMENT 'node input data as JSON',
    `output_data`   LONGTEXT     COMMENT 'node output data as JSON',
    `start_time`    DATETIME     DEFAULT NULL,
    `end_time`      DATETIME     DEFAULT NULL,
    `duration_ms`   BIGINT       DEFAULT NULL COMMENT 'node duration in milliseconds',
    `retry_count`   INT          NOT NULL DEFAULT 0 COMMENT 'number of attempts',
    `error_message` LONGTEXT     COMMENT 'error message if failed',
    `create_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`log_id`),
    KEY `idx_node_log_execution_id` (`execution_id`),
    KEY `idx_node_log_node_id_status` (`node_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='per-node execution log';
