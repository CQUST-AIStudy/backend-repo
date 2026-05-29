-- Clean unified PTA/import data for class 计科25 + 计科24 only.
-- Keeps teaching_class rows themselves and leaves 计科23 untouched.
-- Also tightens PTA keywords before re-sync to reduce cross-class contamination.

START TRANSACTION;

UPDATE teaching_class
SET pta_keyword = CONVERT(0xE8AEA1E7A7913235E695B0E68DAEE7BB93E69E84 USING utf8mb4)
WHERE id = 1;

UPDATE teaching_class
SET pta_keyword = CONVERT(0xE8AEA1E7A7913234E695B0E68DAEE7BB93E69E84 USING utf8mb4)
WHERE id = 3;

CREATE TEMPORARY TABLE tmp_target_classes AS
SELECT id, name
FROM teaching_class
WHERE id IN (1, 3);

CREATE TEMPORARY TABLE tmp_target_offerings AS
SELECT id
FROM assignment_offering
WHERE class_id IN (SELECT id FROM tmp_target_classes);

CREATE TEMPORARY TABLE tmp_target_import_jobs AS
SELECT id
FROM import_job
WHERE class_id IN (SELECT id FROM tmp_target_classes);

CREATE TEMPORARY TABLE tmp_target_artifacts (
    id BIGINT PRIMARY KEY
);

INSERT IGNORE INTO tmp_target_artifacts (id)
SELECT a.id
FROM artifact a
WHERE a.owner_type = 'PTA_IMPORT_JOB'
  AND a.owner_id IN (SELECT id FROM tmp_target_import_jobs);

INSERT IGNORE INTO tmp_target_artifacts (id)
SELECT ras.html_artifact_id
FROM pta_raw_answer_sheet ras
WHERE ras.import_job_id IN (SELECT id FROM tmp_target_import_jobs);

INSERT IGNORE INTO tmp_target_artifacts (id)
SELECT ras.code_artifact_id
FROM pta_raw_answer_sheet ras
WHERE ras.import_job_id IN (SELECT id FROM tmp_target_import_jobs)
  AND ras.code_artifact_id IS NOT NULL;

INSERT IGNORE INTO tmp_target_artifacts (id)
SELECT ras.test_report_artifact_id
FROM pta_raw_answer_sheet ras
WHERE ras.import_job_id IN (SELECT id FROM tmp_target_import_jobs)
  AND ras.test_report_artifact_id IS NOT NULL;

INSERT IGNORE INTO tmp_target_artifacts (id)
SELECT sps.latest_code_artifact_id
FROM student_problem_state sps
WHERE sps.offering_id IN (SELECT id FROM tmp_target_offerings)
  AND sps.latest_code_artifact_id IS NOT NULL;

INSERT IGNORE INTO tmp_target_artifacts (id)
SELECT sps.latest_answer_sheet_artifact_id
FROM student_problem_state sps
WHERE sps.offering_id IN (SELECT id FROM tmp_target_offerings)
  AND sps.latest_answer_sheet_artifact_id IS NOT NULL;

DELETE FROM class_student
WHERE class_id IN (SELECT id FROM tmp_target_classes);

DELETE FROM class_member
WHERE class_id IN (SELECT id FROM tmp_target_classes);

DELETE FROM student_problem_state
WHERE offering_id IN (SELECT id FROM tmp_target_offerings);

DELETE FROM student_problem_attempt
WHERE offering_id IN (SELECT id FROM tmp_target_offerings);

DELETE FROM student_assignment
WHERE offering_id IN (SELECT id FROM tmp_target_offerings);

DELETE FROM assignment_problem
WHERE offering_id IN (SELECT id FROM tmp_target_offerings);

DELETE FROM assignment_offering
WHERE id IN (SELECT id FROM tmp_target_offerings);

DELETE FROM import_job
WHERE id IN (SELECT id FROM tmp_target_import_jobs);

DELETE FROM artifact
WHERE id IN (SELECT id FROM tmp_target_artifacts);

COMMIT;

SELECT id, name, pta_keyword
FROM teaching_class
WHERE id IN (SELECT id FROM tmp_target_classes)
ORDER BY id;

SELECT class_id, COUNT(*) AS row_count
FROM assignment_offering
WHERE class_id IN (SELECT id FROM tmp_target_classes)
GROUP BY class_id;

SELECT class_id, COUNT(*) AS row_count
FROM class_member
WHERE class_id IN (SELECT id FROM tmp_target_classes)
GROUP BY class_id;

SELECT class_id, COUNT(*) AS row_count
FROM class_student
WHERE class_id IN (SELECT id FROM tmp_target_classes)
GROUP BY class_id;

SELECT class_id, COUNT(*) AS row_count
FROM import_job
WHERE class_id IN (SELECT id FROM tmp_target_classes)
GROUP BY class_id;

SELECT COUNT(*) AS artifact_rows_left
FROM artifact
WHERE id IN (SELECT id FROM tmp_target_artifacts);
