-- PTA precise binding fields on teaching_class.
SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND column_name = 'pta_problem_set_id'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE teaching_class ADD COLUMN pta_problem_set_id VARCHAR(64) NULL COMMENT ''PTA problem set id'' AFTER pta_keyword',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND column_name = 'pta_problem_set_name'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE teaching_class ADD COLUMN pta_problem_set_name VARCHAR(256) NULL COMMENT ''PTA problem set name'' AFTER pta_problem_set_id',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND column_name = 'pta_group_id'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE teaching_class ADD COLUMN pta_group_id VARCHAR(64) NULL COMMENT ''PTA group id'' AFTER pta_problem_set_name',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND column_name = 'pta_group_name'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE teaching_class ADD COLUMN pta_group_name VARCHAR(256) NULL COMMENT ''PTA group name'' AFTER pta_group_id',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND column_name = 'pta_binding_verified_at'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE teaching_class ADD COLUMN pta_binding_verified_at TIMESTAMP(3) NULL DEFAULT NULL COMMENT ''PTA binding verified timestamp'' AFTER pta_group_name',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND column_name = 'pta_binding_verify_status'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE teaching_class ADD COLUMN pta_binding_verify_status VARCHAR(32) NULL COMMENT ''PTA binding verify status'' AFTER pta_binding_verified_at',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND column_name = 'pta_binding_verify_message'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE teaching_class ADD COLUMN pta_binding_verify_message VARCHAR(512) NULL COMMENT ''PTA binding verify message'' AFTER pta_binding_verify_status',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND index_name = 'idx_teaching_class_pta_binding'
);
SET @sql := IF(
  @idx = 0,
  'CREATE INDEX idx_teaching_class_pta_binding ON teaching_class(pta_problem_set_id, pta_group_id)',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
