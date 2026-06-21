CREATE TABLE IF NOT EXISTS pta_crawl_job (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key for one backend-to-spider crawl request',
  class_id BIGINT NULL COMMENT 'Teaching class that requested this crawl; nullable when the class is deleted',
  keyword VARCHAR(255) NOT NULL COMMENT 'PTA problem-set search keyword used for this crawl',
  mode VARCHAR(32) NOT NULL DEFAULT 'incremental' COMMENT 'Spider crawl mode: incremental, submissions, refresh, or full',
  trigger_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'Who triggered the crawl: MANUAL or SCHEDULED',
  credential_source VARCHAR(32) NULL COMMENT 'Credential source sent to the spider, such as bound, temporary, or cookie',
  spider_task_id VARCHAR(64) NULL COMMENT 'Task id returned by the spider service for callback correlation',
  status VARCHAR(32) NOT NULL DEFAULT 'REQUESTING' COMMENT 'Backend-visible crawl status for tracking and recovery',
  status_code VARCHAR(64) NOT NULL DEFAULT 'REQUESTING' COMMENT 'Detailed machine-readable reason such as SPIDER_ACCEPTED or CALLBACK_SUCCESS',
  request_json JSON NULL COMMENT 'Sanitized request payload sent to the spider; passwords are masked',
  response_json JSON NULL COMMENT 'Raw response payload returned by the spider service',
  message VARCHAR(1024) NULL COMMENT 'Human-readable status message for operators and dashboards',
  error_message TEXT NULL COMMENT 'Error detail when the backend cannot reach or trigger the spider',
  requested_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Time when the backend started this crawl request',
  accepted_at TIMESTAMP(3) NULL DEFAULT NULL COMMENT 'Time when the spider accepted or deduplicated the request',
  finished_at TIMESTAMP(3) NULL DEFAULT NULL COMMENT 'Time when the crawl was completed, failed, blocked, or deduplicated',
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last update time for this tracking row',
  PRIMARY KEY (id),
  CONSTRAINT fk_pta_crawl_job_class FOREIGN KEY (class_id) REFERENCES teaching_class(id) ON DELETE SET NULL,
  CONSTRAINT chk_pta_crawl_job_status CHECK (
    status IN ('REQUESTING', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'BLOCKED', 'DEDUPED', 'UNREACHABLE')
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PTA spider request and status tracking';

CREATE INDEX idx_pta_crawl_job_class_status ON pta_crawl_job(class_id, status);
CREATE INDEX idx_pta_crawl_job_spider_task ON pta_crawl_job(spider_task_id);
CREATE INDEX idx_pta_crawl_job_keyword_mode ON pta_crawl_job(keyword, mode, requested_at);
