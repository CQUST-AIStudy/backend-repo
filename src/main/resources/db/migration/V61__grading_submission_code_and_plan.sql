-- 代码逻辑分析 Agent 产出（findings + summary，AI 判断而非硬匹配）与分层改进建议 Agent 产出。
-- 两列均为旁路增量结果，允许为空；历史数据不回填，仅新批改写入。
ALTER TABLE grading_submission
    ADD COLUMN code_analysis_json    LONGTEXT NULL COMMENT '代码逻辑分析 Agent 产出：language/code_summary/findings',
    ADD COLUMN improvement_plan_json LONGTEXT NULL COMMENT '分层改进建议 Agent 产出：overall_summary/tiers';
