package com.flowagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Knowledge base document. {@code id} is a caller-supplied UUID (INPUT) so chunk rows can
 * reference it as a plain string without a generated-key round trip.
 */
@Data
@TableName("knowledge_document")
public class KnowledgeDocumentEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String collection;

    private String title;

    private String content;

    private LocalDateTime createdAt;

    private Integer deleted;
}
