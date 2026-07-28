SET @constraint_exists := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_advice_report'
    AND constraint_name = 'chk_teaching_advice_status'
);

SET @sql := IF(
  @constraint_exists > 0,
  'ALTER TABLE teaching_advice_report DROP CONSTRAINT chk_teaching_advice_status',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE teaching_advice_report
  ADD CONSTRAINT chk_teaching_advice_status
  CHECK (status IN ('GENERATING', 'COMPLETED', 'FAILED'));
