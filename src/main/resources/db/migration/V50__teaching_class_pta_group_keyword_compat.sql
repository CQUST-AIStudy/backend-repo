-- V50 safe minimal version
-- 只做旧字段 pta_keyword -> 新字段 pta_group_name 的数据兼容
-- 不执行 ALTER TABLE MODIFY，避免 MySQL 元数据锁等待超时

SET @db_name = DATABASE();

-- 如果 pta_group_name 不存在，则补建
SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE teaching_class ADD COLUMN pta_group_name VARCHAR(256) NULL COMMENT ''PTA user group name used for precise sync''',
        'SELECT 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db_name
      AND TABLE_NAME = 'teaching_class'
      AND COLUMN_NAME = 'pta_group_name'
);

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- 如果 pta_keyword 和 pta_group_name 都存在，则同步旧数据
SET @sql = (
    SELECT IF(
        COUNT(*) = 2,
        'UPDATE teaching_class
         SET pta_group_name = NULLIF(TRIM(pta_keyword), '''')
         WHERE (pta_group_name IS NULL OR TRIM(pta_group_name) = '''')
           AND pta_keyword IS NOT NULL
           AND TRIM(pta_keyword) <> ''''',
        'SELECT 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db_name
      AND TABLE_NAME = 'teaching_class'
      AND COLUMN_NAME IN ('pta_keyword', 'pta_group_name')
);

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;