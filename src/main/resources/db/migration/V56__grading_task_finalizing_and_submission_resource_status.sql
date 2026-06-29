-- 扩展任务状态字段长度，确保能容纳 FINALIZING 等新状态
ALTER TABLE grading_task
  MODIFY COLUMN status VARCHAR(24) NOT NULL DEFAULT 'PENDING';

-- 新增提交级资源预生成状态
ALTER TABLE grading_submission
  ADD COLUMN annotated_report_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  ADD COLUMN error_demonstrations_status VARCHAR(24) NOT NULL DEFAULT 'PENDING';
