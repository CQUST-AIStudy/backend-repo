SET @col := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'experiment' AND column_name = 'class');
SET @sql := IF(@col = 0, 'ALTER TABLE experiment ADD COLUMN `class` VARCHAR(128) DEFAULT NULL COMMENT ''PTA同步关键词''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
