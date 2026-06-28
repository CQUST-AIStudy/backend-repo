-- 扩展 PTA 题目详情表，保存前端可直接渲染的题面内容元数据。
-- 使用幂等写法：本地或开发库如果已经手动添加过部分字段，重复执行也不会报错。

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'pta_problem_detail'
    AND column_name = 'content_format'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE pta_problem_detail ADD COLUMN content_format VARCHAR(32) NULL DEFAULT ''markdown'' AFTER content',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'pta_problem_detail'
    AND column_name = 'image_urls_json'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE pta_problem_detail ADD COLUMN image_urls_json JSON NULL AFTER content_format',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'pta_problem_detail'
    AND column_name = 'raw_json'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE pta_problem_detail ADD COLUMN raw_json JSON NULL AFTER image_urls_json',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
