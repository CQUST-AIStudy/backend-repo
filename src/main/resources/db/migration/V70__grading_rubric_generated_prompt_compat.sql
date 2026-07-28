-- Repair partially migrated grading_rubric schemas without duplicating existing columns.
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'grading_rubric'
      AND COLUMN_NAME = 'generated_prompt'
);
SET @ddl := IF(@exists_col = 0,
    'ALTER TABLE grading_rubric ADD COLUMN generated_prompt TEXT NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'grading_rubric'
      AND COLUMN_NAME = 'generated_prompt_at'
);
SET @ddl := IF(@exists_col = 0,
    'ALTER TABLE grading_rubric ADD COLUMN generated_prompt_at TIMESTAMP(3) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'grading_rubric'
      AND COLUMN_NAME = 'generated_prompt_source_hash'
);
SET @ddl := IF(@exists_col = 0,
    'ALTER TABLE grading_rubric ADD COLUMN generated_prompt_source_hash VARCHAR(64) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
