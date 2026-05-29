-- Review Draft Only
-- This file is not yet a Flyway migration.
-- It is intended for schema review and implementation planning.
-- Execution caveats:
-- 1. Convert into multiple Flyway migrations before execution.
-- 2. Wrap repeated ADD CONSTRAINT statements with idempotent checks.
-- 3. Do not drop legacy tables in the first migration batch.
-- 4. Apply schema first, then import-path migration, then read-path migration.
-- 5. Do not add grading_submission.student_id -> student_profile(id) until historical grading rows are backfilled and validated.

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS academic_term (
  id BIGINT NOT NULL AUTO_INCREMENT,
  term_code VARCHAR(32) NOT NULL,
  name VARCHAR(64) NOT NULL,
  start_date DATE NULL,
  end_date DATE NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_academic_term_code UNIQUE (term_code),
  CONSTRAINT chk_academic_term_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS course (
  id BIGINT NOT NULL AUTO_INCREMENT,
  course_code VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  subject VARCHAR(64) NULL,
  description TEXT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_course_code UNIQUE (course_code),
  CONSTRAINT chk_course_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS student_profile (
  id BIGINT NOT NULL AUTO_INCREMENT,
  student_no VARCHAR(32) NOT NULL,
  real_name VARCHAR(128) NOT NULL,
  user_id BIGINT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_student_profile_student_no UNIQUE (student_no),
  CONSTRAINT fk_student_profile_user FOREIGN KEY (user_id) REFERENCES tap_user(id) ON DELETE SET NULL,
  CONSTRAINT chk_student_profile_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'GRADUATED', 'DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_student_profile_user ON student_profile(user_id);
CREATE INDEX idx_student_profile_name ON student_profile(real_name);

ALTER TABLE teaching_class
  ADD COLUMN IF NOT EXISTS course_id BIGINT NULL COMMENT 'Reference to course',
  ADD COLUMN IF NOT EXISTS term_id BIGINT NULL COMMENT 'Reference to academic term',
  ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE or ARCHIVED',
  ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP(3) NULL DEFAULT NULL COMMENT 'Archive timestamp';

ALTER TABLE teaching_class
  ADD CONSTRAINT fk_teaching_class_course
    FOREIGN KEY (course_id) REFERENCES course(id);

ALTER TABLE teaching_class
  ADD CONSTRAINT fk_teaching_class_term
    FOREIGN KEY (term_id) REFERENCES academic_term(id);

CREATE INDEX idx_teaching_class_course_term ON teaching_class(course_id, term_id);

CREATE TABLE IF NOT EXISTS class_member (
  id BIGINT NOT NULL AUTO_INCREMENT,
  class_id BIGINT NOT NULL,
  student_id BIGINT NOT NULL,
  member_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  joined_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  left_at TIMESTAMP(3) NULL DEFAULT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_class_member UNIQUE (class_id, student_id),
  CONSTRAINT fk_class_member_class FOREIGN KEY (class_id) REFERENCES teaching_class(id) ON DELETE CASCADE,
  CONSTRAINT fk_class_member_student FOREIGN KEY (student_id) REFERENCES student_profile(id) ON DELETE CASCADE,
  CONSTRAINT chk_class_member_status CHECK (member_status IN ('ACTIVE', 'LEFT', 'SUSPENDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_class_member_student ON class_member(student_id);
CREATE INDEX idx_class_member_status ON class_member(member_status);

CREATE TABLE IF NOT EXISTS assignment_template (
  id BIGINT NOT NULL AUTO_INCREMENT,
  title VARCHAR(256) NOT NULL,
  category VARCHAR(64) NULL,
  language VARCHAR(32) NULL,
  description_md LONGTEXT NULL,
  source_system VARCHAR(32) NULL,
  source_template_key VARCHAR(128) NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_by BIGINT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_assignment_template_creator FOREIGN KEY (created_by) REFERENCES tap_user(id) ON DELETE SET NULL,
  CONSTRAINT chk_assignment_template_source_pair
    CHECK (
      (source_system IS NULL AND source_template_key IS NULL)
      OR (source_system IS NOT NULL AND source_template_key IS NOT NULL)
    ),
  CONSTRAINT chk_assignment_template_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE UNIQUE INDEX uq_assignment_template_source
  ON assignment_template(source_system, source_template_key);

CREATE TABLE IF NOT EXISTS assignment_offering (
  id BIGINT NOT NULL AUTO_INCREMENT,
  template_id BIGINT NOT NULL,
  class_id BIGINT NOT NULL,
  teacher_id BIGINT NOT NULL,
  seq_no INT NULL,
  title_override VARCHAR(256) NULL,
  published_at TIMESTAMP(3) NULL DEFAULT NULL,
  deadline_at TIMESTAMP(3) NULL DEFAULT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  source_system VARCHAR(32) NULL,
  source_offering_key VARCHAR(128) NULL,
  pta_problem_set_id VARCHAR(64) NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_assignment_offering_template FOREIGN KEY (template_id) REFERENCES assignment_template(id),
  CONSTRAINT fk_assignment_offering_class FOREIGN KEY (class_id) REFERENCES teaching_class(id),
  CONSTRAINT fk_assignment_offering_teacher FOREIGN KEY (teacher_id) REFERENCES tap_user(id),
  CONSTRAINT chk_assignment_offering_source_pair
    CHECK (
      (source_system IS NULL AND source_offering_key IS NULL)
      OR (source_system IS NOT NULL AND source_offering_key IS NOT NULL)
    ),
  CONSTRAINT chk_assignment_offering_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED', 'ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE UNIQUE INDEX uq_assignment_offering_source
  ON assignment_offering(source_system, source_offering_key);
-- PTA problem set identifiers are queryable source attributes, but should not be assumed globally unique
-- across future cross-term or cross-class reuse patterns.
CREATE INDEX idx_assignment_offering_pta_problem_set_id
  ON assignment_offering(pta_problem_set_id);
CREATE INDEX idx_assignment_offering_class ON assignment_offering(class_id);
CREATE INDEX idx_assignment_offering_teacher ON assignment_offering(teacher_id);
CREATE INDEX idx_assignment_offering_template ON assignment_offering(template_id);
CREATE INDEX idx_assignment_offering_deadline ON assignment_offering(deadline_at);

CREATE TABLE IF NOT EXISTS assignment_problem (
  id BIGINT NOT NULL AUTO_INCREMENT,
  offering_id BIGINT NOT NULL,
  problem_no VARCHAR(32) NOT NULL,
  source_problem_id VARCHAR(64) NULL,
  title VARCHAR(256) NOT NULL,
  statement_md LONGTEXT NULL,
  max_score DECIMAL(8,2) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_assignment_problem_no UNIQUE (offering_id, problem_no),
  CONSTRAINT uq_assignment_problem_source UNIQUE (offering_id, source_problem_id),
  CONSTRAINT fk_assignment_problem_offering FOREIGN KEY (offering_id) REFERENCES assignment_offering(id) ON DELETE CASCADE,
  CONSTRAINT chk_assignment_problem_status CHECK (status IN ('ACTIVE', 'REMOVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Supports composite FK validation so child rows cannot point to a problem from another offering.
CREATE UNIQUE INDEX uq_assignment_problem_id_offering
  ON assignment_problem(id, offering_id);

-- This table is a roster-backed read model, not an attempts-only aggregate.
-- Rows should be materialized for every active class_member of the offering's class,
-- including students with no attempts yet.
CREATE TABLE IF NOT EXISTS student_assignment (
  id BIGINT NOT NULL AUTO_INCREMENT,
  offering_id BIGINT NOT NULL,
  student_id BIGINT NOT NULL,
  submission_status VARCHAR(16) NOT NULL DEFAULT 'NOT_STARTED',
  first_submit_at TIMESTAMP(3) NULL DEFAULT NULL,
  last_submit_at TIMESTAMP(3) NULL DEFAULT NULL,
  accepted_problem_count INT NOT NULL DEFAULT 0,
  submitted_problem_count INT NOT NULL DEFAULT 0,
  problem_count INT NOT NULL DEFAULT 0,
  best_total_score DECIMAL(10,2) NULL,
  latest_total_score DECIMAL(10,2) NULL,
  ranking INT NULL,
  latest_sync_at TIMESTAMP(3) NULL DEFAULT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_student_assignment UNIQUE (offering_id, student_id),
  CONSTRAINT fk_student_assignment_offering FOREIGN KEY (offering_id) REFERENCES assignment_offering(id) ON DELETE CASCADE,
  CONSTRAINT fk_student_assignment_student FOREIGN KEY (student_id) REFERENCES student_profile(id) ON DELETE CASCADE,
  CONSTRAINT chk_student_assignment_status CHECK (submission_status IN ('NOT_STARTED', 'IN_PROGRESS', 'SUBMITTED', 'GRADED', 'CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_student_assignment_offering ON student_assignment(offering_id);
CREATE INDEX idx_student_assignment_student ON student_assignment(student_id);
CREATE INDEX idx_student_assignment_status ON student_assignment(submission_status);

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

CREATE TABLE IF NOT EXISTS external_identity_binding (
  id BIGINT NOT NULL AUTO_INCREMENT,
  entity_type VARCHAR(32) NOT NULL,
  entity_id BIGINT NOT NULL,
  source_system VARCHAR(32) NOT NULL,
  external_id VARCHAR(128) NOT NULL,
  binding_type VARCHAR(32) NOT NULL,
  confidence DECIMAL(5,4) NOT NULL DEFAULT 1.0000,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  valid_from TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  valid_to TIMESTAMP(3) NULL DEFAULT NULL,
  metadata_json JSON NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_external_identity_entity ON external_identity_binding(entity_type, entity_id);
CREATE INDEX idx_external_identity_source_external
  ON external_identity_binding(source_system, entity_type, external_id, is_active);

CREATE TABLE IF NOT EXISTS artifact (
  id BIGINT NOT NULL AUTO_INCREMENT,
  owner_type VARCHAR(32) NOT NULL,
  owner_id BIGINT NOT NULL,
  artifact_type VARCHAR(32) NOT NULL,
  storage_type VARCHAR(16) NOT NULL DEFAULT 'OBJECT',
  object_key TEXT NULL,
  text_content LONGTEXT NULL,
  content_hash VARCHAR(64) NULL,
  mime_type VARCHAR(128) NULL,
  file_name VARCHAR(512) NULL,
  size_bytes BIGINT NULL,
  source_system VARCHAR(32) NULL,
  source_key VARCHAR(128) NULL,
  metadata_json JSON NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE UNIQUE INDEX uq_artifact_source ON artifact(source_system, source_key);
CREATE INDEX idx_artifact_owner ON artifact(owner_type, owner_id);
CREATE INDEX idx_artifact_type ON artifact(artifact_type);
CREATE INDEX idx_artifact_hash ON artifact(content_hash);
-- For import-owned artifacts, generate source_key within the import_job scope at the application layer.

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

CREATE TABLE IF NOT EXISTS student_problem_attempt (
  id BIGINT NOT NULL AUTO_INCREMENT,
  offering_id BIGINT NOT NULL,
  problem_id BIGINT NOT NULL,
  student_id BIGINT NOT NULL,
  pta_user_id VARCHAR(64) NULL,
  source_system VARCHAR(32) NOT NULL,
  source_attempt_key VARCHAR(128) NOT NULL,
  submitted_at TIMESTAMP(3) NOT NULL,
  judge_status VARCHAR(64) NULL,
  score DECIMAL(10,2) NULL,
  compiler VARCHAR(64) NULL,
  runtime_ms INT NULL,
  memory_kb INT NULL,
  raw_row_id BIGINT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_student_problem_attempt_source UNIQUE (source_system, source_attempt_key),
  CONSTRAINT fk_student_problem_attempt_offering FOREIGN KEY (offering_id) REFERENCES assignment_offering(id) ON DELETE CASCADE,
  CONSTRAINT fk_student_problem_attempt_problem_offering
    FOREIGN KEY (problem_id, offering_id) REFERENCES assignment_problem(id, offering_id) ON DELETE CASCADE,
  CONSTRAINT fk_student_problem_attempt_student FOREIGN KEY (student_id) REFERENCES student_profile(id) ON DELETE CASCADE,
  CONSTRAINT fk_student_problem_attempt_student_assignment
    FOREIGN KEY (offering_id, student_id) REFERENCES student_assignment(offering_id, student_id),
  CONSTRAINT fk_student_problem_attempt_raw_row FOREIGN KEY (raw_row_id) REFERENCES pta_raw_submission_row(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Locks attempts to the canonical offering roster and supports scoped attempt references from state rows.
-- source_attempt_key should be built from stable submission identity fields only, not mutable judge outputs.
CREATE UNIQUE INDEX uq_student_problem_attempt_id_scope
  ON student_problem_attempt(id, offering_id, problem_id, student_id);
CREATE INDEX idx_student_problem_attempt_offering_student
  ON student_problem_attempt(offering_id, student_id, submitted_at);
CREATE INDEX idx_student_problem_attempt_problem_student
  ON student_problem_attempt(problem_id, student_id, submitted_at);
CREATE INDEX idx_student_problem_attempt_status
  ON student_problem_attempt(judge_status);

CREATE TABLE IF NOT EXISTS student_problem_state (
  id BIGINT NOT NULL AUTO_INCREMENT,
  offering_id BIGINT NOT NULL,
  problem_id BIGINT NOT NULL,
  student_id BIGINT NOT NULL,
  latest_attempt_id BIGINT NULL,
  best_attempt_id BIGINT NULL,
  latest_status VARCHAR(64) NULL,
  best_score DECIMAL(10,2) NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  accepted_at TIMESTAMP(3) NULL DEFAULT NULL,
  latest_code_artifact_id BIGINT NULL,
  latest_answer_sheet_artifact_id BIGINT NULL,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_student_problem_state UNIQUE (offering_id, problem_id, student_id),
  CONSTRAINT fk_student_problem_state_offering FOREIGN KEY (offering_id) REFERENCES assignment_offering(id) ON DELETE CASCADE,
  CONSTRAINT fk_student_problem_state_problem_offering
    FOREIGN KEY (problem_id, offering_id) REFERENCES assignment_problem(id, offering_id) ON DELETE CASCADE,
  CONSTRAINT fk_student_problem_state_student FOREIGN KEY (student_id) REFERENCES student_profile(id) ON DELETE CASCADE,
  CONSTRAINT fk_student_problem_state_student_assignment
    FOREIGN KEY (offering_id, student_id) REFERENCES student_assignment(offering_id, student_id),
  CONSTRAINT fk_student_problem_state_latest_attempt
    FOREIGN KEY (latest_attempt_id, offering_id, problem_id, student_id)
    REFERENCES student_problem_attempt(id, offering_id, problem_id, student_id),
  CONSTRAINT fk_student_problem_state_best_attempt
    FOREIGN KEY (best_attempt_id, offering_id, problem_id, student_id)
    REFERENCES student_problem_attempt(id, offering_id, problem_id, student_id),
  CONSTRAINT fk_student_problem_state_code_artifact FOREIGN KEY (latest_code_artifact_id) REFERENCES artifact(id) ON DELETE SET NULL,
  CONSTRAINT fk_student_problem_state_answer_artifact FOREIGN KEY (latest_answer_sheet_artifact_id) REFERENCES artifact(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_student_problem_state_student ON student_problem_state(student_id);
CREATE INDEX idx_student_problem_state_status ON student_problem_state(latest_status);

ALTER TABLE grading_task
  ADD COLUMN IF NOT EXISTS assignment_offering_id BIGINT NULL COMMENT 'Link to assignment_offering';

ALTER TABLE grading_task
  ADD CONSTRAINT fk_grading_task_assignment_offering
    FOREIGN KEY (assignment_offering_id) REFERENCES assignment_offering(id) ON DELETE SET NULL;

-- IMPORTANT:
-- Add the grading_submission -> student_profile FK only in a later migration after:
-- 1. historical grading_submission.student_id values are mapped to student_profile.id
-- 2. orphan rows are repaired or nulled
-- 3. application reads/writes have been updated to use canonical student_profile ids
--
-- Example later migration:
-- ALTER TABLE grading_submission
--   ADD CONSTRAINT fk_grading_submission_student_profile
--     FOREIGN KEY (student_id) REFERENCES student_profile(id) ON DELETE SET NULL;
