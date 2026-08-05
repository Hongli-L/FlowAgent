-- 2.15 lightweight RAG: knowledge base tables (MySQL, no new middleware).
-- Embeddings are stored as a JSON array string; retrieval scores them in the JVM.

CREATE TABLE IF NOT EXISTS knowledge_document (
    id          VARCHAR(64)  NOT NULL,
    collection  VARCHAR(128) NOT NULL DEFAULT 'default',
    title       VARCHAR(512),
    content     LONGTEXT,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_knowledge_document_collection (collection)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    document_id   VARCHAR(64)  NOT NULL,
    collection    VARCHAR(128) NOT NULL DEFAULT 'default',
    chunk_index   INT          NOT NULL,
    title         VARCHAR(512),
    content       TEXT,
    embedding_json LONGTEXT,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_knowledge_chunk_collection (collection),
    KEY idx_knowledge_chunk_document (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
