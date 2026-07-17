CREATE TABLE ai_experiment_report (
  id BIGINT NOT NULL AUTO_INCREMENT,
  offering_id BIGINT NOT NULL,
  student_id BIGINT NOT NULL,
  report_md MEDIUMTEXT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_ai_experiment_report_offering_student UNIQUE (offering_id, student_id),
  CONSTRAINT fk_ai_experiment_report_offering
    FOREIGN KEY (offering_id) REFERENCES assignment_offering(id) ON DELETE CASCADE,
  CONSTRAINT fk_ai_experiment_report_student
    FOREIGN KEY (student_id) REFERENCES student_profile(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
