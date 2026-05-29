-- Store completion evidence on the student-experiment fact table.
-- PTA submission rows are often capped/paginated, so completion status must
-- be derived from all exported evidence rather than submissions alone.

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'student_assignment'
    AND column_name = 'transcript_row_present'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE student_assignment
     ADD COLUMN transcript_row_present BOOLEAN NOT NULL DEFAULT FALSE AFTER ranking',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'student_assignment'
    AND column_name = 'answer_sheet_count'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE student_assignment
     ADD COLUMN answer_sheet_count INT NOT NULL DEFAULT 0 AFTER transcript_row_present',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'student_assignment'
    AND column_name = 'scored_code_count'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE student_assignment
     ADD COLUMN scored_code_count INT NOT NULL DEFAULT 0 AFTER answer_sheet_count',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'student_assignment'
    AND column_name = 'submission_attempt_count'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE student_assignment
     ADD COLUMN submission_attempt_count INT NOT NULL DEFAULT 0 AFTER scored_code_count',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'student_assignment'
    AND column_name = 'completion_evidence'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE student_assignment
     ADD COLUMN completion_evidence VARCHAR(32) NOT NULL DEFAULT ''NONE'' AFTER submission_attempt_count',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'student_assignment'
    AND index_name = 'idx_student_assignment_evidence'
);
SET @sql := IF(
  @idx = 0,
  'CREATE INDEX idx_student_assignment_evidence
     ON student_assignment(offering_id, completion_evidence)',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @constraint_count := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE()
    AND table_name = 'student_assignment'
    AND constraint_name = 'chk_student_assignment_evidence_valid'
);
SET @sql := IF(
  @constraint_count = 0,
  'ALTER TABLE student_assignment
     ADD CONSTRAINT chk_student_assignment_evidence_valid
     CHECK (
       transcript_row_present IN (0, 1)
       AND answer_sheet_count >= 0
       AND scored_code_count >= 0
       AND submission_attempt_count >= 0
       AND completion_evidence IN (
         ''NONE'',
         ''TRANSCRIPT_SCORE'',
         ''ANSWER_SHEET'',
         ''SCORED_CODE'',
         ''SUBMISSION_ATTEMPT''
       )
     )',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @constraint_count := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE()
    AND table_name = 'student_assignment'
    AND constraint_name = 'chk_student_assignment_not_started_evidence_empty'
);
SET @sql := IF(
  @constraint_count > 0,
  'ALTER TABLE student_assignment DROP CHECK chk_student_assignment_not_started_evidence_empty',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @constraint_count := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE()
    AND table_name = 'student_assignment'
    AND constraint_name = 'chk_student_assignment_not_started_evidence_empty'
);
SET @sql := IF(
  @constraint_count = 0,
  'ALTER TABLE student_assignment
     ADD CONSTRAINT chk_student_assignment_not_started_evidence_empty
     CHECK (
       submission_status <> ''NOT_STARTED''
       OR (
         answer_sheet_count = 0
         AND scored_code_count = 0
         AND submission_attempt_count = 0
         AND completion_evidence = ''NONE''
       )
     )',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
