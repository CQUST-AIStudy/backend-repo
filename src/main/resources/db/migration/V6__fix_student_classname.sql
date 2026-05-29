-- Legacy data repair: older deployments had a `student` table, while clean
-- installations of the current TAP schema do not create it.
SET @student_table_exists := (
  SELECT COUNT(*)
  FROM information_schema.tables
  WHERE table_schema = DATABASE()
    AND table_name = 'student'
);

SET @sql := IF(
  @student_table_exists > 0,
  'UPDATE student SET class_name = ''计科23'' WHERE class_name LIKE ''%?%'' OR class_name IS NULL',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
