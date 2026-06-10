-- V38: Grading batch model (design doc 方案 B)
-- One upload = one batch by default; tasks can also be attached to an existing batch at creation time.

CREATE TABLE IF NOT EXISTS grading_batch (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  teacher_id BIGINT NOT NULL,
  display_code VARCHAR(16) NULL COMMENT 'Human-friendly batch identifier in MMDD-XX format',
  name VARCHAR(128) NOT NULL,
  description TEXT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_grading_batch_teacher FOREIGN KEY (teacher_id) REFERENCES tap_user(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE INDEX idx_grading_batch_teacher ON grading_batch(teacher_id);

ALTER TABLE grading_task
  ADD COLUMN batch_id BIGINT NULL,
  ADD CONSTRAINT fk_grading_task_batch FOREIGN KEY (batch_id) REFERENCES grading_batch(id) ON DELETE SET NULL;

CREATE INDEX idx_grading_task_batch ON grading_task(batch_id);
