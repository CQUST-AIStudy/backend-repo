-- PTA import lineage and raw replay schema
-- Scope:
-- 1. import job lineage
-- 2. raw PTA parsed tables
-- 3. wire student_problem_attempt.raw_row_id FK after raw tables exist

CREATE TABLE IF NOT EXISTS import_job (
  id BIGINT NOT NULL AUTO_INCREMENT,
  source_system VARCHAR(32) NOT NULL,
  job_type VARCHAR(32) NOT NULL,
  class_id BIGINT NULL,
  trigger_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
  triggered_by BIGINT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  started_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at TIMESTAMP(3) NULL DEFAULT NULL,
  summary_json JSON NULL,
  error_message TEXT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_import_job_class FOREIGN KEY (class_id) REFERENCES teaching_class(id) ON DELETE SET NULL,
  CONSTRAINT fk_import_job_user FOREIGN KEY (triggered_by) REFERENCES tap_user(id) ON DELETE SET NULL,
  CONSTRAINT chk_import_job_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_import_job_source_status ON import_job(source_system, status);
CREATE INDEX idx_import_job_class ON import_job(class_id);

CREATE TABLE IF NOT EXISTS import_source_file (
  id BIGINT NOT NULL AUTO_INCREMENT,
  import_job_id BIGINT NOT NULL,
  file_role VARCHAR(32) NOT NULL,
  relative_path VARCHAR(512) NOT NULL,
  sha256 VARCHAR(64) NOT NULL,
  size_bytes BIGINT NULL,
  parse_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  parsed_at TIMESTAMP(3) NULL DEFAULT NULL,
  error_message TEXT NULL,
  metadata_json JSON NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_import_source_file UNIQUE (import_job_id, relative_path),
  CONSTRAINT fk_import_source_file_job FOREIGN KEY (import_job_id) REFERENCES import_job(id) ON DELETE CASCADE,
  CONSTRAINT chk_import_source_file_status CHECK (parse_status IN ('PENDING', 'PARSED', 'FAILED', 'SKIPPED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_import_source_file_sha256 ON import_source_file(sha256);

CREATE TABLE IF NOT EXISTS pta_raw_submission_row (
  id BIGINT NOT NULL AUTO_INCREMENT,
  import_job_id BIGINT NOT NULL,
  source_file_id BIGINT NOT NULL,
  row_no INT NOT NULL,
  pta_user_id VARCHAR(64) NULL,
  pta_problem_id VARCHAR(64) NULL,
  judge_status VARCHAR(64) NULL,
  score_text VARCHAR(64) NULL,
  compiler VARCHAR(64) NULL,
  runtime_text VARCHAR(64) NULL,
  memory_text VARCHAR(64) NULL,
  submitted_at_text VARCHAR(64) NULL,
  raw_json JSON NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_pta_raw_submission_row UNIQUE (source_file_id, row_no),
  CONSTRAINT fk_pta_raw_submission_job FOREIGN KEY (import_job_id) REFERENCES import_job(id) ON DELETE CASCADE,
  CONSTRAINT fk_pta_raw_submission_file FOREIGN KEY (source_file_id) REFERENCES import_source_file(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_pta_raw_submission_pta_user ON pta_raw_submission_row(pta_user_id);
CREATE INDEX idx_pta_raw_submission_problem ON pta_raw_submission_row(pta_problem_id);

CREATE TABLE IF NOT EXISTS pta_raw_transcript_row (
  id BIGINT NOT NULL AUTO_INCREMENT,
  import_job_id BIGINT NOT NULL,
  source_file_id BIGINT NOT NULL,
  row_no INT NOT NULL,
  student_no VARCHAR(32) NULL,
  student_name VARCHAR(128) NULL,
  total_score_text VARCHAR(64) NULL,
  ranking_text VARCHAR(64) NULL,
  raw_json JSON NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_pta_raw_transcript_row UNIQUE (source_file_id, row_no),
  CONSTRAINT fk_pta_raw_transcript_job FOREIGN KEY (import_job_id) REFERENCES import_job(id) ON DELETE CASCADE,
  CONSTRAINT fk_pta_raw_transcript_file FOREIGN KEY (source_file_id) REFERENCES import_source_file(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_pta_raw_transcript_student_no ON pta_raw_transcript_row(student_no);

CREATE TABLE IF NOT EXISTS pta_raw_answer_sheet (
  id BIGINT NOT NULL AUTO_INCREMENT,
  import_job_id BIGINT NOT NULL,
  source_file_id BIGINT NOT NULL,
  student_no VARCHAR(32) NULL,
  student_name VARCHAR(128) NULL,
  problem_key VARCHAR(64) NULL,
  html_artifact_id BIGINT NOT NULL,
  code_artifact_id BIGINT NULL,
  test_report_artifact_id BIGINT NULL,
  raw_json JSON NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_pta_raw_answer_sheet_entry UNIQUE (source_file_id, html_artifact_id),
  CONSTRAINT fk_pta_raw_answer_sheet_job FOREIGN KEY (import_job_id) REFERENCES import_job(id) ON DELETE CASCADE,
  CONSTRAINT fk_pta_raw_answer_sheet_file FOREIGN KEY (source_file_id) REFERENCES import_source_file(id) ON DELETE CASCADE,
  CONSTRAINT fk_pta_raw_answer_sheet_html_artifact FOREIGN KEY (html_artifact_id) REFERENCES artifact(id) ON DELETE RESTRICT,
  CONSTRAINT fk_pta_raw_answer_sheet_code_artifact FOREIGN KEY (code_artifact_id) REFERENCES artifact(id) ON DELETE SET NULL,
  CONSTRAINT fk_pta_raw_answer_sheet_report_artifact FOREIGN KEY (test_report_artifact_id) REFERENCES artifact(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_pta_raw_answer_sheet_student_no ON pta_raw_answer_sheet(student_no);
CREATE INDEX idx_pta_raw_answer_sheet_problem_key ON pta_raw_answer_sheet(problem_key);

SET @constraint_count := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE()
    AND table_name = 'student_problem_attempt'
    AND constraint_name = 'fk_student_problem_attempt_raw_row'
);
SET @sql := IF(
  @constraint_count = 0,
  'ALTER TABLE student_problem_attempt ADD CONSTRAINT fk_student_problem_attempt_raw_row FOREIGN KEY (raw_row_id) REFERENCES pta_raw_submission_row(id) ON DELETE SET NULL',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
