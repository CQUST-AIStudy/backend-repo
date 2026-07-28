SET @source_hash_col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_advice_report'
    AND column_name = 'source_hash'
);

SET @sql := IF(
  @source_hash_col > 0,
  'ALTER TABLE teaching_advice_report MODIFY COLUMN source_hash VARCHAR(64) NULL COMMENT ''教学建议生成时的数据指纹；同一数据快照可复用报告''',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
