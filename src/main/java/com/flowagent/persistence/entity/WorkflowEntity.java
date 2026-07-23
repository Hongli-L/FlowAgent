package com.flowagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Workflow entity
 */
@Data
@TableName("flow")
public class WorkflowEntity {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("group_id")
    private Long groupId;

    /** Workflow name */
    @TableField("name")
    private String name;

    /** DSL workflow data */
    @TableField("data")
    private String data;

    /** DSL workflow released data */
    @TableField("release_data")
    private String releaseData;

    /** Workflow description */
    @TableField("description")
    private String description;

    /** Workflow version */
    @TableField("version")
    private String version;

    /** Workflow release status */
    @TableField("release_status")
    private Integer releaseStatus;

    /** Workflow application ID */
    @TableField("app_id")
    private String appId;

    /** Workflow source */
    @TableField("source")
    private Integer source;

    /** Workflow tag: 0 = no tag, 1 = control group */
    @TableField("tag")
    private Integer tag;

    /** Created by */
    @TableField("create_by")
    private Long createBy;

    /** Updated by */
    @TableField("update_by")
    private Long updateBy;

    /** Created at */
    @TableField("create_at")
    private LocalDateTime createAt;

    /** Updated at */
    @TableField("update_at")
    private LocalDateTime updateAt;
}
