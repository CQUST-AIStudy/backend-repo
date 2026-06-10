CREATE TABLE IF NOT EXISTS teacher_signature (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    teacher_id   BIGINT       NOT NULL,
    signature    VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_teacher_signature UNIQUE (teacher_id, signature),
    CONSTRAINT fk_teacher_signature_teacher FOREIGN KEY (teacher_id) REFERENCES tap_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
