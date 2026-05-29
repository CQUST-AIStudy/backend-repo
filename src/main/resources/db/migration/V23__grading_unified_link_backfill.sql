-- Backfill grading unified links without changing the external API contract.
-- This migration is intentionally idempotent and keeps V21/V22 unchanged to avoid checksum drift.

-- 1. Backfill grading_task.assignment_offering_id via the canonical legacy source key.
UPDATE grading_task gt
JOIN assignment_offering ao
  ON ao.source_system = 'LEGACY_TAP'
 AND ao.source_offering_key = CONCAT('LEGACY_EXPERIMENT_OFFERING:', gt.experiment_id)
SET gt.assignment_offering_id = ao.id
WHERE gt.experiment_id IS NOT NULL
  AND (gt.assignment_offering_id IS NULL OR gt.assignment_offering_id <> ao.id);

-- 2. Backfill grading_submission.student_id from business student number when available.
UPDATE grading_submission gs
JOIN student_profile sp
  ON sp.student_no = TRIM(gs.student_no) COLLATE utf8mb4_unicode_ci
SET gs.student_id = sp.id
WHERE gs.student_no IS NOT NULL
  AND TRIM(gs.student_no) <> ''
  AND (gs.student_id IS NULL OR gs.student_id <> sp.id);

-- 3. Rewrite historical legacy numeric student_id values to canonical student_profile.id.
UPDATE grading_submission gs
LEFT JOIN student_profile direct
  ON direct.id = gs.student_id
JOIN student_profile legacy
  ON legacy.student_no = CAST(gs.student_id AS CHAR(32)) COLLATE utf8mb4_unicode_ci
SET gs.student_id = legacy.id
WHERE gs.student_id IS NOT NULL
  AND direct.id IS NULL;

-- 4. For linked tasks, resolve a unique roster student by exact real_name when the upload only carried names.
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
SET gs.student_id = sp.id
WHERE gs.student_id IS NULL
  AND gs.student_name IS NOT NULL
  AND TRIM(gs.student_name) <> '';

-- 5. Sync compatibility fields from canonical records for already-resolved submissions.
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
