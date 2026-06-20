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

SET @create_batch_teacher_index_sql := (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.statistics
      WHERE table_schema = DATABASE()
        AND table_name = 'grading_batch'
        AND index_name = 'idx_grading_batch_teacher'
    ),
    'SELECT 1',
    'CREATE INDEX idx_grading_batch_teacher ON grading_batch(teacher_id)'
  )
);
PREPARE create_batch_teacher_index_stmt FROM @create_batch_teacher_index_sql;
EXECUTE create_batch_teacher_index_stmt;
DEALLOCATE PREPARE create_batch_teacher_index_stmt;

SET @add_task_batch_column_sql := (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'grading_task'
        AND column_name = 'batch_id'
    ),
    'SELECT 1',
    'ALTER TABLE grading_task ADD COLUMN batch_id BIGINT NULL'
  )
);
PREPARE add_task_batch_column_stmt FROM @add_task_batch_column_sql;
EXECUTE add_task_batch_column_stmt;
DEALLOCATE PREPARE add_task_batch_column_stmt;

SET @add_task_batch_fk_sql := (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.table_constraints
      WHERE table_schema = DATABASE()
        AND table_name = 'grading_task'
        AND constraint_name = 'fk_grading_task_batch'
    ),
    'SELECT 1',
    'ALTER TABLE grading_task ADD CONSTRAINT fk_grading_task_batch FOREIGN KEY (batch_id) REFERENCES grading_batch(id) ON DELETE SET NULL'
  )
);
PREPARE add_task_batch_fk_stmt FROM @add_task_batch_fk_sql;
EXECUTE add_task_batch_fk_stmt;
DEALLOCATE PREPARE add_task_batch_fk_stmt;

SET @create_task_batch_index_sql := (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.statistics
      WHERE table_schema = DATABASE()
        AND table_name = 'grading_task'
        AND index_name = 'idx_grading_task_batch'
    ),
    'SELECT 1',
    'CREATE INDEX idx_grading_task_batch ON grading_task(batch_id)'
  )
);
PREPARE create_task_batch_index_stmt FROM @create_task_batch_index_sql;
EXECUTE create_task_batch_index_stmt;
DEALLOCATE PREPARE create_task_batch_index_stmt;
