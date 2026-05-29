-- PTA 数据同步功能：teaching_class 表新增同步相关字段（幂等）
SET @col := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'teaching_class' AND column_name = 'pta_keyword');
SET @sql := IF(@col = 0, 'ALTER TABLE teaching_class ADD COLUMN pta_keyword VARCHAR(128) DEFAULT NULL COMMENT ''PTA搜索关键词''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'teaching_class' AND column_name = 'sync_enabled');
SET @sql := IF(@col = 0, 'ALTER TABLE teaching_class ADD COLUMN sync_enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT ''是否开启PTA定时同步''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'teaching_class' AND column_name = 'last_sync_at');
SET @sql := IF(@col = 0, 'ALTER TABLE teaching_class ADD COLUMN last_sync_at TIMESTAMP NULL DEFAULT NULL COMMENT ''上次同步完成时间''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'teaching_class' AND column_name = 'sync_status');
SET @sql := IF(@col = 0, 'ALTER TABLE teaching_class ADD COLUMN sync_status VARCHAR(32) DEFAULT ''IDLE'' COMMENT ''同步状态''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
