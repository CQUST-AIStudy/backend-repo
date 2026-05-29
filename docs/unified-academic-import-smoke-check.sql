-- Unified academic import smoke checks
-- Usage:
-- 1. Run V19-V22 on a database snapshot.
-- 2. Execute the unified importer once with legacy writes disabled.
-- 3. Run the queries below to verify the import result before switching reads.

-- Latest unified import jobs and their summary.
SELECT
  ij.id,
  ij.status,
  ij.class_id,
  ij.started_at,
  ij.finished_at,
  JSON_UNQUOTE(JSON_EXTRACT(ij.summary_json, '$.experiment')) AS experiment_name,
  JSON_EXTRACT(ij.summary_json, '$.offering_id') AS offering_id,
  JSON_EXTRACT(ij.summary_json, '$.students_resolved') AS students_resolved,
  JSON_EXTRACT(ij.summary_json, '$.attempts_upserted') AS attempts_upserted,
  JSON_EXTRACT(ij.summary_json, '$.unmapped_submission_rows') AS unmapped_submission_rows,
  JSON_EXTRACT(ij.summary_json, '$.stale_attempts_pruned') AS stale_attempts_pruned,
  JSON_EXTRACT(ij.summary_json, '$.stale_problem_states_pruned') AS stale_problem_states_pruned,
  ij.error_message
FROM import_job ij
WHERE ij.source_system = 'PTA'
  AND ij.job_type = 'UNIFIED_SYNC'
ORDER BY ij.id DESC
LIMIT 20;

-- Jobs that still have unmapped PTA user IDs in submission CSVs.
SELECT
  ij.id,
  JSON_UNQUOTE(JSON_EXTRACT(ij.summary_json, '$.experiment')) AS experiment_name,
  JSON_EXTRACT(ij.summary_json, '$.unmapped_submission_rows') AS unmapped_submission_rows,
  JSON_EXTRACT(ij.summary_json, '$.unmapped_pta_user_ids') AS unmapped_pta_user_ids
FROM import_job ij
WHERE ij.source_system = 'PTA'
  AND ij.job_type = 'UNIFIED_SYNC'
  AND CAST(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(ij.summary_json, '$.unmapped_submission_rows')), '0') AS UNSIGNED) > 0
ORDER BY ij.id DESC;

-- Per-job source file coverage and raw replay counts.
SELECT
  ij.id AS import_job_id,
  JSON_UNQUOTE(JSON_EXTRACT(ij.summary_json, '$.experiment')) AS experiment_name,
  isf.file_role,
  COUNT(*) AS file_count,
  SUM(CASE WHEN isf.parse_status = 'PARSED' THEN 1 ELSE 0 END) AS parsed_file_count
FROM import_job ij
JOIN import_source_file isf
  ON isf.import_job_id = ij.id
WHERE ij.source_system = 'PTA'
  AND ij.job_type = 'UNIFIED_SYNC'
GROUP BY ij.id, JSON_UNQUOTE(JSON_EXTRACT(ij.summary_json, '$.experiment')), isf.file_role
ORDER BY ij.id DESC, isf.file_role;

SELECT
  ij.id AS import_job_id,
  JSON_UNQUOTE(JSON_EXTRACT(ij.summary_json, '$.experiment')) AS experiment_name,
  COALESCE(sub_rows.row_count, 0) AS raw_submission_rows,
  COALESCE(trans_rows.row_count, 0) AS raw_transcript_rows,
  COALESCE(sheet_rows.row_count, 0) AS raw_answer_sheet_rows
FROM import_job ij
LEFT JOIN (
  SELECT import_job_id, COUNT(*) AS row_count
  FROM pta_raw_submission_row
  GROUP BY import_job_id
) sub_rows
  ON sub_rows.import_job_id = ij.id
LEFT JOIN (
  SELECT import_job_id, COUNT(*) AS row_count
  FROM pta_raw_transcript_row
  GROUP BY import_job_id
) trans_rows
  ON trans_rows.import_job_id = ij.id
LEFT JOIN (
  SELECT import_job_id, COUNT(*) AS row_count
  FROM pta_raw_answer_sheet
  GROUP BY import_job_id
) sheet_rows
  ON sheet_rows.import_job_id = ij.id
WHERE ij.source_system = 'PTA'
  AND ij.job_type = 'UNIFIED_SYNC'
ORDER BY ij.id DESC;

-- Attempts must stay inside the student_assignment roster boundary.
SELECT COUNT(*) AS attempt_without_student_assignment
FROM student_problem_attempt spa
LEFT JOIN student_assignment sa
  ON sa.offering_id = spa.offering_id
 AND sa.student_id = spa.student_id
WHERE sa.id IS NULL;

SELECT COUNT(*) AS state_without_student_assignment
FROM student_problem_state sps
LEFT JOIN student_assignment sa
  ON sa.offering_id = sps.offering_id
 AND sa.student_id = sps.student_id
WHERE sa.id IS NULL;

-- student_problem_state should not survive without any backing attempts in the same scope.
SELECT COUNT(*) AS state_without_attempt
FROM student_problem_state sps
LEFT JOIN student_problem_attempt spa
  ON spa.offering_id = sps.offering_id
 AND spa.problem_id = sps.problem_id
 AND spa.student_id = sps.student_id
WHERE spa.id IS NULL;

-- student_assignment.problem_count should match the active problem count for the offering.
SELECT
  sa.offering_id,
  COUNT(*) AS mismatched_student_rows
FROM student_assignment sa
JOIN (
  SELECT offering_id, COUNT(*) AS expected_problem_count
  FROM assignment_problem
  WHERE status = 'ACTIVE'
  GROUP BY offering_id
) ap
  ON ap.offering_id = sa.offering_id
WHERE sa.problem_count <> ap.expected_problem_count
GROUP BY sa.offering_id
ORDER BY mismatched_student_rows DESC, sa.offering_id;

-- student_assignment.best_total_score should equal the sum of problem best scores.
SELECT
  sa.offering_id,
  sa.student_id,
  sa.best_total_score,
  agg.expected_best_total_score
FROM student_assignment sa
JOIN (
  SELECT
    offering_id,
    student_id,
    COALESCE(SUM(COALESCE(best_score, 0)), 0) AS expected_best_total_score
  FROM student_problem_state
  GROUP BY offering_id, student_id
) agg
  ON agg.offering_id = sa.offering_id
 AND agg.student_id = sa.student_id
WHERE ABS(COALESCE(sa.best_total_score, 0) - agg.expected_best_total_score) > 0.001
ORDER BY sa.offering_id, sa.student_id
LIMIT 100;

-- Detect historical pollution where PTA internal user IDs were written into student_no.
SELECT
  sp.id,
  sp.student_no,
  eib.external_id AS pta_user_id
FROM student_profile sp
JOIN external_identity_binding eib
  ON eib.entity_type = 'STUDENT_PROFILE'
 AND eib.entity_id = sp.id
 AND eib.source_system = 'PTA'
 AND eib.is_active = TRUE
WHERE sp.student_no = eib.external_id
  AND LENGTH(sp.student_no) >= 16
ORDER BY sp.id DESC
LIMIT 100;

-- V22 precheck: grading_submission.student_id must already point to student_profile.id.
SELECT COUNT(*) AS grading_submission_student_orphans
FROM grading_submission gs
LEFT JOIN student_profile sp
  ON sp.id = gs.student_id
WHERE gs.student_id IS NOT NULL
  AND sp.id IS NULL;
