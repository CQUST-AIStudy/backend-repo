UPDATE teaching_class
SET pta_group_name = NULLIF(TRIM(pta_keyword), '')
WHERE (pta_group_name IS NULL OR TRIM(pta_group_name) = '')
  AND pta_keyword IS NOT NULL
  AND TRIM(pta_keyword) <> '';

ALTER TABLE teaching_class
  MODIFY COLUMN pta_keyword VARCHAR(128) NULL COMMENT 'Legacy PTA sync keyword; kept for compatibility',
  MODIFY COLUMN pta_group_name VARCHAR(256) NULL COMMENT 'PTA user group name used for precise sync';
