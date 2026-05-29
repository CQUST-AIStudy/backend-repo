-- Legacy data repair: older deployments had a `user` table, while clean
-- installations of the current TAP schema use `tap_user`.
SET @user_table_exists := (
  SELECT COUNT(*)
  FROM information_schema.tables
  WHERE table_schema = DATABASE()
    AND table_name = 'user'
);

SET @sql := IF(
  @user_table_exists > 0,
  'UPDATE `user` SET classname = ''计科23'' WHERE role = ''student'' AND (classname LIKE ''%?%'' OR classname LIKE ''%??%'')',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
