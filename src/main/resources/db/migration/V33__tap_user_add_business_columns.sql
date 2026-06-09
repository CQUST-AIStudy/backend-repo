-- V33: Add missing business columns to tap_user for deprecating the legacy `user` table.
-- Guard each DDL so the migration can complete even if a previous local run partially applied it.

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'tap_user'
    AND column_name = 'email'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE tap_user ADD COLUMN email VARCHAR(128) DEFAULT NULL COMMENT ''邮箱地址''',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'tap_user'
    AND column_name = 'usernum'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE tap_user ADD COLUMN usernum VARCHAR(64) DEFAULT NULL COMMENT ''学号/工号''',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'tap_user'
    AND column_name = 'classname'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE tap_user ADD COLUMN classname VARCHAR(128) DEFAULT NULL COMMENT ''班级名称''',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'tap_user'
    AND index_name = 'idx_tap_user_usernum'
);
SET @sql := IF(@idx = 0, 'CREATE INDEX idx_tap_user_usernum ON tap_user(usernum)', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'tap_user'
    AND index_name = 'idx_tap_user_classname'
);
SET @sql := IF(@idx = 0, 'CREATE INDEX idx_tap_user_classname ON tap_user(classname)', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
