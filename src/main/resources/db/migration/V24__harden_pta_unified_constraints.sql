-- Harden PTA unified import constraints for replayable crawls.
-- student_problem_state is a derived cache, so it must not block pruning
-- and replaying student_problem_attempt rows during a forced re-import.

SET @constraint_count := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE()
    AND table_name = 'student_problem_state'
    AND constraint_name = 'fk_student_problem_state_latest_attempt'
);
SET @sql := IF(
  @constraint_count > 0,
  'ALTER TABLE student_problem_state DROP FOREIGN KEY fk_student_problem_state_latest_attempt',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @constraint_count := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE()
    AND table_name = 'student_problem_state'
    AND constraint_name = 'fk_student_problem_state_latest_attempt'
);
SET @sql := IF(
  @constraint_count = 0,
  'ALTER TABLE student_problem_state
     ADD CONSTRAINT fk_student_problem_state_latest_attempt
     FOREIGN KEY (latest_attempt_id)
     REFERENCES student_problem_attempt(id)
     ON DELETE SET NULL',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @constraint_count := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE()
    AND table_name = 'student_problem_state'
    AND constraint_name = 'fk_student_problem_state_best_attempt'
);
SET @sql := IF(
  @constraint_count > 0,
  'ALTER TABLE student_problem_state DROP FOREIGN KEY fk_student_problem_state_best_attempt',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @constraint_count := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE()
    AND table_name = 'student_problem_state'
    AND constraint_name = 'fk_student_problem_state_best_attempt'
);
SET @sql := IF(
  @constraint_count = 0,
  'ALTER TABLE student_problem_state
     ADD CONSTRAINT fk_student_problem_state_best_attempt
     FOREIGN KEY (best_attempt_id)
     REFERENCES student_problem_attempt(id)
     ON DELETE SET NULL',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Attempts and per-problem state should follow the student assignment row.
-- This keeps single-student cleanup and roster corrections from leaving
-- dependent rows that block deletion.
SET @constraint_count := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE()
    AND table_name = 'student_problem_attempt'
    AND constraint_name = 'fk_student_problem_attempt_student_assignment'
);
SET @sql := IF(
  @constraint_count > 0,
  'ALTER TABLE student_problem_attempt DROP FOREIGN KEY fk_student_problem_attempt_student_assignment',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @constraint_count := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE()
    AND table_name = 'student_problem_attempt'
    AND constraint_name = 'fk_student_problem_attempt_student_assignment'
);
SET @sql := IF(
  @constraint_count = 0,
  'ALTER TABLE student_problem_attempt
     ADD CONSTRAINT fk_student_problem_attempt_student_assignment
     FOREIGN KEY (offering_id, student_id)
     REFERENCES student_assignment(offering_id, student_id)
     ON DELETE CASCADE',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @constraint_count := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE()
    AND table_name = 'student_problem_state'
    AND constraint_name = 'fk_student_problem_state_student_assignment'
);
SET @sql := IF(
  @constraint_count > 0,
  'ALTER TABLE student_problem_state DROP FOREIGN KEY fk_student_problem_state_student_assignment',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @constraint_count := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE()
    AND table_name = 'student_problem_state'
    AND constraint_name = 'fk_student_problem_state_student_assignment'
);
SET @sql := IF(
  @constraint_count = 0,
  'ALTER TABLE student_problem_state
     ADD CONSTRAINT fk_student_problem_state_student_assignment
     FOREIGN KEY (offering_id, student_id)
     REFERENCES student_assignment(offering_id, student_id)
     ON DELETE CASCADE',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Enforce one active external binding per PTA external id. Inactive history
-- remains possible because the generated value becomes NULL when inactive.
SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'external_identity_binding'
    AND column_name = 'active_external_id'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE external_identity_binding
     ADD COLUMN active_external_id VARCHAR(128)
     GENERATED ALWAYS AS (CASE WHEN is_active THEN external_id ELSE NULL END) STORED',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'external_identity_binding'
    AND index_name = 'uq_external_identity_active_external'
);
SET @sql := IF(
  @idx = 0,
  'CREATE UNIQUE INDEX uq_external_identity_active_external
     ON external_identity_binding(source_system, entity_type, active_external_id)',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Data-quality guards for crawler imports.
SET @constraint_count := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE()
    AND table_name = 'student_profile'
    AND constraint_name = 'chk_student_profile_student_no_valid'
);
SET @sql := IF(
  @constraint_count = 0,
  'ALTER TABLE student_profile
     ADD CONSTRAINT chk_student_profile_student_no_valid
     CHECK (
       TRIM(student_no) <> ''''
       AND LOWER(TRIM(student_no)) NOT IN (''0'', ''none'', ''null'', ''n/a'', ''na'', ''blank'')
     )',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @constraint_count := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE()
    AND table_name = 'student_assignment'
    AND constraint_name = 'chk_student_assignment_counts_valid'
);
SET @sql := IF(
  @constraint_count = 0,
  'ALTER TABLE student_assignment
     ADD CONSTRAINT chk_student_assignment_counts_valid
     CHECK (
       accepted_problem_count >= 0
       AND submitted_problem_count >= 0
       AND problem_count >= 0
       AND accepted_problem_count <= submitted_problem_count
       AND (problem_count = 0 OR submitted_problem_count <= problem_count)
     )',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @constraint_count := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE()
    AND table_name = 'student_assignment'
    AND constraint_name = 'chk_student_assignment_scores_valid'
);
SET @sql := IF(
  @constraint_count = 0,
  'ALTER TABLE student_assignment
     ADD CONSTRAINT chk_student_assignment_scores_valid
     CHECK (
       (best_total_score IS NULL OR best_total_score >= 0)
       AND (latest_total_score IS NULL OR latest_total_score >= 0)
     )',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @constraint_count := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE()
    AND table_name = 'student_assignment'
    AND constraint_name = 'chk_student_assignment_not_started_empty'
);
SET @sql := IF(
  @constraint_count = 0,
  'ALTER TABLE student_assignment
     ADD CONSTRAINT chk_student_assignment_not_started_empty
     CHECK (
       submission_status <> ''NOT_STARTED''
       OR (
         first_submit_at IS NULL
         AND last_submit_at IS NULL
         AND accepted_problem_count = 0
         AND submitted_problem_count = 0
         AND best_total_score IS NULL
         AND latest_total_score IS NULL
         AND ranking IS NULL
       )
     )',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
