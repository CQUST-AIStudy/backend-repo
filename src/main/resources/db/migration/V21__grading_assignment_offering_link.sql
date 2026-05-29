-- Link grading_task into the unified academic assignment offering dimension.
-- This intentionally does not yet add grading_submission.student_id -> student_profile(id).

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'grading_task'
    AND column_name = 'assignment_offering_id'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE grading_task ADD COLUMN assignment_offering_id BIGINT NULL COMMENT ''Link to assignment_offering'' AFTER experiment_id',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'grading_task'
    AND index_name = 'idx_grading_task_assignment_offering'
);
SET @sql := IF(
  @idx = 0,
  'CREATE INDEX idx_grading_task_assignment_offering ON grading_task(assignment_offering_id)',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @constraint_count := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE()
    AND table_name = 'grading_task'
    AND constraint_name = 'fk_grading_task_assignment_offering'
);
SET @sql := IF(
  @constraint_count = 0,
  'ALTER TABLE grading_task ADD CONSTRAINT fk_grading_task_assignment_offering FOREIGN KEY (assignment_offering_id) REFERENCES assignment_offering(id) ON DELETE SET NULL',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Deliberately deferred:
-- grading_submission.student_id -> student_profile(id)
-- This must only be added after historical grading rows are backfilled and validated.
