-- V40: LeetCode submission record for learning tracking
-- Persists AI judging results from LeetCodeExecutionService.submitSolution()

CREATE TABLE IF NOT EXISTS leetcode_submission_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id INT NOT NULL COMMENT 'FK to student_profile.id',
    problem_id BIGINT NOT NULL COMMENT 'FK to leetcode_problem_bank.id',
    code LONGTEXT COMMENT 'submitted source code',
    language VARCHAR(32) COMMENT 'programming language',
    accepted BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'whether AI judged as accepted',
    score INT COMMENT 'AI score 0-100',
    ai_feedback TEXT COMMENT 'AI evaluation feedback text',
    passed_cases INT DEFAULT 0 COMMENT 'estimated passed test cases',
    total_cases INT DEFAULT 0 COMMENT 'estimated total test cases',
    confidence DOUBLE COMMENT 'AI confidence 0.0-1.0',
    recommendation_request_id VARCHAR(64) COMMENT 'FK to leetcode_recommend_request.request_id',
    recommendation_session_id VARCHAR(64) COMMENT 'recommendation session identifier',
    submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'when student submitted',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_lsr_student (student_id),
    INDEX idx_lsr_problem (problem_id),
    INDEX idx_lsr_submitted (submitted_at),
    INDEX idx_lsr_request (recommendation_request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
