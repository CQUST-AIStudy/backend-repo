-- AI 助教「代码演示（手动输入）」历史表：按学生学号存多条生成记录
CREATE TABLE IF NOT EXISTS student_code_playground (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    student_no   VARCHAR(128) NOT NULL,
    title        VARCHAR(512),
    problem_md   TEXT,
    source_code  LONGTEXT,
    stdin_text   TEXT,
    workflow     VARCHAR(32),
    frames_json  LONGTEXT,
    explanation  TEXT,
    error_line   INT NOT NULL DEFAULT 0,
    status       VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_scp_student_created (student_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI助教代码演示(手动输入)历史';
