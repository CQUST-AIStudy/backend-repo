-- 学生端「每题代码执行演示」缓存表：按 (学生, 实验, 题号) 缓存一次最新生成的演示分镜。
-- 注：原为 V68，因 origin/main 已占用 V68__pta_problem_set_sync_state，改为 V69 避免 Flyway 版本撞号。
CREATE TABLE IF NOT EXISTS student_code_demo (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    student_profile_id  BIGINT NOT NULL,
    offering_id         BIGINT NOT NULL,
    problem_no          VARCHAR(128) NOT NULL,
    source_code         LONGTEXT,
    stdin_text          TEXT,
    frames_json         LONGTEXT,
    workflow            VARCHAR(32),
    title               VARCHAR(512),
    explanation         TEXT,
    error_line          INT NOT NULL DEFAULT 0,
    status              VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_student_code_demo UNIQUE (student_profile_id, offering_id, problem_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生端每题代码执行演示缓存';
