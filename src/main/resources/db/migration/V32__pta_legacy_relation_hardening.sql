-- Harden legacy PTA table relationships used by the crawler import.
-- 1. Student ids are external identifiers, not integers. PTA/class data can
--    contain 12-digit student numbers and temporary ids.
-- 2. score should have one total-score row per student per experiment.
--    Duplicate historical rows are archived before deletion.

ALTER TABLE student
  MODIFY COLUMN student_id VARCHAR(50) NOT NULL COMMENT 'Student number or external student identifier';

CREATE TABLE IF NOT EXISTS score_duplicate_archive LIKE score;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'score_duplicate_archive'
    AND column_name = 'archived_at'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE score_duplicate_archive ADD COLUMN archived_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO score_duplicate_archive
  (score_id, username, real_name, experiment_id, score, submit_time,
   plagiarism_rate, status, serial_number, num)
SELECT s.score_id, s.username, s.real_name, s.experiment_id, s.score,
       s.submit_time, s.plagiarism_rate, s.status, s.serial_number, s.num
FROM score s
JOIN (
  SELECT username, experiment_id, MAX(score_id) AS keep_score_id
  FROM score
  GROUP BY username, experiment_id
  HAVING COUNT(*) > 1
) keepers
  ON keepers.username = s.username
 AND keepers.experiment_id = s.experiment_id
WHERE s.score_id <> keepers.keep_score_id;

DELETE s
FROM score s
JOIN (
  SELECT username, experiment_id, MAX(score_id) AS keep_score_id
  FROM score
  GROUP BY username, experiment_id
  HAVING COUNT(*) > 1
) keepers
  ON keepers.username = s.username
 AND keepers.experiment_id = s.experiment_id
WHERE s.score_id <> keepers.keep_score_id;

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'score'
    AND index_name = 'uq_score_student_experiment'
);
SET @sql := IF(
  @idx = 0,
  'ALTER TABLE score ADD UNIQUE KEY uq_score_student_experiment (username, experiment_id)',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

