-- Legacy academic tables required by the original teaching UI and MyBatis mappers.
-- The newer TAP domain tables are maintained by earlier Flyway migrations.

-- 用户表 (AI_Ds 登录系统)
CREATE TABLE IF NOT EXISTS `user` (
    `id` INT AUTO_INCREMENT PRIMARY KEY,
    `username` VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    `password` VARCHAR(100) NOT NULL COMMENT '密码',
    `email` VARCHAR(100) COMMENT '邮箱',
    `role` VARCHAR(20) NOT NULL DEFAULT 'student' COMMENT '角色: student/teacher/admin',
    `usernum` VARCHAR(20) COMMENT '学号或工号',
    `classname` VARCHAR(50) COMMENT '班级名称',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 教师表 (TeacherMapper: teacher_id, teacher_name, username, classroom)
CREATE TABLE IF NOT EXISTS `teacher` (
    `teacher_id` INT AUTO_INCREMENT PRIMARY KEY,
    `teacher_name` VARCHAR(50) COMMENT '教师姓名',
    `username` VARCHAR(50) NOT NULL UNIQUE,
    `classroom` VARCHAR(50) COMMENT '所教班级'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 学生表 (StudentMapper: student_id, username, password, name, class_name, created_at)
CREATE TABLE IF NOT EXISTS `student` (
    `student_id` INT NOT NULL PRIMARY KEY COMMENT '学号',
    `username` VARCHAR(50) UNIQUE,
    `password` VARCHAR(100),
    `name` VARCHAR(50) COMMENT '姓名',
    `class_name` VARCHAR(50) COMMENT '班级',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 实验表 (爬虫: experiment_id, num, name, deadline, describe, requirements, topic_sum, teacher_id)
CREATE TABLE IF NOT EXISTS `experiment` (
    `experiment_id` INT AUTO_INCREMENT PRIMARY KEY,
    `num` INT COMMENT '实验编号',
    `name` VARCHAR(200) NOT NULL COMMENT '实验名称',
    `deadline` DATETIME COMMENT '截止时间',
    `describe` TEXT COMMENT '实验描述',
    `requirements` TEXT COMMENT '实验要求',
    `topic_sum` INT DEFAULT 0 COMMENT '题目总数',
    `teacher_id` VARCHAR(50) COMMENT '教师ID',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 提交表 (SubmissionMapper: submission_id, username, experiment_id, code, report, submit_time)
CREATE TABLE IF NOT EXISTS `submission` (
    `submission_id` INT AUTO_INCREMENT PRIMARY KEY,
    `username` VARCHAR(50) NOT NULL,
    `experiment_id` INT NOT NULL,
    `code` LONGTEXT,
    `report` LONGTEXT,
    `submit_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `serial_number` INT DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 成绩表 (ScoreMapper: score_id, username, real_name, experiment_id, score, submit_time, plagiarism_rate, status, serial_number, num)
CREATE TABLE IF NOT EXISTS `score` (
    `score_id` INT AUTO_INCREMENT PRIMARY KEY,
    `username` VARCHAR(50) NOT NULL,
    `real_name` VARCHAR(50),
    `experiment_id` INT NOT NULL,
    `score` DECIMAL(5,2),
    `submit_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `plagiarism_rate` VARCHAR(20),
    `status` VARCHAR(20) DEFAULT 'pending',
    `serial_number` INT DEFAULT 0,
    `num` INT DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 学生代码表 (爬虫: experiment_id, experiment_name, student_id, student_name, code)
-- StudentMapper 也查询此表: Student_Code WHERE student_id = ? AND experiment_id = ?
CREATE TABLE IF NOT EXISTS `student_code` (
    `experiment_id` INT NOT NULL,
    `experiment_name` VARCHAR(200),
    `student_id` VARCHAR(50) NOT NULL,
    `student_name` VARCHAR(50),
    `code` LONGTEXT,
    PRIMARY KEY (`experiment_id`, `student_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 提交情况表 (爬虫: submit_time, situation, score, serial_number, experiment_id, experiment_name, runtime_ms, memory_kb, student_id, student_name)
CREATE TABLE IF NOT EXISTS `submit_situation` (
    `id` INT AUTO_INCREMENT PRIMARY KEY,
    `submit_time` VARCHAR(50),
    `situation` VARCHAR(50),
    `score` DECIMAL(5,2),
    `serial_number` VARCHAR(50),
    `experiment_id` INT NOT NULL,
    `experiment_name` VARCHAR(200),
    `runtime_ms` VARCHAR(50),
    `memory_kb` VARCHAR(50),
    `student_id` VARCHAR(50) NOT NULL,
    `student_name` VARCHAR(50),
    UNIQUE KEY `uk_submit` (`submit_time`, `serial_number`, `experiment_id`, `student_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 题目集表 (爬虫: experiment_id, experiment_name, problem)
CREATE TABLE IF NOT EXISTS `problems_sets` (
    `experiment_id` INT NOT NULL PRIMARY KEY,
    `experiment_name` VARCHAR(200),
    `problem` LONGTEXT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 题库表 (爬虫: problems_id, problems_content, problems_url)
CREATE TABLE IF NOT EXISTS `tk` (
    `problems_id` INT NOT NULL PRIMARY KEY,
    `problems_content` LONGTEXT,
    `problems_url` VARCHAR(500)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- AI评语表 (爬虫: student_id, student_name, experiment_id, experiment_name, airemark)
-- AIRemarksMapper 查询: AI_remarks WHERE student_id = ? AND experiment_id = ?
CREATE TABLE IF NOT EXISTS `ai_remarks` (
    `student_id` VARCHAR(50) NOT NULL,
    `student_name` VARCHAR(50),
    `experiment_id` INT NOT NULL,
    `experiment_name` VARCHAR(200),
    `airemark` LONGTEXT,
    PRIMARY KEY (`student_id`, `experiment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- AI推荐题目表 (爬虫AI推荐题目.py: student_id, student_name, experiment_id, suggested_problems)
CREATE TABLE IF NOT EXISTS `ai_suggested_problems` (
    `student_id` VARCHAR(50) NOT NULL,
    `student_name` VARCHAR(50),
    `experiment_id` INT NOT NULL,
    `suggested_problems` LONGTEXT,
    PRIMARY KEY (`student_id`, `experiment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- AI PTA推题URL表 (爬虫题库推题入库.py: student_name, PTAURLS)
-- AISuggestedProblemMapper 查询: ai_pta_suggested_url JOIN student
CREATE TABLE IF NOT EXISTS `ai_pta_suggested_url` (
    `student_name` VARCHAR(50) NOT NULL PRIMARY KEY,
    `PTAURLS` LONGTEXT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- AI提交分析表 (爬虫: experiment_id, experiment_name, AI_analysis)
CREATE TABLE IF NOT EXISTS `ai_submission_analysis` (
    `experiment_id` INT NOT NULL PRIMARY KEY,
    `experiment_name` VARCHAR(200),
    `AI_analysis` LONGTEXT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- AI总体提交分析表 (AI提交列表分析.py: total_analysis)
CREATE TABLE IF NOT EXISTS `total_submission_analysis` (
    `id` INT AUTO_INCREMENT PRIMARY KEY,
    `total_analysis` LONGTEXT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 查重表 (ScoreMapper 查询: Plagiarism_Check_Table WHERE student_id = ? AND experiment_id = ?)
CREATE TABLE IF NOT EXISTS `Plagiarism_Check_Table` (
    `id` INT AUTO_INCREMENT PRIMARY KEY,
    `student_id` VARCHAR(50) NOT NULL,
    `experiment_id` INT NOT NULL,
    `Plagiarism_Rate` VARCHAR(20),
    UNIQUE KEY `uk_plagiarism` (`student_id`, `experiment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- AI学习建议缓存表 (DeepSeek生成的能力画像反馈)
CREATE TABLE IF NOT EXISTS `profile_ai_feedback` (
    `student_id` VARCHAR(50) NOT NULL PRIMARY KEY COMMENT '学号',
    `feedback` TEXT COMMENT 'DeepSeek生成的反馈文本',
    `profile_json` TEXT COMMENT '生成时使用的画像摘要JSON',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ========== 测试数据 ==========
INSERT IGNORE INTO `user` (`username`, `password`, `email`, `role`, `usernum`, `classname`) VALUES
('teacher1', '$2a$10$RiIfr7fwVhHVxLwmwK5dnOtDWiEdXtaGatoGeRf9qWjVHckXpNPJe', 'teacher1@example.com', 'teacher', 'T001', '计科23'),
('admin1', '$2a$10$RiIfr7fwVhHVxLwmwK5dnOtDWiEdXtaGatoGeRf9qWjVHckXpNPJe', 'admin1@example.com', 'admin', 'A001', NULL);

INSERT IGNORE INTO `teacher` (`teacher_name`, `username`, `classroom`) VALUES
('张老师', 'teacher1', '计科23');
