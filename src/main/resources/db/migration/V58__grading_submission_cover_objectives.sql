-- V58: Persist VLM-recognized cover-page course-objective table on grading_submission.
-- The grading worker reads the report's first page with a VLM (parse_rubric task) and
-- extracts the 课程目标 table (each 目标's label, max score and level ranges). The backend
-- uses this to reliably map rubric dimensions onto the correct cover-page objective rows
-- instead of guessing by order, and to fill the cover table with authoritative max scores.

ALTER TABLE grading_submission
  ADD COLUMN cover_objectives_json LONGTEXT NULL;
