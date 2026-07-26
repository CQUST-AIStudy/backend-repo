-- Align legacy LeetCode tag storage with the canonical V12 contract.
DROP TABLE IF EXISTS leetcode_problem_tag_v65;
DROP TABLE IF EXISTS leetcode_problem_tag_v65_legacy;

CREATE TABLE leetcode_problem_tag_v65 (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    problem_id BIGINT NOT NULL,
    tag_name VARCHAR(64) NOT NULL,
    tag_category ENUM('algorithm','data_structure','technique') NOT NULL,
    relevance_score DECIMAL(5,4) NOT NULL DEFAULT 1.0000,
    is_primary TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_problem_tag (problem_id, tag_name),
    KEY idx_problem (problem_id),
    KEY idx_tag (tag_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Canonical LeetCode problem tags';

SET @has_canonical_tag_columns := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'leetcode_problem_tag'
      AND column_name = 'tag_name'
);
SET @copy_tags_sql := IF(
    @has_canonical_tag_columns > 0,
    'INSERT IGNORE INTO leetcode_problem_tag_v65 (problem_id, tag_name, tag_category, relevance_score, is_primary, created_at) SELECT problem_id, tag_name, tag_category, relevance_score, is_primary, created_at FROM leetcode_problem_tag',
    'INSERT IGNORE INTO leetcode_problem_tag_v65 (problem_id, tag_name, tag_category, relevance_score, is_primary, created_at) SELECT problem_id, tag_value, CASE WHEN tag_type IN (''algorithm'',''data_structure'',''technique'') THEN tag_type ELSE ''technique'' END, COALESCE(confidence, 1.0000), 0, created_at FROM leetcode_problem_tag'
);
PREPARE copy_tags_stmt FROM @copy_tags_sql;
EXECUTE copy_tags_stmt;
DEALLOCATE PREPARE copy_tags_stmt;

RENAME TABLE leetcode_problem_tag TO leetcode_problem_tag_v65_legacy,
             leetcode_problem_tag_v65 TO leetcode_problem_tag;
DROP TABLE leetcode_problem_tag_v65_legacy;

ALTER TABLE leetcode_problem_tag
    ADD CONSTRAINT fk_problem_tag_problem
    FOREIGN KEY (problem_id) REFERENCES leetcode_problem_bank(id) ON DELETE CASCADE;

CREATE TABLE IF NOT EXISTS leetcode_problem_embedding (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    problem_id BIGINT NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    model_revision VARCHAR(128) NOT NULL DEFAULT 'main',
    preprocessing_version VARCHAR(32) NOT NULL DEFAULT 'v1',
    content_hash CHAR(64) NOT NULL,
    dim INT NOT NULL,
    embedding_blob MEDIUMBLOB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_problem_model (problem_id, model_name),
    KEY idx_embedding_dataset (model_name, model_revision, preprocessing_version, dim),
    CONSTRAINT fk_embedding_problem
      FOREIGN KEY (problem_id) REFERENCES leetcode_problem_bank(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Versioned LeetCode problem embeddings';

SET @has_embedding_revision := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'leetcode_problem_embedding'
      AND column_name = 'model_revision'
);
SET @embedding_revision_sql := IF(
    @has_embedding_revision = 0,
    'ALTER TABLE leetcode_problem_embedding ADD COLUMN model_revision VARCHAR(128) NOT NULL DEFAULT ''main'' AFTER model_name, ADD COLUMN preprocessing_version VARCHAR(32) NOT NULL DEFAULT ''v1'' AFTER model_revision, ADD COLUMN content_hash CHAR(64) NULL AFTER preprocessing_version',
    'SELECT 1'
);
PREPARE embedding_revision_stmt FROM @embedding_revision_sql;
EXECUTE embedding_revision_stmt;
DEALLOCATE PREPARE embedding_revision_stmt;

UPDATE leetcode_problem_embedding e
JOIN leetcode_problem_bank p ON p.id = e.problem_id
SET e.content_hash = SHA2(CONCAT(
    e.preprocessing_version, CHAR(0),
    CONCAT_WS(' ',
        NULLIF(TRIM(p.title_main), ''),
        NULLIF(TRIM(p.title_alt), ''),
        NULLIF(TRIM(p.problem_text), '')
    )
), 256)
WHERE e.content_hash IS NULL OR e.content_hash = '';

UPDATE leetcode_problem_embedding
SET content_hash = SHA2(CONCAT(model_name, CHAR(0), problem_id), 256)
WHERE content_hash IS NULL OR content_hash = '';

ALTER TABLE leetcode_problem_embedding
    MODIFY COLUMN content_hash CHAR(64) NOT NULL,
    MODIFY COLUMN embedding_blob MEDIUMBLOB NOT NULL;

SET @has_embedding_dataset_index := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'leetcode_problem_embedding'
      AND index_name = 'idx_embedding_dataset'
);
SET @embedding_index_sql := IF(
    @has_embedding_dataset_index = 0,
    'ALTER TABLE leetcode_problem_embedding ADD KEY idx_embedding_dataset (model_name, model_revision, preprocessing_version, dim)',
    'SELECT 1'
);
PREPARE embedding_index_stmt FROM @embedding_index_sql;
EXECUTE embedding_index_stmt;
DEALLOCATE PREPARE embedding_index_stmt;

SET @has_score_semantic := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'leetcode_recommend_item'
      AND column_name = 'score_semantic'
);
SET @score_semantic_sql := IF(
    @has_score_semantic = 0,
    'ALTER TABLE leetcode_recommend_item ADD COLUMN score_semantic DECIMAL(6,4) NOT NULL DEFAULT 0.0000 AFTER score_quality',
    'SELECT 1'
);
PREPARE score_semantic_stmt FROM @score_semantic_sql;
EXECUTE score_semantic_stmt;
DEALLOCATE PREPARE score_semantic_stmt;

SET @has_matched_tag := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'leetcode_recommend_item'
      AND column_name = 'matched_tag'
);
SET @matched_tag_sql := IF(
    @has_matched_tag = 0,
    'ALTER TABLE leetcode_recommend_item ADD COLUMN matched_tag VARCHAR(64) NULL AFTER reason_json',
    'SELECT 1'
);
PREPARE matched_tag_stmt FROM @matched_tag_sql;
EXECUTE matched_tag_stmt;
DEALLOCATE PREPARE matched_tag_stmt;

SET @has_recall_source := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'leetcode_recommend_item'
      AND column_name = 'recall_source'
);
SET @recall_source_sql := IF(
    @has_recall_source = 0,
    'ALTER TABLE leetcode_recommend_item ADD COLUMN recall_source VARCHAR(255) NULL AFTER matched_tag',
    'SELECT 1'
);
PREPARE recall_source_stmt FROM @recall_source_sql;
EXECUTE recall_source_stmt;
DEALLOCATE PREPARE recall_source_stmt;
