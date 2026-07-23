CREATE DATABASE IF NOT EXISTS flowagent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE flowagent;

CREATE TABLE IF NOT EXISTS `flow` (
    `id`            BIGINT       NOT NULL COMMENT 'workflow id (snowflake)',
    `group_id`      BIGINT       DEFAULT NULL COMMENT 'workflow group id',
    `name`          VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'workflow name',
    `data`          LONGTEXT     COMMENT 'workflow DSL JSON',
    `release_data`  LONGTEXT     COMMENT 'published DSL JSON',
    `description`   VARCHAR(512) NOT NULL DEFAULT '' COMMENT 'workflow description',
    `version`       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT 'workflow version',
    `release_status` INT         NOT NULL DEFAULT 0 COMMENT 'publish status',
    `app_id`        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT 'application id',
    `source`        INT          NOT NULL DEFAULT 0 COMMENT 'workflow source',
    `tag`           INT          NOT NULL DEFAULT 0 COMMENT 'tag: 0=normal, 1=comparison',
    `create_by`     BIGINT       DEFAULT NULL,
    `update_by`     BIGINT       DEFAULT NULL,
    `create_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_flow_group_id` (`group_id`),
    KEY `idx_flow_app_id` (`app_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='workflow definition';
