ALTER TABLE grading_submission
    ADD COLUMN match_status VARCHAR(24) NOT NULL DEFAULT 'UNMATCHED' AFTER final_review_comment,
    ADD COLUMN published_at TIMESTAMP(3) NULL AFTER match_status,
    ADD COLUMN published_by BIGINT NULL AFTER published_at;

UPDATE grading_submission
SET match_status = 'AUTO_CONFIRMED'
WHERE student_id IS NOT NULL;

ALTER TABLE grading_submission
    ADD CONSTRAINT fk_grading_submission_published_by
        FOREIGN KEY (published_by) REFERENCES tap_user(id) ON DELETE SET NULL;

CREATE INDEX idx_grading_submission_task_match
    ON grading_submission(task_id, match_status);

CREATE INDEX idx_grading_submission_student_published
    ON grading_submission(student_id, published_at);
