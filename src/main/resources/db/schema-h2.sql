-- H2-compatible schema for the 'local' profile (in-memory DB, MODE=MySQL).
-- Mirrors schema.sql but without MySQL-specific clauses (ENGINE/CHARSET/ON UPDATE, LONGTEXT).
CREATE TABLE IF NOT EXISTS flow (
    id            BIGINT       NOT NULL,
    group_id      BIGINT       DEFAULT NULL,
    name          VARCHAR(255) NOT NULL DEFAULT '',
    data          CLOB,
    release_data  CLOB,
    description   VARCHAR(512) NOT NULL DEFAULT '',
    version       VARCHAR(64)  NOT NULL DEFAULT '',
    release_status INT         NOT NULL DEFAULT 0,
    app_id        VARCHAR(64)  NOT NULL DEFAULT '',
    source        INT         NOT NULL DEFAULT 0,
    tag           INT         NOT NULL DEFAULT 0,
    create_by     BIGINT       DEFAULT NULL,
    update_by     BIGINT       DEFAULT NULL,
    create_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS workflow_execution (
    execution_id   BIGINT       NOT NULL,
    workflow_id    VARCHAR(64)  NOT NULL DEFAULT '',
    status         VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',
    start_time     DATETIME     DEFAULT NULL,
    end_time       DATETIME     DEFAULT NULL,
    duration_ms    BIGINT       DEFAULT NULL,
    trigger_source VARCHAR(32)  NOT NULL DEFAULT 'API',
    create_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (execution_id)
);

CREATE TABLE IF NOT EXISTS node_run_log (
    log_id        BIGINT       NOT NULL,
    execution_id  BIGINT       NOT NULL,
    node_id       VARCHAR(64)  NOT NULL DEFAULT '',
    node_type     VARCHAR(32)  NOT NULL DEFAULT '',
    status        VARCHAR(16)  NOT NULL DEFAULT '',
    input_data    CLOB,
    output_data   CLOB,
    start_time    DATETIME     DEFAULT NULL,
    end_time      DATETIME     DEFAULT NULL,
    duration_ms   BIGINT       DEFAULT NULL,
    retry_count   INT         NOT NULL DEFAULT 0,
    error_message CLOB,
    create_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (log_id)
);
