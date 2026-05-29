-- V8: Upgrade agent organize from analysis-only to executable Plan→Apply→Deliver

-- Per-file manifest & status tracking
CREATE TABLE IF NOT EXISTS agent_job_file (
  id BIGINT NOT NULL AUTO_INCREMENT,
  job_id BIGINT NOT NULL,
  document_id BIGINT NOT NULL,
  object_key TEXT NOT NULL,
  filename VARCHAR(512) NOT NULL,
  content_type VARCHAR(128),
  size_bytes BIGINT NOT NULL DEFAULT 0,
  sha256 VARCHAR(64),
  ext VARCHAR(32),
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  error_message TEXT,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_ajf_job FOREIGN KEY (job_id) REFERENCES agent_job(id) ON DELETE CASCADE,
  CONSTRAINT fk_ajf_doc FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE INDEX idx_ajf_job ON agent_job_file(job_id);

-- Per-file AI extraction results (structured metadata for AI input)
CREATE TABLE IF NOT EXISTS agent_file_extract (
  id BIGINT NOT NULL AUTO_INCREMENT,
  job_file_id BIGINT NOT NULL,
  title_candidate VARCHAR(512),
  headings_json JSON,
  abstract_snippet TEXT,
  body_preview TEXT,
  metadata_json JSON,
  evidence_json JSON,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_afe_jf FOREIGN KEY (job_file_id) REFERENCES agent_job_file(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE UNIQUE INDEX uq_afe_jf ON agent_file_extract(job_file_id);

-- Organize plan: one row per file, the executable placement decision
CREATE TABLE IF NOT EXISTS agent_organize_plan (
  id BIGINT NOT NULL AUTO_INCREMENT,
  job_id BIGINT NOT NULL,
  job_file_id BIGINT NOT NULL,
  source_object_key TEXT NOT NULL,
  target_object_key TEXT NOT NULL,
  new_filename VARCHAR(512) NOT NULL,
  target_folder VARCHAR(512),
  doc_kind VARCHAR(32),
  topic VARCHAR(256),
  confidence DOUBLE DEFAULT 0,
  decision_source VARCHAR(16) DEFAULT 'ai',
  review_flag BOOLEAN NOT NULL DEFAULT FALSE,
  review_reason VARCHAR(256),
  duplicate_group_id VARCHAR(64),
  conflict_resolved BOOLEAN NOT NULL DEFAULT FALSE,
  applied BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_aop_job FOREIGN KEY (job_id) REFERENCES agent_job(id) ON DELETE CASCADE,
  CONSTRAINT fk_aop_jf FOREIGN KEY (job_file_id) REFERENCES agent_job_file(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE INDEX idx_aop_job ON agent_organize_plan(job_id);

-- Add step tracking columns to agent_job
ALTER TABLE agent_job ADD COLUMN current_step VARCHAR(32) DEFAULT NULL;
ALTER TABLE agent_job ADD COLUMN step_detail TEXT DEFAULT NULL;
ALTER TABLE agent_job ADD COLUMN organized_prefix TEXT DEFAULT NULL;
ALTER TABLE agent_job ADD COLUMN zip_object_key TEXT DEFAULT NULL;
