-- V55: Persist error demonstrations JSON on grading_submission
-- Generated once during grading, read from DB on every page load.
-- Regenerated only when scores are overridden or review is regenerated.

ALTER TABLE grading_submission
  ADD COLUMN error_demonstrations_json LONGTEXT NULL;
