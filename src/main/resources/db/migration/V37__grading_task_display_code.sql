-- V37: Add display_code column to grading_task
-- Format: MMDD-XX (e.g., 0610-01 for June 10, first task of the day)
SET @add_display_code_sql := (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'grading_task'
              AND column_name = 'display_code'
        ),
        'SELECT 1',
        'ALTER TABLE grading_task ADD COLUMN display_code VARCHAR(16) NULL COMMENT ''Human-friendly task identifier in MMDD-XX format'''
    )
);

PREPARE add_display_code_stmt FROM @add_display_code_sql;
EXECUTE add_display_code_stmt;
DEALLOCATE PREPARE add_display_code_stmt;

-- Backfill existing tasks with display codes based on their ID
-- This is a one-time operation; new tasks will get proper codes from the service
UPDATE grading_task
SET display_code = CONCAT(
    LPAD(MONTH(created_at), 2, '0'),
    LPAD(DAY(created_at), 2, '0'),
    '-',
    LPAD(id, 2, '0')
)
WHERE display_code IS NULL;
