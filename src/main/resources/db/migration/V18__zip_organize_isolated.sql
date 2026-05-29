CREATE TABLE IF NOT EXISTS zip_organize_job (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  progress INT NOT NULL DEFAULT 0,
  error_message TEXT,
  retry_count INT NOT NULL DEFAULT 0,
  started_at TIMESTAMP(3) NULL,
  finished_at TIMESTAMP(3) NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  current_step VARCHAR(32) NULL,
  step_detail TEXT NULL,
  original_filename VARCHAR(512) NOT NULL,
  input_object_key TEXT NOT NULL,
  zip_object_key TEXT NULL,
  report_object_key TEXT NULL,
  result_json JSON NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT fk_zip_organize_job_user FOREIGN KEY (user_id) REFERENCES tap_user(id) ON DELETE CASCADE,
  CONSTRAINT chk_zip_organize_job_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED'))
) ENGINE=InnoDB;

CREATE INDEX idx_zip_organize_job_user_created ON zip_organize_job(user_id, created_at DESC);
CREATE INDEX idx_zip_organize_job_status_created ON zip_organize_job(status, created_at);

CREATE TABLE IF NOT EXISTS zip_organize_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  job_id BIGINT NOT NULL,
  original_path TEXT NOT NULL,
  filename VARCHAR(512) NOT NULL,
  content_type VARCHAR(128),
  size_bytes BIGINT NOT NULL DEFAULT 0,
  sha256 VARCHAR(64),
  ext VARCHAR(32),
  object_key TEXT NOT NULL,
  extract_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  extracted_text_preview TEXT,
  title_candidate VARCHAR(512),
  doc_kind VARCHAR(32),
  topic VARCHAR(256),
  keywords_json JSON NULL,
  summary_zh TEXT,
  year_value VARCHAR(16),
  confidence DOUBLE DEFAULT 0,
  review_flag BOOLEAN NOT NULL DEFAULT FALSE,
  review_reason VARCHAR(256),
  target_folder VARCHAR(512),
  new_filename VARCHAR(512),
  duplicate_group_id VARCHAR(64),
  final_path VARCHAR(1024),
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_zip_organize_item_job FOREIGN KEY (job_id) REFERENCES zip_organize_job(id) ON DELETE CASCADE,
  CONSTRAINT chk_zip_organize_extract_status CHECK (extract_status IN ('PENDING','EXTRACTED','EMPTY','FAILED'))
) ENGINE=InnoDB;

CREATE INDEX idx_zip_organize_item_job ON zip_organize_item(job_id);
