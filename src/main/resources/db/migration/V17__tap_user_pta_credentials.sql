SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'tap_user'
    AND column_name = 'pta_username'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE tap_user ADD COLUMN pta_username VARCHAR(128) NULL COMMENT ''Bound PTA username''',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'tap_user'
    AND column_name = 'pta_password_ciphertext'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE tap_user ADD COLUMN pta_password_ciphertext VARCHAR(1024) NULL COMMENT ''Encrypted PTA password''',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
