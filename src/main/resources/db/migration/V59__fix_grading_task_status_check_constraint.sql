-- 修复 CHECK 约束：V56 新增了 FINALIZING 状态但未更新约束，导致写入时违反 chk_grading_task_status
ALTER TABLE grading_task
  DROP CONSTRAINT chk_grading_task_status;

ALTER TABLE grading_task
  ADD CONSTRAINT chk_grading_task_status CHECK (status IN ('PENDING','PROCESSING','FINALIZING','COMPLETED','FAILED'));
