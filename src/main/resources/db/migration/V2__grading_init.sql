-- AI Grading Module tables

CREATE TABLE IF NOT EXISTS grading_rubric (
  id BIGINT NOT NULL AUTO_INCREMENT,
  teacher_id BIGINT NOT NULL,
  name VARCHAR(256) NOT NULL,
  subject VARCHAR(128),
  description TEXT,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_grading_rubric_teacher FOREIGN KEY (teacher_id) REFERENCES tap_user(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS rubric_dimension (
  id BIGINT NOT NULL AUTO_INCREMENT,
  rubric_id BIGINT NOT NULL,
  name VARCHAR(256) NOT NULL,
  description TEXT,
  max_score DECIMAL(5,1) NOT NULL,
  weight INT NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT fk_rubric_dimension_rubric FOREIGN KEY (rubric_id) REFERENCES grading_rubric(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS grading_task (
  id BIGINT NOT NULL AUTO_INCREMENT,
  teacher_id BIGINT NOT NULL,
  experiment_id BIGINT,
  class_id BIGINT,
  rubric_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  total_count INT NOT NULL DEFAULT 0,
  completed_count INT NOT NULL DEFAULT 0,
  failed_count INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_grading_task_teacher FOREIGN KEY (teacher_id) REFERENCES tap_user(id),
  CONSTRAINT fk_grading_task_rubric FOREIGN KEY (rubric_id) REFERENCES grading_rubric(id),
  CONSTRAINT chk_grading_task_status CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED'))
) ENGINE=InnoDB;

CREATE INDEX idx_grading_task_teacher ON grading_task(teacher_id);
CREATE INDEX idx_grading_task_status ON grading_task(status);


CREATE TABLE IF NOT EXISTS grading_submission (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  student_id BIGINT,
  student_name VARCHAR(128),
  pdf_object_key TEXT NOT NULL,
  original_filename VARCHAR(512),
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  total_score DECIMAL(6,2),
  error_message TEXT,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_grading_submission_task FOREIGN KEY (task_id) REFERENCES grading_task(id) ON DELETE CASCADE,
  CONSTRAINT chk_grading_submission_status CHECK (status IN ('PENDING','PROCESSING','SCORED','FAILED','NEED_MORE_EVIDENCE'))
) ENGINE=InnoDB;

CREATE INDEX idx_grading_submission_task ON grading_submission(task_id);

CREATE TABLE IF NOT EXISTS evidence_block (
  id BIGINT NOT NULL AUTO_INCREMENT,
  submission_id BIGINT NOT NULL,
  evidence_id VARCHAR(64) NOT NULL,
  kind VARCHAR(16) NOT NULL,
  page INT,
  bbox_json JSON,
  content TEXT,
  confidence DECIMAL(4,3),
  image_key TEXT,
  metadata_json JSON,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_evidence_block_submission FOREIGN KEY (submission_id) REFERENCES grading_submission(id) ON DELETE CASCADE,
  CONSTRAINT chk_evidence_block_kind CHECK (kind IN ('text','ocr','vlm','vlm_failed'))
) ENGINE=InnoDB;

CREATE UNIQUE INDEX uq_evidence_block_evidence_id ON evidence_block(evidence_id);
CREATE INDEX idx_evidence_block_submission ON evidence_block(submission_id);

CREATE TABLE IF NOT EXISTS score_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  submission_id BIGINT NOT NULL,
  dimension_id BIGINT NOT NULL,
  score DECIMAL(5,1),
  max_score DECIMAL(5,1) NOT NULL,
  weight INT NOT NULL,
  comment TEXT,
  evidence_ids_json JSON,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_score_item_submission FOREIGN KEY (submission_id) REFERENCES grading_submission(id) ON DELETE CASCADE,
  CONSTRAINT fk_score_item_dimension FOREIGN KEY (dimension_id) REFERENCES rubric_dimension(id),
  CONSTRAINT chk_score_item_status CHECK (status IN ('PENDING','SCORED','NEED_MORE_EVIDENCE'))
) ENGINE=InnoDB;

CREATE INDEX idx_score_item_submission ON score_item(submission_id);

CREATE TABLE IF NOT EXISTS score_override (
  id BIGINT NOT NULL AUTO_INCREMENT,
  score_item_id BIGINT NOT NULL,
  teacher_id BIGINT NOT NULL,
  old_score DECIMAL(5,1),
  new_score DECIMAL(5,1) NOT NULL,
  old_comment TEXT,
  new_comment TEXT,
  reason TEXT,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_score_override_score_item FOREIGN KEY (score_item_id) REFERENCES score_item(id) ON DELETE CASCADE,
  CONSTRAINT fk_score_override_teacher FOREIGN KEY (teacher_id) REFERENCES tap_user(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS grading_trace (
  id BIGINT NOT NULL AUTO_INCREMENT,
  submission_id BIGINT NOT NULL,
  step VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  duration_ms BIGINT,
  model_used VARCHAR(64),
  input_tokens INT,
  output_tokens INT,
  error_message TEXT,
  metadata_json JSON,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_grading_trace_submission FOREIGN KEY (submission_id) REFERENCES grading_submission(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE INDEX idx_grading_trace_submission ON grading_trace(submission_id);

CREATE TABLE IF NOT EXISTS report_file (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  submission_id BIGINT,
  file_type VARCHAR(8) NOT NULL,
  object_key TEXT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_report_file_task FOREIGN KEY (task_id) REFERENCES grading_task(id) ON DELETE CASCADE,
  CONSTRAINT fk_report_file_submission FOREIGN KEY (submission_id) REFERENCES grading_submission(id) ON DELETE SET NULL,
  CONSTRAINT chk_report_file_type CHECK (file_type IN ('pdf','zip'))
) ENGINE=InnoDB;
