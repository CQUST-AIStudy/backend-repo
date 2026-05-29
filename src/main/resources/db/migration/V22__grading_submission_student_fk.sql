-- Add grading_submission.student_id -> student_profile(id) only after
-- historical rows have been backfilled to canonical student_profile ids.

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'grading_submission'
    AND column_name = 'student_id'
);

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'grading_submission'
    AND index_name = 'idx_grading_submission_student'
);
SET @sql := IF(
  @col = 1 AND @idx = 0,
  'CREATE INDEX idx_grading_submission_student ON grading_submission(student_id)',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @orphan_count := 0;
SET @sql := IF(
  @col = 1,
  'SELECT COUNT(*) INTO @orphan_count
   FROM grading_submission gs
   LEFT JOIN student_profile sp ON sp.id = gs.student_id
   WHERE gs.student_id IS NOT NULL
     AND sp.id IS NULL',
  'SELECT 0 INTO @orphan_count'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @constraint_count := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE()
    AND table_name = 'grading_submission'
    AND constraint_name = 'fk_grading_submission_student_profile'
);

SET @sql := IF(
  @col = 0,
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''V22 requires grading_submission.student_id before adding the FK''',
  IF(
    @orphan_count > 0,
    'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''Backfill grading_submission.student_id to canonical student_profile.id before V22''',
    IF(
      @constraint_count = 0,
      'ALTER TABLE grading_submission ADD CONSTRAINT fk_grading_submission_student_profile FOREIGN KEY (student_id) REFERENCES student_profile(id) ON DELETE SET NULL',
      'SELECT 1'
    )
  )
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
