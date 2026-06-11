-- =============================================
-- V35 迁移：确保 ai_remarks / ai_suggested_problems / experiment 三张核心数据表存在
-- 用于 Flyway 扫描发现并管理这些表
-- =============================================

-- AI备注表 (爬虫 AI 评语生成)
CREATE TABLE IF NOT EXISTS `ai_remarks` (
    `student_id` VARCHAR(50) NOT NULL COMMENT '学号',
    `student_name` VARCHAR(50) COMMENT '学生姓名',
    `experiment_id` INT NOT NULL COMMENT '实验 ID',
    `experiment_name` VARCHAR(200) COMMENT '实验名称',
    `airemark` LONGTEXT COMMENT 'AI 生成的备注/评语内容',
    PRIMARY KEY (`student_id`, `experiment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 评语表';

-- AI推荐题目表 (爬虫 AI 推荐题目)
CREATE TABLE IF NOT EXISTS `ai_suggested_problems` (
    `student_id` VARCHAR(50) NOT NULL COMMENT '学号',
    `student_name` VARCHAR(50) COMMENT '学生姓名',
    `experiment_id` INT NOT NULL COMMENT '实验 ID',
    `suggested_problems` LONGTEXT COMMENT 'AI 推荐的题目内容',
    PRIMARY KEY (`student_id`, `experiment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 推荐题目表';

-- 实验表 (教学实验/作业)
CREATE TABLE IF NOT EXISTS `experiment` (
    `experiment_id` INT AUTO_INCREMENT PRIMARY KEY,
    `num` INT COMMENT '实验编号',
    `name` VARCHAR(200) NOT NULL COMMENT '实验名称',
    `deadline` DATETIME COMMENT '截止时间',
    `describe` TEXT COMMENT '实验描述',
    `requirements` TEXT COMMENT '实验要求',
    `topic_sum` INT DEFAULT 0 COMMENT '题目总数',
    `teacher_id` VARCHAR(50) COMMENT '教师 ID',
    `class` VARCHAR(100) COMMENT '班级名称（逗号分隔多班级）',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实验表';
