-- Grading unified-key backfill and V22 precheck
-- Scope:
-- 1. backfill grading_task.assignment_offering_id
-- 2. backfill grading_submission.student_id -> student_profile.id
-- 3. enumerate residual dirty data that still blocks a clean unified grading path

SET NAMES utf8mb4;

-- =========================================================
-- A. assignment_offering link precheck and backfill
-- =========================================================

SELECT 'grading_task_missing_assignment_offering_link' AS check_name, COUNT(*) AS row_count
FROM grading_task gt
LEFT JOIN assignment_offering ao
  ON ao.source_system = 'LEGACY_TAP'
 AND ao.source_offering_key = CONCAT('LEGACY_EXPERIMENT_OFFERING:', gt.experiment_id)
WHERE gt.experiment_id IS NOT NULL
  AND gt.assignment_offering_id IS NULL
  AND ao.id IS NULL;

SELECT
  gt.id AS grading_task_id,
  gt.teacher_id,
  gt.class_id,
  gt.experiment_id
FROM grading_task gt
LEFT JOIN assignment_offering ao
  ON ao.source_system = 'LEGACY_TAP'
 AND ao.source_offering_key = CONCAT('LEGACY_EXPERIMENT_OFFERING:', gt.experiment_id)
WHERE gt.experiment_id IS NOT NULL
  AND gt.assignment_offering_id IS NULL
  AND ao.id IS NULL
ORDER BY gt.id
LIMIT 200;

UPDATE grading_task gt
JOIN assignment_offering ao
  ON ao.source_system = 'LEGACY_TAP'
 AND ao.source_offering_key = CONCAT('LEGACY_EXPERIMENT_OFFERING:', gt.experiment_id)
SET gt.assignment_offering_id = ao.id
WHERE gt.experiment_id IS NOT NULL
  AND (gt.assignment_offering_id IS NULL OR gt.assignment_offering_id <> ao.id);

SELECT 'grading_task_assignment_offering_mismatch' AS check_name, COUNT(*) AS row_count
FROM grading_task gt
JOIN assignment_offering ao
  ON ao.id = gt.assignment_offering_id
WHERE (gt.class_id IS NOT NULL AND ao.class_id <> gt.class_id)
   OR ao.teacher_id <> gt.teacher_id;

SELECT
  gt.id AS grading_task_id,
  gt.teacher_id AS grading_teacher_id,
  ao.teacher_id AS offering_teacher_id,
  gt.class_id AS grading_class_id,
  ao.class_id AS offering_class_id,
  gt.experiment_id,
  gt.assignment_offering_id
FROM grading_task gt
JOIN assignment_offering ao
  ON ao.id = gt.assignment_offering_id
WHERE (gt.class_id IS NOT NULL AND ao.class_id <> gt.class_id)
   OR ao.teacher_id <> gt.teacher_id
ORDER BY gt.id
LIMIT 200;

-- =========================================================
-- B. grading_submission.student_id backfill to student_profile.id
-- =========================================================

SELECT 'grading_submission_student_orphans_before_backfill' AS check_name, COUNT(*) AS row_count
FROM grading_submission gs
LEFT JOIN student_profile sp
  ON sp.id = gs.student_id
WHERE gs.student_id IS NOT NULL
  AND sp.id IS NULL;

-- Pass 1: use business student number when present.
UPDATE grading_submission gs
JOIN student_profile sp
  ON sp.student_no = TRIM(gs.student_no) COLLATE utf8mb4_unicode_ci
SET gs.student_id = sp.id
WHERE gs.student_no IS NOT NULL
  AND TRIM(gs.student_no) <> ''
  AND (gs.student_id IS NULL OR gs.student_id <> sp.id);

-- Pass 2: rewrite legacy numeric student_id values that still store old student.student_id semantics.
UPDATE grading_submission gs
LEFT JOIN student_profile direct
  ON direct.id = gs.student_id
JOIN student_profile legacy
  ON legacy.student_no = CAST(gs.student_id AS CHAR(32)) COLLATE utf8mb4_unicode_ci
SET gs.student_id = legacy.id
WHERE gs.student_id IS NOT NULL
  AND direct.id IS NULL;

-- Pass 3: within a linked assignment offering, resolve a unique roster student by exact real_name.
UPDATE grading_submission gs
JOIN grading_task gt
  ON gt.id = gs.task_id
JOIN assignment_offering ao
  ON ao.id = gt.assignment_offering_id
JOIN (
  SELECT
    roster.offering_id,
    roster.real_name,
    MAX(roster.student_profile_id) AS student_profile_id,
    COUNT(*) AS matched_count
  FROM (
    SELECT
      sa.offering_id,
      sp.real_name,
      sp.id AS student_profile_id
    FROM student_assignment sa
    JOIN student_profile sp
      ON sp.id = sa.student_id
    UNION ALL
    SELECT
      ao2.id AS offering_id,
      sp.real_name,
      sp.id AS student_profile_id
    FROM assignment_offering ao2
    JOIN class_member cm
      ON cm.class_id = ao2.class_id
     AND cm.member_status = 'ACTIVE'
    JOIN student_profile sp
      ON sp.id = cm.student_id
  ) roster
  WHERE roster.real_name IS NOT NULL
    AND TRIM(roster.real_name) <> ''
  GROUP BY roster.offering_id, roster.real_name
  HAVING COUNT(*) = 1
) unique_roster
  ON unique_roster.offering_id = ao.id
 AND unique_roster.real_name COLLATE utf8mb4_unicode_ci = TRIM(gs.student_name) COLLATE utf8mb4_unicode_ci
JOIN student_profile sp
  ON sp.id = unique_roster.student_profile_id
SET gs.student_id = sp.id,
    gs.student_no = COALESCE(NULLIF(gs.student_no, ''), sp.student_no),
    gs.student_name = COALESCE(NULLIF(gs.student_name, ''), sp.real_name),
    gs.class_name = COALESCE(NULLIF(gs.class_name, ''), (
      SELECT tc.name
      FROM teaching_class tc
      WHERE tc.id = ao.class_id
    ))
WHERE (gs.student_id IS NULL OR gs.student_id <> sp.id)
  AND gs.student_name IS NOT NULL
  AND TRIM(gs.student_name) <> '';

-- Sync auxiliary compatibility fields after canonical student_id is known.
UPDATE grading_submission gs
JOIN grading_task gt
  ON gt.id = gs.task_id
LEFT JOIN assignment_offering ao
  ON ao.id = gt.assignment_offering_id
LEFT JOIN teaching_class tc
  ON tc.id = ao.class_id
JOIN student_profile sp
  ON sp.id = gs.student_id
SET gs.student_no = COALESCE(NULLIF(gs.student_no, ''), sp.student_no),
    gs.student_name = COALESCE(NULLIF(gs.student_name, ''), sp.real_name),
    gs.class_name = COALESCE(NULLIF(gs.class_name, ''), tc.name)
WHERE gs.student_id IS NOT NULL;

-- =========================================================
-- C. V22 precheck
-- =========================================================

SELECT 'grading_submission_student_orphans_after_backfill' AS check_name, COUNT(*) AS row_count
FROM grading_submission gs
LEFT JOIN student_profile sp
  ON sp.id = gs.student_id
WHERE gs.student_id IS NOT NULL
  AND sp.id IS NULL;

SELECT
  gs.id AS grading_submission_id,
  gs.task_id,
  gt.assignment_offering_id,
  gs.student_id,
  gs.student_no,
  gs.student_name,
  gs.class_name,
  gs.original_filename
FROM grading_submission gs
JOIN grading_task gt
  ON gt.id = gs.task_id
LEFT JOIN student_profile sp
  ON sp.id = gs.student_id
WHERE gs.student_id IS NOT NULL
  AND sp.id IS NULL
ORDER BY gs.id
LIMIT 200;

SELECT 'ready_for_v22_grading_student_fk' AS check_name, COUNT(*) = 0 AS passed
FROM grading_submission gs
LEFT JOIN student_profile sp
  ON sp.id = gs.student_id
WHERE gs.student_id IS NOT NULL
  AND sp.id IS NULL;

-- =========================================================
-- D. Residual dirty-data checklist
-- =========================================================

SELECT 'submissions_without_student_no_after_resolution' AS check_name, COUNT(*) AS row_count
FROM grading_submission gs
WHERE gs.student_id IS NOT NULL
  AND (gs.student_no IS NULL OR TRIM(gs.student_no) = '');

SELECT 'linked_tasks_missing_assignment_offering_id' AS check_name, COUNT(*) AS row_count
FROM grading_task gt
WHERE gt.experiment_id IS NOT NULL
  AND gt.assignment_offering_id IS NULL;

SELECT 'named_submissions_without_canonical_student' AS check_name, COUNT(*) AS row_count
FROM grading_submission gs
JOIN grading_task gt
  ON gt.id = gs.task_id
WHERE gt.assignment_offering_id IS NOT NULL
  AND (gs.student_id IS NULL)
  AND gs.student_name IS NOT NULL
  AND TRIM(gs.student_name) <> '';

SELECT
  gs.id AS grading_submission_id,
  gs.task_id,
  gt.assignment_offering_id,
  gs.student_no,
  gs.student_name,
  gs.class_name,
  gs.original_filename
FROM grading_submission gs
JOIN grading_task gt
  ON gt.id = gs.task_id
WHERE gt.assignment_offering_id IS NOT NULL
  AND gs.student_id IS NULL
ORDER BY gs.id
LIMIT 200;
