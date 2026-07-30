-- 批改聚合的"每题完整代码"artifact：供代码演示直接取用，避免用碎片证据现拼。
-- 由错误演示生成流程把实际使用的完整代码作为副产物写入（零额外 LLM 调用）。
ALTER TABLE grading_submission
    ADD COLUMN extracted_code_json LONGTEXT NULL COMMENT '批改聚合的每题完整代码(演示取用)';
