-- Grading module enhancements: custom prompt, score range, final review, class_name, student_no

-- 1. Add custom_prompt to grading_rubric
ALTER TABLE grading_rubric ADD COLUMN custom_prompt TEXT AFTER description;

-- 2. Add score range to grading_task
ALTER TABLE grading_task ADD COLUMN score_range_min DECIMAL(5,1) DEFAULT NULL AFTER rubric_id;
ALTER TABLE grading_task ADD COLUMN score_range_max DECIMAL(5,1) DEFAULT NULL AFTER score_range_min;

-- 3. Add class_name, student_no, final_review_comment to grading_submission
ALTER TABLE grading_submission ADD COLUMN class_name VARCHAR(256) DEFAULT NULL AFTER student_name;
ALTER TABLE grading_submission ADD COLUMN student_no VARCHAR(64) DEFAULT NULL AFTER class_name;
ALTER TABLE grading_submission ADD COLUMN final_review_comment TEXT AFTER error_message;
