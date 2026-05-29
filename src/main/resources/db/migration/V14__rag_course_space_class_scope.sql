CREATE TABLE IF NOT EXISTS course_space_class (
  id BIGINT NOT NULL AUTO_INCREMENT,
  course_space_id BIGINT NOT NULL,
  class_id BIGINT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_course_space_class_space
    FOREIGN KEY (course_space_id) REFERENCES course_space(id) ON DELETE CASCADE,
  CONSTRAINT fk_course_space_class_class
    FOREIGN KEY (class_id) REFERENCES teaching_class(id) ON DELETE CASCADE,
  CONSTRAINT uq_course_space_class UNIQUE (course_space_id, class_id)
) ENGINE=InnoDB;

CREATE INDEX idx_course_space_class_space ON course_space_class(course_space_id);
CREATE INDEX idx_course_space_class_class ON course_space_class(class_id);
