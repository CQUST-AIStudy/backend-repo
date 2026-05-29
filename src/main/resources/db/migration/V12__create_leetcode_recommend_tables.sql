-- ========== LeetCode 个性化推荐系统数据表 ==========
-- 创建时间: 2026-03-12
-- 版本: v1.0

-- 题库主表
CREATE TABLE IF NOT EXISTS leetcode_problem_bank (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_key VARCHAR(64) NOT NULL COMMENT '如 id:1234 / title:xxx',
    problem_code VARCHAR(32) NULL COMMENT '原始题号，如 LCR 002',
    numeric_id INT NULL COMMENT '纯数字题号',
    title_main VARCHAR(255) NOT NULL,
    title_alt VARCHAR(255) NULL,
    problem_text MEDIUMTEXT NOT NULL,
    solution_text MEDIUMTEXT NOT NULL,
    source_url VARCHAR(600) NULL,
    difficulty ENUM('Easy','Medium','Hard','Unknown') NOT NULL DEFAULT 'Unknown',
    estimated_minutes INT NOT NULL DEFAULT 30,
    quality_score DECIMAL(5,4) NOT NULL DEFAULT 0.8000,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_source_key (source_key),
    KEY idx_numeric_id (numeric_id),
    KEY idx_difficulty (difficulty),
    KEY idx_quality (quality_score)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LeetCode题库主表';

-- 题目标签表
CREATE TABLE IF NOT EXISTS leetcode_problem_tag (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    problem_id BIGINT NOT NULL,
    tag_name VARCHAR(64) NOT NULL,
    tag_category ENUM('algorithm','data_structure','technique') NOT NULL,
    relevance_score DECIMAL(5,4) NOT NULL DEFAULT 1.0000,
    is_primary TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_problem_tag (problem_id, tag_name),
    KEY idx_problem (problem_id),
    KEY idx_tag (tag_name),
    CONSTRAINT fk_problem_tag_problem
      FOREIGN KEY (problem_id) REFERENCES leetcode_problem_bank(id)
      ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题目标签表';

-- 学生技能状态表（画像核心）
CREATE TABLE IF NOT EXISTS student_skill_state (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    student_id INT NOT NULL,
    tag_name VARCHAR(64) NOT NULL,
    mastery_score DECIMAL(5,2) NOT NULL DEFAULT 50.00 COMMENT '0~100',
    forgetting_score DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '0~100',
    confidence_score DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '0~100',
    attempt_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    avg_attempts_to_success DECIMAL(8,3) NULL,
    last_practice_at DATETIME NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_student_tag (student_id, tag_name),
    KEY idx_student (student_id),
    KEY idx_student_mastery (student_id, mastery_score),
    KEY idx_student_forgetting (student_id, forgetting_score)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生技能状态表';

-- 推荐请求表
CREATE TABLE IF NOT EXISTS leetcode_recommend_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id CHAR(36) NOT NULL,
    student_id INT NOT NULL,
    scene VARCHAR(32) NOT NULL DEFAULT 'default',
    request_limit INT NOT NULL DEFAULT 20,
    status ENUM('pending','completed','failed') NOT NULL DEFAULT 'pending',
    error_message VARCHAR(512) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at DATETIME NULL,
    UNIQUE KEY uk_request_id (request_id),
    KEY idx_student_created (student_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐请求表';

-- 推荐结果表
CREATE TABLE IF NOT EXISTS leetcode_recommend_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id CHAR(36) NOT NULL,
    student_id INT NOT NULL,
    rank_no INT NOT NULL,
    problem_id BIGINT NOT NULL,
    score_total DECIMAL(8,4) NOT NULL,
    score_need_match DECIMAL(8,4) NOT NULL,
    score_difficulty_fit DECIMAL(8,4) NOT NULL,
    score_success_prob DECIMAL(8,4) NOT NULL,
    score_novelty DECIMAL(8,4) NOT NULL,
    score_quality DECIMAL(8,4) NOT NULL,
    reason_text VARCHAR(512) NOT NULL,
    reason_json JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_request_rank (request_id, rank_no),
    KEY idx_request (request_id),
    KEY idx_student_created (student_id, created_at),
    CONSTRAINT fk_recommend_item_problem
      FOREIGN KEY (problem_id) REFERENCES leetcode_problem_bank(id)
      ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐结果表';

-- 反馈行为表
CREATE TABLE IF NOT EXISTS leetcode_recommend_feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id CHAR(36) NOT NULL,
    student_id INT NOT NULL,
    problem_id BIGINT NOT NULL,
    session_id VARCHAR(64) NULL,
    action ENUM('exposure','click','start','complete','skip','dislike') NOT NULL,
    action_at DATETIME NOT NULL,
    extra_json JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_student_time (student_id, action_at),
    KEY idx_request (request_id),
    KEY idx_problem_time (problem_id, action_at),
    CONSTRAINT fk_recommend_feedback_problem
      FOREIGN KEY (problem_id) REFERENCES leetcode_problem_bank(id)
      ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='反馈行为表';