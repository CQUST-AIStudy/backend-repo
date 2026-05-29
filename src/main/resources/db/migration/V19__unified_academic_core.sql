-- Unified academic core business schema
-- Scope:
-- 1. Core academic entities and read models
-- 2. teaching_class extensions
-- 3. No raw PTA/import lineage tables yet
-- 4. No grading_submission -> student_profile FK yet

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

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND column_name = 'course_id'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE teaching_class ADD COLUMN course_id BIGINT NULL COMMENT ''Reference to course'' AFTER course_name',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND column_name = 'term_id'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE teaching_class ADD COLUMN term_id BIGINT NULL COMMENT ''Reference to academic term'' AFTER course_id',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND column_name = 'status'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE teaching_class ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT ''ACTIVE'' COMMENT ''ACTIVE or ARCHIVED'' AFTER sync_status',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND column_name = 'archived_at'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE teaching_class ADD COLUMN archived_at TIMESTAMP(3) NULL DEFAULT NULL COMMENT ''Archive timestamp'' AFTER status',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @constraint_count := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND constraint_name = 'fk_teaching_class_course'
);
SET @sql := IF(
  @constraint_count = 0,
  'ALTER TABLE teaching_class ADD CONSTRAINT fk_teaching_class_course FOREIGN KEY (course_id) REFERENCES course(id)',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @constraint_count := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND constraint_name = 'fk_teaching_class_term'
);
SET @sql := IF(
  @constraint_count = 0,
  'ALTER TABLE teaching_class ADD CONSTRAINT fk_teaching_class_term FOREIGN KEY (term_id) REFERENCES academic_term(id)',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND index_name = 'idx_teaching_class_course_term'
);
SET @sql := IF(
  @idx = 0,
  'CREATE INDEX idx_teaching_class_course_term ON teaching_class(course_id, term_id)',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

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

CREATE UNIQUE INDEX uq_assignment_problem_id_offering
  ON assignment_problem(id, offering_id);

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

-- Single-active binding per external ID is intentionally enforced in application logic first.
-- A stricter database-level active-only uniqueness rule can be added later with a generated column strategy if needed.
CREATE INDEX idx_external_identity_entity ON external_identity_binding(entity_type, entity_id);
CREATE INDEX idx_external_identity_source_external
  ON external_identity_binding(source_system, entity_type, external_id, is_active);

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
    FOREIGN KEY (offering_id, student_id) REFERENCES student_assignment(offering_id, student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
