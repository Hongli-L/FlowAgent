package com.flowagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A single embedded chunk of a knowledge document. The embedding is stored as a JSON array
 * string (LONGTEXT) and converted to/from {@code float[]} by {@link com.flowagent.engine.integration.rag.MysqlVectorStore}.
 */
@Data
@TableName("knowledge_chunk")
public class KnowledgeChunkEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String documentId;

    private String collection;

    private Integer chunkIndex;

    private String title;

    private String content;

    private String embeddingJson;

    private LocalDateTime createdAt;

    private Integer deleted;
}
