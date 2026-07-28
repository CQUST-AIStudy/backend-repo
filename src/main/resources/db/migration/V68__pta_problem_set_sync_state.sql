CREATE TABLE IF NOT EXISTS pta_problem_set_sync_state (
  id BIGINT NOT NULL AUTO_INCREMENT,
  class_id BIGINT NOT NULL,
  pta_problem_set_id VARCHAR(64) NOT NULL,
  problem_set_name VARCHAR(255) NOT NULL,
  deadline_at TIMESTAMP(3) NULL,
  sync_state VARCHAR(32) NOT NULL DEFAULT 'NEW',
  content_complete TINYINT(1) NOT NULL DEFAULT 0,
  transcript_complete TINYINT(1) NOT NULL DEFAULT 0,
  answer_complete TINYINT(1) NOT NULL DEFAULT 0,
  submission_complete TINYINT(1) NOT NULL DEFAULT 0,
  last_submission_cursor VARCHAR(128) NULL,
  last_dynamic_sync_at TIMESTAMP(3) NULL,
  finalized_at TIMESTAMP(3) NULL,
  last_success_at TIMESTAMP(3) NULL,
  last_error TEXT NULL,
  sync_version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_pta_problem_set_sync_state_class_set
    (class_id, pta_problem_set_id),
  KEY idx_pta_problem_set_sync_state_class_state
    (class_id, sync_state),
  KEY idx_pta_problem_set_sync_state_deadline
    (deadline_at),
  CONSTRAINT chk_pta_problem_set_sync_state_value
    CHECK (
      sync_state IN (
        'NEW',
        'OPEN',
        'CLOSED_PENDING_FINAL',
        'CLOSED_COMPLETE',
        'REPAIR_REQUIRED',
        'FAILED_RETRY'
      )
    )
);
