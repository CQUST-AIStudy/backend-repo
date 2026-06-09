-- =============================================
-- V30 迁移：1.新建AI错误分析报告存储表 2.给teaching_class新增PTA绑定字段+索引
-- =============================================

-- AI 错误分析报告存储表
-- 用于持久化 Python error-analysis 微服务返回的分析结果
CREATE TABLE IF NOT EXISTS ai_error_analysis_report (
                                                        id BIGINT NOT NULL AUTO_INCREMENT,
                                                        analysis_id VARCHAR(64) NOT NULL COMMENT '分析唯一 ID',
    student_no VARCHAR(32) NOT NULL COMMENT '学号',
    experiment_id INT NOT NULL COMMENT '实验 ID',
    experiment_name VARCHAR(256) DEFAULT '' COMMENT '实验名称',
    report_type VARCHAR(32) NOT NULL COMMENT '报告类型：ERROR / LEARNING / WARNING',
    severity VARCHAR(16) DEFAULT 'MEDIUM' COMMENT '严重程度 HIGH/MEDIUM/LOW',
    ai_generated TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1=AI生成 0=规则降级',
    overall_assessment TEXT COMMENT '综合诊断（error分析）',
    error_categories_json JSON COMMENT '错误分类 [{type,count,rootCause,specificIssues,suggestions,isSystemic}]',
    learning_suggestions_json JSON COMMENT '学习建议 [{topic,priority,reason,suggestedResources}]',
    weak_points_json JSON COMMENT '薄弱知识点 [{tagName,severity,reason}]',
    study_plan_json JSON COMMENT '学习计划 [{topic,priority,suggestedResources,estimatedTime}]',
    recommended_problems_json JSON COMMENT '推荐练习方向 [string]',
    summary_message TEXT COMMENT '总结鼓励语',
    warning_type VARCHAR(32) DEFAULT NULL COMMENT '预警类型：FREQUENT_FAILURE/BASIC_SYNTAX/STUCK/DEADLINE_RISK/OK',
    warning_message TEXT COMMENT '给学生看的预警提示',
    teacher_note TEXT COMMENT '给老师看的备注',
    intervention_triggered TINYINT(1) DEFAULT 0 COMMENT '是否触发干预',
    raw_response_json JSON COMMENT '微服务原始返回（调试用）',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_analysis_id (analysis_id),
    KEY idx_student_exp (student_no, experiment_id),
    KEY idx_student_exp_type (student_no, experiment_id, report_type)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI错误分析报告';

-- =============================================
-- 给 teaching_class 批量新增 PTA 绑定相关字段（判断字段不存在才新增，兼容已执行环境）
-- =============================================
-- 1. pta_problem_set_id
SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND column_name = 'pta_problem_set_id'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE teaching_class ADD COLUMN pta_problem_set_id VARCHAR(64) NULL COMMENT ''PTA problem set id'' AFTER pta_keyword',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. pta_problem_set_name
SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND column_name = 'pta_problem_set_name'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE teaching_class ADD COLUMN pta_problem_set_name VARCHAR(256) NULL COMMENT ''PTA problem set name'' AFTER pta_problem_set_id',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. pta_group_id
SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND column_name = 'pta_group_id'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE teaching_class ADD COLUMN pta_group_id VARCHAR(64) NULL COMMENT ''PTA group id'' AFTER pta_problem_set_name',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4. pta_group_name
SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND column_name = 'pta_group_name'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE teaching_class ADD COLUMN pta_group_name VARCHAR(256) NULL COMMENT ''PTA group name'' AFTER pta_group_id',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5. pta_binding_verified_at
SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND column_name = 'pta_binding_verified_at'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE teaching_class ADD COLUMN pta_binding_verified_at TIMESTAMP(3) NULL DEFAULT NULL COMMENT ''PTA binding verified timestamp'' AFTER pta_group_name',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 6. pta_binding_verify_status
SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND column_name = 'pta_binding_verify_status'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE teaching_class ADD COLUMN pta_binding_verify_status VARCHAR(32) NULL COMMENT ''PTA binding verify status'' AFTER pta_binding_verified_at',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 7. pta_binding_verify_message
SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND column_name = 'pta_binding_verify_message'
);
SET @sql := IF(
  @col = 0,
  'ALTER TABLE teaching_class ADD COLUMN pta_binding_verify_message VARCHAR(512) NULL COMMENT ''PTA binding verify message'' AFTER pta_binding_verify_status',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =============================================
-- 创建PTA绑定联合索引（不存在才创建）
-- =============================================
SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'teaching_class'
    AND index_name = 'idx_teaching_class_pta_binding'
);
SET @sql := IF(
  @idx = 0,
  'CREATE INDEX idx_teaching_class_pta_binding ON teaching_class(pta_problem_set_id, pta_group_id)',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;