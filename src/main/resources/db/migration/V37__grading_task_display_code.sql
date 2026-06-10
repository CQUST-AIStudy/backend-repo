-- V37: Add display_code column to grading_task
-- Format: MMDD-XX (e.g., 0610-01 for June 10, first task of the day)
ALTER TABLE grading_task
    ADD COLUMN display_code VARCHAR(16) NULL COMMENT 'Human-friendly task identifier in MMDD-XX format';

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
