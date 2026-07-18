-- 学生站内通知（当前仅覆盖“成绩已发布”一种类型，轮询读取）。
-- 以学号（student_no）为收件人主键，与已发布成绩读路径保持同一学生标识口径。
CREATE TABLE notification (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    recipient_student_no VARCHAR(64)  NOT NULL COMMENT '收件学生学号（对应 student_profile.student_no）',
    type                 VARCHAR(32)  NOT NULL COMMENT '通知类型，如 GRADE_PUBLISHED',
    title                VARCHAR(255) NOT NULL COMMENT '通知标题',
    content              TEXT         NULL COMMENT '通知正文',
    link_experiment_id   BIGINT       NULL COMMENT '跳转目标：学生实验列表里的 id（assignment_offering.id）',
    read_at              DATETIME     NULL COMMENT '已读时间，NULL 表示未读',
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_notification_recipient_unread (recipient_student_no, read_at),
    KEY idx_notification_recipient_created (recipient_student_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学生站内通知';
