ALTER TABLE pta_problem_set_sync_state
  ADD COLUMN submission_coverage VARCHAR(32) NOT NULL DEFAULT 'NONE'
    AFTER submission_complete,
  ADD COLUMN submission_truncated TINYINT(1) NOT NULL DEFAULT 0
    AFTER submission_coverage,
  ADD COLUMN submission_gap_detected TINYINT(1) NOT NULL DEFAULT 0
    AFTER submission_truncated,
  ADD COLUMN submission_row_count INT NOT NULL DEFAULT 0
    AFTER submission_gap_detected,
  ADD COLUMN last_fast_sync_at TIMESTAMP(3) NULL
    AFTER last_submission_cursor,
  ADD COLUMN full_history_finalized_at TIMESTAMP(3) NULL
    AFTER last_fast_sync_at;

-- V68 之前的成功提交快照均由逐学生兜底流程产生，可视为完整历史。
UPDATE pta_problem_set_sync_state
SET submission_coverage = 'FULL_HISTORY',
    full_history_finalized_at = COALESCE(finalized_at, last_success_at),
    submission_truncated = 0,
    submission_gap_detected = 0
WHERE submission_complete = 1;

ALTER TABLE pta_problem_set_sync_state
  ADD CONSTRAINT chk_pta_submission_coverage
    CHECK (submission_coverage IN ('NONE', 'LATEST_200', 'FULL_HISTORY'));

ALTER TABLE pta_crawl_job
  ADD COLUMN submission_policy VARCHAR(32) NOT NULL DEFAULT 'LATEST_200'
    AFTER mode;

CREATE INDEX idx_pta_crawl_job_submission_policy
  ON pta_crawl_job(class_id, submission_policy, requested_at);
