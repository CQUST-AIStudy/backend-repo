-- Speed up PTA unified-library ingestion refresh queries.
-- These indexes match the offering-scoped aggregation and state backfill paths in sync_to_unified_db.py.

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'student_problem_attempt'
    AND index_name = 'idx_spa_offering_problem_student_time'
);
SET @sql := IF(
  @idx = 0,
  'CREATE INDEX idx_spa_offering_problem_student_time ON student_problem_attempt(offering_id, problem_id, student_id, submitted_at, id)',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'student_problem_attempt'
    AND index_name = 'idx_spa_offering_student_problem_time'
);
SET @sql := IF(
  @idx = 0,
  'CREATE INDEX idx_spa_offering_student_problem_time ON student_problem_attempt(offering_id, student_id, problem_id, submitted_at, id)',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'student_problem_state'
    AND index_name = 'idx_sps_offering_student_problem'
);
SET @sql := IF(
  @idx = 0,
  'CREATE INDEX idx_sps_offering_student_problem ON student_problem_state(offering_id, student_id, problem_id)',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
