-- PTA 批改结果：按 (offering, 学生) 存客观分 + AI 评语，可发布给学生。
CREATE TABLE IF NOT EXISTS pta_grading_result (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    offering_id     BIGINT NOT NULL,
    problem_set_id  VARCHAR(64),
    student_id      BIGINT NOT NULL,
    student_no      VARCHAR(128) NOT NULL,
    student_name    VARCHAR(255),
    score           DECIMAL(5,2),
    ac_rate         DECIMAL(5,2),
    problem_count   INT NOT NULL DEFAULT 0,
    accepted_count  INT NOT NULL DEFAULT 0,
    comment         TEXT,
    detail_json     LONGTEXT,
    status          VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    published       TINYINT(1) NOT NULL DEFAULT 0,
    published_at    DATETIME NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pta_grading (offering_id, student_id),
    INDEX idx_pta_grading_offering_pub (offering_id, published),
    INDEX idx_pta_grading_student_no (student_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PTA批改结果(客观分+AI评语,可发布)';
