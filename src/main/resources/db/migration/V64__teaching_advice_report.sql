CREATE TABLE IF NOT EXISTS teaching_advice_report (
  id BIGINT NOT NULL AUTO_INCREMENT,
  teacher_id BIGINT NOT NULL,
  scope_level VARCHAR(16) NOT NULL,
  course_id BIGINT NULL,
  term_id BIGINT NULL,
  class_id BIGINT NULL,
  experiment_id BIGINT NULL,
  scope_json LONGTEXT NOT NULL,
  metrics_json LONGTEXT NOT NULL,
  advice_json LONGTEXT NULL,
  prompt_version VARCHAR(64) NOT NULL,
  model VARCHAR(128) NULL,
  status VARCHAR(16) NOT NULL,
  error_message VARCHAR(1000) NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_teaching_advice_teacher FOREIGN KEY (teacher_id) REFERENCES tap_user(id),
  CONSTRAINT fk_teaching_advice_course FOREIGN KEY (course_id) REFERENCES course(id) ON DELETE SET NULL,
  CONSTRAINT fk_teaching_advice_term FOREIGN KEY (term_id) REFERENCES academic_term(id) ON DELETE SET NULL,
  CONSTRAINT fk_teaching_advice_class FOREIGN KEY (class_id) REFERENCES teaching_class(id) ON DELETE SET NULL,
  CONSTRAINT chk_teaching_advice_scope CHECK (scope_level IN ('EXPERIMENT', 'CLASS', 'COURSE')),
  CONSTRAINT chk_teaching_advice_status CHECK (status IN ('COMPLETED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_teaching_advice_teacher_created
  ON teaching_advice_report(teacher_id, created_at);
CREATE INDEX idx_teaching_advice_scope
  ON teaching_advice_report(teacher_id, scope_level, class_id, experiment_id);
