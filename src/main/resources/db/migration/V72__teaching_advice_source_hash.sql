SET @source_hash_col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_advice_report'
    AND column_name = 'source_hash'
);

SET @sql := IF(
  @source_hash_col = 0,
  'ALTER TABLE teaching_advice_report ADD COLUMN source_hash CHAR(64) NULL COMMENT ''教学建议生成时的数据指纹；同一数据快照可复用报告'' AFTER model',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @source_hash_idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_advice_report'
    AND index_name = 'idx_teaching_advice_source_hash'
);

SET @sql := IF(
  @source_hash_idx = 0,
  'CREATE INDEX idx_teaching_advice_source_hash ON teaching_advice_report(teacher_id, source_hash, created_at)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
