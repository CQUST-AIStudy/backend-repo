-- Store PTA problem metadata crawled from /api/problems/{problemId}.
-- This supports the legacy PTA import path, where dynamic scores live in
-- problem_score_detail but problem knowledge points and PTA difficulty come
-- from 爬取结果/{实验名}/题目详情.json.

CREATE TABLE IF NOT EXISTS pta_problem_detail (
  id BIGINT NOT NULL AUTO_INCREMENT,
  experiment_id INT NOT NULL COMMENT 'Legacy experiment.experiment_id',
  experiment_name VARCHAR(200) NOT NULL,
  problem_set_id VARCHAR(64) NULL COMMENT 'PTA problem set id',
  problem_set_problem_id VARCHAR(64) NOT NULL COMMENT 'PTA problem-set-problem id',
  pta_global_problem_id VARCHAR(64) NULL COMMENT 'PTA canonical problem id',
  problem_url VARCHAR(512) NULL,
  problem_label VARCHAR(64) NULL,
  title VARCHAR(256) NULL,
  score DECIMAL(8,2) NULL,
  problem_type VARCHAR(64) NULL,
  difficulty_level TINYINT NULL COMMENT 'PTA raw difficulty number',
  difficulty_label VARCHAR(32) NULL COMMENT 'PTA difficulty label, e.g. 简单/中等/困难',
  problem_pool_index INT NULL,
  index_in_problem_pool INT NULL,
  knowledge_path VARCHAR(1024) NULL COMMENT '知识点完整路径，多个路径用 ; 分隔',
  knowledge_leaf VARCHAR(256) NULL COMMENT '叶子知识点，多个用 ; 分隔',
  knowledge_point_ids JSON NULL,
  knowledge_points_json JSON NULL,
  content LONGTEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_pta_problem_detail_psp (experiment_id, problem_set_problem_id),
  KEY idx_pta_problem_detail_experiment (experiment_id),
  KEY idx_pta_problem_detail_global (pta_global_problem_id),
  KEY idx_pta_problem_detail_label (experiment_id, problem_label),
  KEY idx_pta_problem_detail_knowledge_leaf (knowledge_leaf),
  KEY idx_pta_problem_detail_difficulty (difficulty_label)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PTA题目元数据：知识点、难度、题面';

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'problem_score_detail'
    AND column_name = 'pta_global_problem_id'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE problem_score_detail ADD COLUMN pta_global_problem_id VARCHAR(64) NULL AFTER problem_label',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'problem_score_detail'
    AND column_name = 'knowledge_path'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE problem_score_detail ADD COLUMN knowledge_path VARCHAR(1024) NULL AFTER problem_type',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'problem_score_detail'
    AND column_name = 'knowledge_leaf'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE problem_score_detail ADD COLUMN knowledge_leaf VARCHAR(256) NULL AFTER knowledge_path',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'problem_score_detail'
    AND column_name = 'difficulty_level'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE problem_score_detail ADD COLUMN difficulty_level TINYINT NULL AFTER knowledge_leaf',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'problem_score_detail'
    AND column_name = 'difficulty_label'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE problem_score_detail ADD COLUMN difficulty_label VARCHAR(32) NULL AFTER difficulty_level',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'problem_score_detail'
    AND index_name = 'idx_psd_knowledge_leaf'
);
SET @sql := IF(
  @idx = 0,
  'CREATE INDEX idx_psd_knowledge_leaf ON problem_score_detail(knowledge_leaf)',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'problem_score_detail'
    AND index_name = 'idx_psd_difficulty'
);
SET @sql := IF(
  @idx = 0,
  'CREATE INDEX idx_psd_difficulty ON problem_score_detail(difficulty_label)',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
