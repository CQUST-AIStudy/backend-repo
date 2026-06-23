SET @has_keyword := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'pta_crawl_job'
    AND column_name = 'keyword'
);

SET @has_group_name := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'pta_crawl_job'
    AND column_name = 'pta_group_name'
);

SET @sql := IF(
  @has_keyword = 1 AND @has_group_name = 0,
  'ALTER TABLE pta_crawl_job CHANGE COLUMN keyword pta_group_name VARCHAR(255) NOT NULL COMMENT ''PTA user group name used for this crawl''',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_group_id := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'pta_crawl_job'
    AND column_name = 'pta_group_id'
);

SET @sql := IF(
  @has_group_id = 0,
  'ALTER TABLE pta_crawl_job ADD COLUMN pta_group_id VARCHAR(64) NULL COMMENT ''PTA user group id used for this crawl'' AFTER class_id',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'pta_crawl_job'
    AND index_name = 'idx_pta_crawl_job_keyword_mode'
);

SET @sql := IF(@idx > 0, 'DROP INDEX idx_pta_crawl_job_keyword_mode ON pta_crawl_job', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'pta_crawl_job'
    AND index_name = 'idx_pta_crawl_job_group_mode'
);

SET @sql := IF(
  @idx = 0,
  'CREATE INDEX idx_pta_crawl_job_group_mode ON pta_crawl_job(pta_group_name, mode, requested_at)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
