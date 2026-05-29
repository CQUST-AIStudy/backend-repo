-- P1+P2 RAG 增强

-- 1. course_space 新增策略字段
ALTER TABLE course_space
  ADD COLUMN default_mode VARCHAR(8) NOT NULL DEFAULT 'strict',
  ADD COLUMN allow_web_search TINYINT(1) NOT NULL DEFAULT 0,
  ADD COLUMN require_citation TINYINT(1) NOT NULL DEFAULT 1,
  ADD COLUMN doc_visibility VARCHAR(16) NOT NULL DEFAULT 'private';

-- 2. qa_log 新增字段
ALTER TABLE qa_log
  ADD COLUMN mode VARCHAR(8) DEFAULT 'strict',
  ADD COLUMN coverage_score DOUBLE DEFAULT NULL,
  ADD COLUMN used_web TINYINT(1) DEFAULT 0,
  ADD COLUMN feedback TINYINT DEFAULT NULL,
  ADD COLUMN intent_type VARCHAR(32) DEFAULT NULL;

-- 3. 教师标注表
CREATE TABLE doc_chunk_annotation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  chunk_id BIGINT NOT NULL,
  annotation_type VARCHAR(16) NOT NULL,
  note TEXT,
  teacher_id BIGINT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_dca_chunk FOREIGN KEY (chunk_id) REFERENCES doc_chunk(id) ON DELETE CASCADE,
  CONSTRAINT fk_dca_teacher FOREIGN KEY (teacher_id) REFERENCES tap_user(id),
  CONSTRAINT chk_dca_type CHECK (annotation_type IN ('important','error_prone'))
) ENGINE=InnoDB;

CREATE INDEX idx_dca_chunk ON doc_chunk_annotation(chunk_id);

-- 4. 章节摘要表
CREATE TABLE chapter_summary (
  id BIGINT NOT NULL AUTO_INCREMENT,
  doc_id BIGINT NOT NULL,
  course_space_id BIGINT NOT NULL,
  chapter_path VARCHAR(512) NOT NULL,
  summary_text TEXT NOT NULL,
  level INT NOT NULL DEFAULT 1,
  parent_chapter_id BIGINT DEFAULT NULL,
  milvus_id BIGINT DEFAULT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_chsum_doc FOREIGN KEY (doc_id) REFERENCES document(id) ON DELETE CASCADE,
  CONSTRAINT fk_chsum_cs FOREIGN KEY (course_space_id) REFERENCES course_space(id) ON DELETE CASCADE,
  CONSTRAINT fk_chsum_parent FOREIGN KEY (parent_chapter_id) REFERENCES chapter_summary(id) ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE INDEX idx_chsum_doc ON chapter_summary(doc_id);
CREATE INDEX idx_chsum_course ON chapter_summary(course_space_id);
