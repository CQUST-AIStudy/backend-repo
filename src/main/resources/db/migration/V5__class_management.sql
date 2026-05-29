-- 多班级管理系统

CREATE TABLE IF NOT EXISTS teaching_class (
  id BIGINT NOT NULL AUTO_INCREMENT,
  teacher_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL COMMENT '班级名称',
  class_code VARCHAR(32) NOT NULL COMMENT '唯一班级号',
  join_password VARCHAR(64) NOT NULL COMMENT '加入密码',
  grade VARCHAR(16) COMMENT '年级，如 2023',
  course_name VARCHAR(128) COMMENT '课程名称',
  description TEXT COMMENT '班级描述',
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_teaching_class_teacher FOREIGN KEY (teacher_id) REFERENCES tap_user(id),
  CONSTRAINT uq_class_code UNIQUE (class_code)
) ENGINE=InnoDB;

CREATE INDEX idx_teaching_class_teacher ON teaching_class(teacher_id);

CREATE TABLE IF NOT EXISTS class_student (
  id BIGINT NOT NULL AUTO_INCREMENT,
  class_id BIGINT NOT NULL,
  student_name VARCHAR(64) NOT NULL COMMENT '学生姓名',
  student_num VARCHAR(32) COMMENT '学号',
  user_id BIGINT COMMENT '关联 tap_user（可选）',
  joined_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_class_student_class FOREIGN KEY (class_id) REFERENCES teaching_class(id) ON DELETE CASCADE,
  CONSTRAINT uq_class_student UNIQUE (class_id, student_num)
) ENGINE=InnoDB;

CREATE INDEX idx_class_student_class ON class_student(class_id);
CREATE INDEX idx_class_student_user ON class_student(user_id);
