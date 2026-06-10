-- V35: Mirror profile fields on tap_user.
-- V34 added these fields to the legacy `user` table, while current login/profile
-- queries read from `tap_user`.

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'tap_user'
    AND column_name = 'phone'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE tap_user ADD COLUMN phone VARCHAR(30) DEFAULT NULL COMMENT ''联系电话''',
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
    AND column_name = 'department'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE tap_user ADD COLUMN department VARCHAR(100) DEFAULT NULL COMMENT ''部门''',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
