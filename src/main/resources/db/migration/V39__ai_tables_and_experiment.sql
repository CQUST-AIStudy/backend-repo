-- =============================================
-- V39 迁移：AI 相关数据表 + 实验表（由远端 V35/V36 重编号合并而来，全部为幂等建表语句）
-- 根据后端实体 AiErrorAnalysisReport / AIRemarks / AISuggestedProblem / Experiment
-- 统一创建对应的四张数据库表
-- =============================================

-- 1. AI 评语表（对应 AIRemarks 实体）
CREATE TABLE IF NOT EXISTS `ai_remarks` (
    `student_id` VARCHAR(50) NOT NULL COMMENT '学号',
    `student_name` VARCHAR(50) COMMENT '学生姓名',
    `experiment_id` INT NOT NULL COMMENT '实验 ID',
    `experiment_name` VARCHAR(200) COMMENT '实验名称',
    `airemark` LONGTEXT COMMENT 'AI 生成备注/评语',
    PRIMARY KEY (`student_id`, `experiment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI评语表';

-- 2. AI 推荐题目表（对应 AISuggestedProblem 实体）
CREATE TABLE IF NOT EXISTS `ai_suggested_problems` (
    `student_id` VARCHAR(50) NOT NULL COMMENT '学号',
    `student_name` VARCHAR(50) COMMENT '学生姓名',
    `experiment_id` INT NOT NULL COMMENT '实验 ID',
    `suggested_problems` LONGTEXT COMMENT 'AI推荐题目内容',
    PRIMARY KEY (`student_id`, `experiment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI推荐题目表';

-- 3. 实验表（对应 Experiment 实体，含 V28 新增 class 字段）
CREATE TABLE IF NOT EXISTS `experiment` (
    `experiment_id` INT AUTO_INCREMENT PRIMARY KEY,
    `num` INT COMMENT '实验编号',
    `name` VARCHAR(200) NOT NULL COMMENT '实验名称',
    `deadline` DATETIME COMMENT '截止时间',
    `describe` TEXT COMMENT '实验描述',
    `requirements` TEXT COMMENT '实验要求',
    `topic_sum` INT DEFAULT 0 COMMENT '题目总数',
    `teacher_id` VARCHAR(50) COMMENT '教师ID',
    `class` VARCHAR(128) DEFAULT NULL COMMENT '班级名称',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实验表';

-- 4. AI 错误分析报告表（对应 AiErrorAnalysisReport 实体）
CREATE TABLE IF NOT EXISTS `ai_error_analysis_report` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `analysis_id` VARCHAR(64) NOT NULL COMMENT '分析唯一ID',
    `student_no` VARCHAR(32) NOT NULL COMMENT '学号',
    `experiment_id` INT NOT NULL COMMENT '实验ID',
    `experiment_name` VARCHAR(256) DEFAULT '' COMMENT '实验名称',
    `report_type` VARCHAR(32) NOT NULL COMMENT '报告类型: ERROR / LEARNING / WARNING',
    `severity` VARCHAR(16) DEFAULT 'MEDIUM' COMMENT '严重程度: HIGH / MEDIUM / LOW',
    `ai_generated` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1=AI生成 0=规则降级',
    `overall_assessment` TEXT COMMENT '综合诊断',
    `error_categories_json` JSON COMMENT '错误分类 JSON',
    `learning_suggestions_json` JSON COMMENT '学习建议 JSON',
    `weak_points_json` JSON COMMENT '薄弱知识点 JSON',
    `study_plan_json` JSON COMMENT '学习计划 JSON',
    `recommended_problems_json` JSON COMMENT '推荐练习 JSON',
    `summary_message` TEXT COMMENT '总结鼓励语',
    `warning_type` VARCHAR(32) DEFAULT NULL COMMENT '预警类型',
    `warning_message` TEXT COMMENT '学生预警提示',
    `teacher_note` TEXT COMMENT '教师备注',
    `intervention_triggered` TINYINT(1) DEFAULT 0 COMMENT '是否触发干预',
    `raw_response_json` JSON COMMENT '微服务原始返回',
    `created_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_analysis_id` (`analysis_id`),
    KEY `idx_student_exp` (`student_no`, `experiment_id`),
    KEY `idx_student_exp_type` (`student_no`, `experiment_id`, `report_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI错误分析报告表';
