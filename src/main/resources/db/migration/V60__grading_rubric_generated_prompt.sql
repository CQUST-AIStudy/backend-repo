-- 缓存"提示词生成 Agent"根据评分标准生成的批改提示词，避免每次批改重复调用大模型。
-- generated_prompt_source_hash 记录生成时所依据评分标准内容的哈希，评分标准变更后哈希不同即触发重新生成。
ALTER TABLE grading_rubric
    ADD COLUMN generated_prompt TEXT NULL COMMENT '由提示词生成 Agent 根据评分标准生成的批改提示词',
    ADD COLUMN generated_prompt_at TIMESTAMP(3) NULL COMMENT '批改提示词生成时间',
    ADD COLUMN generated_prompt_source_hash VARCHAR(64) NULL COMMENT '生成提示词时所依据评分标准内容的哈希，用于失效判断';
