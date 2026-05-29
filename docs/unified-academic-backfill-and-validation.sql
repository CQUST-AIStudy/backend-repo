-- Review Draft Only
-- Purpose:
-- 1. historical backfill into the unified academic model
-- 2. preflight validation before enabling new read paths
-- 3. preflight validation before V22__grading_submission_student_fk.sql
--
-- Execution notes:
-- 1. Run on a database snapshot first.
-- 2. Inspect every SELECT validation block before running the matching INSERT/UPDATE block.
-- 3. Some legacy tables used below are application-owned historical tables and may differ by environment.
-- 4. assignment_offering backfill requires a manual experiment -> class/teacher mapping step.

SET NAMES utf8mb4;

-- =========================================================
-- 0. Preflight checks
-- =========================================================

SELECT 'missing_student_profile_source_rows' AS check_name, COUNT(*) AS row_count
FROM (
  SELECT CAST(s.student_id AS CHAR(32)) COLLATE utf8mb4_unicode_ci AS student_no
  FROM student s
  WHERE s.student_id IS NOT NULL
  UNION
  SELECT TRIM(cs.student_num) COLLATE utf8mb4_unicode_ci AS student_no
  FROM class_student cs
  WHERE cs.student_num IS NOT NULL AND TRIM(cs.student_num) <> ''
) candidate
LEFT JOIN student_profile sp ON sp.student_no = candidate.student_no
WHERE sp.id IS NULL;

SELECT 'class_student_without_teaching_class' AS check_name, COUNT(*) AS row_count
FROM class_student cs
LEFT JOIN teaching_class tc ON tc.id = cs.class_id
WHERE tc.id IS NULL;

SELECT 'grading_submission_student_id_orphans_before_backfill' AS check_name, COUNT(*) AS row_count
FROM grading_submission gs
LEFT JOIN student_profile sp ON sp.id = gs.student_id
WHERE gs.student_id IS NOT NULL
  AND sp.id IS NULL;

-- =========================================================
-- 1. Backfill student_profile
-- =========================================================

INSERT INTO student_profile (
  student_no,
  real_name,
  user_id,
  status
)
SELECT
  candidate.student_no,
  MAX(candidate.real_name) AS real_name,
  MAX(candidate.user_id) AS user_id,
  'ACTIVE' AS status
FROM (
  SELECT
    CAST(s.student_id AS CHAR(32)) COLLATE utf8mb4_unicode_ci AS student_no,
    COALESCE(NULLIF(TRIM(s.name), ''), CAST(s.student_id AS CHAR(32))) COLLATE utf8mb4_unicode_ci AS real_name,
    tu.id AS user_id
  FROM student s
  LEFT JOIN tap_user tu
    ON tu.username COLLATE utf8mb4_unicode_ci = s.username COLLATE utf8mb4_unicode_ci
  WHERE s.student_id IS NOT NULL

  UNION ALL

  SELECT
    TRIM(cs.student_num) COLLATE utf8mb4_unicode_ci AS student_no,
    COALESCE(NULLIF(TRIM(cs.student_name), ''), TRIM(cs.student_num)) COLLATE utf8mb4_unicode_ci AS real_name,
    NULL AS user_id
  FROM class_student cs
  WHERE cs.student_num IS NOT NULL
    AND TRIM(cs.student_num) <> ''
) candidate
GROUP BY candidate.student_no
ON DUPLICATE KEY UPDATE
  real_name = COALESCE(NULLIF(VALUES(real_name), ''), student_profile.real_name),
  user_id = COALESCE(student_profile.user_id, VALUES(user_id)),
  status = CASE
    WHEN student_profile.status = 'DELETED' THEN student_profile.status
    ELSE 'ACTIVE'
  END;

-- Validate after the backfill.
SELECT 'student_profile_missing_after_backfill' AS check_name, COUNT(*) AS row_count
FROM (
  SELECT CAST(s.student_id AS CHAR(32)) COLLATE utf8mb4_unicode_ci AS student_no
  FROM student s
  WHERE s.student_id IS NOT NULL
  UNION
  SELECT TRIM(cs.student_num) COLLATE utf8mb4_unicode_ci AS student_no
  FROM class_student cs
  WHERE cs.student_num IS NOT NULL AND TRIM(cs.student_num) <> ''
) candidate
LEFT JOIN student_profile sp ON sp.student_no = candidate.student_no
WHERE sp.id IS NULL;

-- =========================================================
-- 2. Backfill class_member from canonical roster sources
-- =========================================================

INSERT INTO class_member (
  class_id,
  student_id,
  member_status,
  joined_at,
  created_at,
  updated_at
)
SELECT
  cs.class_id,
  sp.id,
  'ACTIVE' AS member_status,
  COALESCE(cs.joined_at, CURRENT_TIMESTAMP(3)) AS joined_at,
  CURRENT_TIMESTAMP(3) AS created_at,
  CURRENT_TIMESTAMP(3) AS updated_at
FROM class_student cs
JOIN teaching_class tc ON tc.id = cs.class_id
JOIN student_profile sp
  ON sp.student_no = TRIM(cs.student_num) COLLATE utf8mb4_unicode_ci
WHERE cs.student_num IS NOT NULL
  AND TRIM(cs.student_num) <> ''
ON DUPLICATE KEY UPDATE
  member_status = 'ACTIVE',
  left_at = NULL,
  updated_at = CURRENT_TIMESTAMP(3);

-- Optional fallback only if class_student is incomplete in a specific environment.
-- This fallback assumes teaching_class.name matches student.class_name.
-- Review rows from the SELECT first.
SELECT
  tc.id AS class_id,
  sp.id AS student_id,
  s.class_name
FROM student s
JOIN student_profile sp ON sp.student_no = CAST(s.student_id AS CHAR(32)) COLLATE utf8mb4_unicode_ci
JOIN teaching_class tc
  ON tc.name COLLATE utf8mb4_unicode_ci = s.class_name COLLATE utf8mb4_unicode_ci
LEFT JOIN class_member cm ON cm.class_id = tc.id AND cm.student_id = sp.id
WHERE s.class_name IS NOT NULL
  AND TRIM(s.class_name) <> ''
  AND cm.id IS NULL;

-- =========================================================
-- 3. Backfill assignment_template and assignment_offering
-- =========================================================

INSERT INTO assignment_template (
  title,
  category,
  language,
  description_md,
  source_system,
  source_template_key,
  status,
  created_by
)
SELECT
  COALESCE(NULLIF(TRIM(e.name), ''), CONCAT('Legacy Experiment ', e.experiment_id)) AS title,
  'LEGACY_EXPERIMENT' AS category,
  NULL AS language,
  NULLIF(
    CONCAT_WS('\n\n',
      NULLIF(TRIM(e.`describe`), ''),
      NULLIF(TRIM(e.requirements), '')
    ),
    ''
  ) AS description_md,
  'LEGACY_TAP' AS source_system,
  CONCAT('LEGACY_EXPERIMENT_TEMPLATE:', e.experiment_id) AS source_template_key,
  'ACTIVE' AS status,
  NULL AS created_by
FROM experiment e
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  description_md = COALESCE(VALUES(description_md), assignment_template.description_md),
  status = 'ACTIVE';

-- This staging table must be reviewed and filled before the offering backfill.
CREATE TABLE IF NOT EXISTS legacy_experiment_offering_map (
  experiment_id BIGINT NOT NULL,
  class_id BIGINT NOT NULL,
  teacher_id BIGINT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  notes VARCHAR(255) NULL,
  PRIMARY KEY (experiment_id),
  CONSTRAINT fk_legacy_experiment_offering_map_class
    FOREIGN KEY (class_id) REFERENCES teaching_class(id) ON DELETE CASCADE,
  CONSTRAINT fk_legacy_experiment_offering_map_teacher
    FOREIGN KEY (teacher_id) REFERENCES tap_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Inspect unmapped experiments before inserting offerings.
SELECT
  e.experiment_id,
  e.num,
  e.name,
  e.deadline
FROM experiment e
LEFT JOIN legacy_experiment_offering_map map ON map.experiment_id = e.experiment_id
WHERE map.experiment_id IS NULL;

INSERT INTO assignment_offering (
  template_id,
  class_id,
  teacher_id,
  seq_no,
  title_override,
  published_at,
  deadline_at,
  status,
  source_system,
  source_offering_key,
  pta_problem_set_id
)
SELECT
  at.id AS template_id,
  map.class_id,
  map.teacher_id,
  e.num AS seq_no,
  COALESCE(NULLIF(TRIM(e.name), ''), CONCAT('Legacy Experiment ', e.experiment_id)) AS title_override,
  NULL AS published_at,
  CASE
    WHEN e.deadline REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}([[:space:]][0-9]{2}:[0-9]{2}(:[0-9]{2})?)?$'
      THEN e.deadline
    ELSE NULL
  END AS deadline_at,
  'PUBLISHED' AS status,
  'LEGACY_TAP' AS source_system,
  CONCAT('LEGACY_EXPERIMENT_OFFERING:', e.experiment_id) AS source_offering_key,
  NULL AS pta_problem_set_id
FROM experiment e
JOIN legacy_experiment_offering_map map
  ON map.experiment_id = e.experiment_id
 AND map.enabled = TRUE
JOIN assignment_template at
  ON at.source_system = 'LEGACY_TAP'
 AND at.source_template_key = CONCAT('LEGACY_EXPERIMENT_TEMPLATE:', e.experiment_id)
ON DUPLICATE KEY UPDATE
  template_id = VALUES(template_id),
  class_id = VALUES(class_id),
  teacher_id = VALUES(teacher_id),
  seq_no = VALUES(seq_no),
  title_override = VALUES(title_override),
  deadline_at = COALESCE(VALUES(deadline_at), assignment_offering.deadline_at),
  status = VALUES(status);

SELECT 'assignment_offering_missing_source_key' AS check_name, COUNT(*) AS row_count
FROM assignment_offering
WHERE (source_system IS NULL AND source_offering_key IS NOT NULL)
   OR (source_system IS NOT NULL AND source_offering_key IS NULL);

-- =========================================================
-- 4. Pre-materialize roster-backed student_assignment
-- =========================================================

INSERT INTO student_assignment (
  offering_id,
  student_id,
  submission_status,
  accepted_problem_count,
  submitted_problem_count,
  problem_count,
  created_at,
  updated_at
)
SELECT
  ao.id AS offering_id,
  cm.student_id,
  'NOT_STARTED' AS submission_status,
  0 AS accepted_problem_count,
  0 AS submitted_problem_count,
  COUNT(ap.id) AS problem_count,
  CURRENT_TIMESTAMP(3) AS created_at,
  CURRENT_TIMESTAMP(3) AS updated_at
FROM assignment_offering ao
JOIN class_member cm
  ON cm.class_id = ao.class_id
 AND cm.member_status = 'ACTIVE'
LEFT JOIN assignment_problem ap
  ON ap.offering_id = ao.id
 AND ap.status = 'ACTIVE'
GROUP BY ao.id, cm.student_id
ON DUPLICATE KEY UPDATE
  problem_count = VALUES(problem_count),
  submission_status = CASE
    WHEN student_assignment.submission_status = 'NOT_STARTED' THEN VALUES(submission_status)
    ELSE student_assignment.submission_status
  END,
  updated_at = CURRENT_TIMESTAMP(3);

SELECT 'student_assignment_missing_for_active_roster' AS check_name, COUNT(*) AS row_count
FROM assignment_offering ao
JOIN class_member cm
  ON cm.class_id = ao.class_id
 AND cm.member_status = 'ACTIVE'
LEFT JOIN student_assignment sa
  ON sa.offering_id = ao.id
 AND sa.student_id = cm.student_id
WHERE sa.id IS NULL;

-- =========================================================
-- 5. Backfill grading_submission.student_id to canonical ids
-- =========================================================

-- Pass 1: use grading_submission.student_no when present.
UPDATE grading_submission gs
JOIN student_profile sp
  ON sp.student_no = TRIM(gs.student_no) COLLATE utf8mb4_unicode_ci
SET gs.student_id = sp.id
WHERE gs.student_no IS NOT NULL
  AND TRIM(gs.student_no) <> ''
  AND (gs.student_id IS NULL OR gs.student_id <> sp.id);

-- Pass 2: rewrite legacy numeric student_id values that still store old student.student_id semantics.
UPDATE grading_submission gs
LEFT JOIN student_profile direct ON direct.id = gs.student_id
JOIN student_profile legacy ON legacy.student_no = CAST(gs.student_id AS CHAR(32)) COLLATE utf8mb4_unicode_ci
SET gs.student_id = legacy.id
WHERE gs.student_id IS NOT NULL
  AND direct.id IS NULL;

SELECT 'grading_submission_student_id_orphans_after_backfill' AS check_name, COUNT(*) AS row_count
FROM grading_submission gs
LEFT JOIN student_profile sp ON sp.id = gs.student_id
WHERE gs.student_id IS NOT NULL
  AND sp.id IS NULL;

-- =========================================================
-- 6. Final validation before read-path switch and V22
-- =========================================================

SELECT 'orphan_class_member_student' AS check_name, COUNT(*) AS row_count
FROM class_member cm
LEFT JOIN student_profile sp ON sp.id = cm.student_id
WHERE sp.id IS NULL;

SELECT 'orphan_class_member_class' AS check_name, COUNT(*) AS row_count
FROM class_member cm
LEFT JOIN teaching_class tc ON tc.id = cm.class_id
WHERE tc.id IS NULL;

SELECT 'attempts_missing_student_assignment' AS check_name, COUNT(*) AS row_count
FROM student_problem_attempt spa
LEFT JOIN student_assignment sa
  ON sa.offering_id = spa.offering_id
 AND sa.student_id = spa.student_id
WHERE sa.id IS NULL;

SELECT 'states_missing_student_assignment' AS check_name, COUNT(*) AS row_count
FROM student_problem_state sps
LEFT JOIN student_assignment sa
  ON sa.offering_id = sps.offering_id
 AND sa.student_id = sps.student_id
WHERE sa.id IS NULL;

SELECT 'state_attempt_scope_mismatch' AS check_name, COUNT(*) AS row_count
FROM student_problem_state sps
LEFT JOIN student_problem_attempt spa
  ON spa.id = sps.latest_attempt_id
 AND spa.offering_id = sps.offering_id
 AND spa.problem_id = sps.problem_id
 AND spa.student_id = sps.student_id
WHERE sps.latest_attempt_id IS NOT NULL
  AND spa.id IS NULL;

SELECT 'ready_for_v22_grading_student_fk' AS check_name, COUNT(*) = 0 AS passed
FROM grading_submission gs
LEFT JOIN student_profile sp ON sp.id = gs.student_id
WHERE gs.student_id IS NOT NULL
  AND sp.id IS NULL;
