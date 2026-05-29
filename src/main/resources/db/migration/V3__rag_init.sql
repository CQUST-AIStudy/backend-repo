-- RAG 学习助手模块数据表

-- 课程空间
CREATE TABLE course_space (
  id BIGINT NOT NULL AUTO_INCREMENT,
  teacher_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  term VARCHAR(32),
  course_name VARCHAR(128),
  description TEXT,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_cs_teacher FOREIGN KEY (teacher_id) REFERENCES tap_user(id)
) ENGINE=InnoDB;

-- 课程空间-文档关联
CREATE TABLE course_space_document (
  id BIGINT NOT NULL AUTO_INCREMENT,
  course_space_id BIGINT NOT NULL,
  document_id BIGINT NOT NULL,
  doc_type VARCHAR(32) DEFAULT 'textbook',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  chunk_count INT NOT NULL DEFAULT 0,
  error_message TEXT,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_csd_cs FOREIGN KEY (course_space_id) REFERENCES course_space(id) ON DELETE CASCADE,
  CONSTRAINT fk_csd_doc FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE,
  CONSTRAINT chk_csd_status CHECK (status IN ('PENDING','PROCESSING','READY','FAILED'))
) ENGINE=InnoDB;

CREATE UNIQUE INDEX uq_csd ON course_space_document(course_space_id, document_id);

-- 文档分块
CREATE TABLE doc_chunk (
  id BIGINT NOT NULL AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  course_space_id BIGINT NOT NULL,
  chunk_type VARCHAR(8) NOT NULL,
  parent_id BIGINT NULL,
  chunk_index INT NOT NULL DEFAULT 0,
  content TEXT NOT NULL,
  chapter_path VARCHAR(512),
  page_range VARCHAR(64),
  token_count INT NOT NULL DEFAULT 0,
  milvus_id BIGINT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_dc_doc FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE,
  CONSTRAINT fk_dc_cs FOREIGN KEY (course_space_id) REFERENCES course_space(id) ON DELETE CASCADE,
  CONSTRAINT fk_dc_parent FOREIGN KEY (parent_id) REFERENCES doc_chunk(id) ON DELETE SET NULL,
  CONSTRAINT chk_dc_type CHECK (chunk_type IN ('parent','child'))
) ENGINE=InnoDB;

CREATE INDEX idx_dc_parent ON doc_chunk(parent_id);
CREATE INDEX idx_dc_cs ON doc_chunk(course_space_id, chunk_type);

-- 问答日志
CREATE TABLE qa_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  student_id VARCHAR(64),
  course_space_id BIGINT NOT NULL,
  query TEXT NOT NULL,
  retrieved_chunk_ids JSON,
  top1_score DOUBLE,
  answer_text TEXT,
  citations_json JSON,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_qa_cs FOREIGN KEY (course_space_id) REFERENCES course_space(id)
) ENGINE=InnoDB;
