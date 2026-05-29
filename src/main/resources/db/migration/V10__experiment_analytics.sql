-- 每题每人得分明细（从 PAPER_TRANSCRIPT.xlsx 解析）
-- 用于计算正答率、分数分布、题目难度分析等
CREATE TABLE IF NOT EXISTS problem_score_detail (
  id BIGINT NOT NULL AUTO_INCREMENT,
  experiment_id INT NOT NULL COMMENT '实验ID',
  student_id VARCHAR(32) NOT NULL COMMENT '学号',
  student_name VARCHAR(64) DEFAULT '' COMMENT '姓名',
  problem_label VARCHAR(32) NOT NULL COMMENT '题目标号，如 2-1, 7-1',
  problem_type VARCHAR(32) DEFAULT '' COMMENT '题目类型，如 单选题、编程题',
  max_score DECIMAL(8,2) DEFAULT 0 COMMENT '该题满分',
  actual_score DECIMAL(8,2) DEFAULT 0 COMMENT '实际得分',
  total_score DECIMAL(8,2) DEFAULT 0 COMMENT '总分',
  ranking INT DEFAULT 0 COMMENT '排名',
  PRIMARY KEY (id),
  UNIQUE KEY uq_psd (experiment_id, student_id, problem_label)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题目得分明细';

-- 使用 IF NOT EXISTS 避免重复创建索引报错
SET @exist := (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'problem_score_detail' AND index_name = 'idx_psd_exp');
SET @sql := IF(@exist = 0, 'CREATE INDEX idx_psd_exp ON problem_score_detail(experiment_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'problem_score_detail' AND index_name = 'idx_psd_student');
SET @sql := IF(@exist = 0, 'CREATE INDEX idx_psd_student ON problem_score_detail(student_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'problem_score_detail' AND index_name = 'idx_psd_label');
SET @sql := IF(@exist = 0, 'CREATE INDEX idx_psd_label ON problem_score_detail(problem_label)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
