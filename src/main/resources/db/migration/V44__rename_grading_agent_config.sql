-- 兼容旧环境：如果历史版本创建的是 grading_agent_config，则重命名为 agent_config。
-- MySQL 不支持 RENAME TABLE IF EXISTS，因此这里使用动态 SQL 做安全判断。
SET @old_table_exists := (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'grading_agent_config'
);
SET @new_table_exists := (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_config'
);
SET @ddl := IF(@old_table_exists = 1 AND @new_table_exists = 0,
    'RENAME TABLE grading_agent_config TO agent_config',
    'DO 0'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
