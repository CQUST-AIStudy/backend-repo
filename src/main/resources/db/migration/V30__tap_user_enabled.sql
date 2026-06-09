SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'tap_user'
    AND column_name = 'enabled'
);
SET @sql := IF(@col = 0, 'ALTER TABLE tap_user ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'tap_user'
    AND index_name = 'idx_tap_user_enabled'
);
SET @sql := IF(@idx = 0, 'CREATE INDEX idx_tap_user_enabled ON tap_user(enabled)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
