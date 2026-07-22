-- 缓存"提示词生成 Agent"根据评分标准生成的批改提示词，避免每次批改重复调用大模型。
-- generated_prompt_source_hash 记录生成时所依据评分标准内容的哈希，评分标准变更后哈希不同即触发重新生成。
--
-- 说明：本迁移原为 V60，但与 V60__student_ai_experiment_report.sql 版本号冲突导致 Flyway 启动失败，
-- 现重编号为 V63。部分环境（历史上通过 ddl-auto 或手工）可能已存在这些列，故采用幂等方式添加，
-- 仅当列不存在时才执行 ALTER，避免 "Duplicate column" 导致迁移失败。
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'grading_rubric'
      AND COLUMN_NAME = 'generated_prompt'
);
SET @ddl := IF(@exists_col = 0,
    'ALTER TABLE grading_rubric ADD COLUMN generated_prompt TEXT NULL COMMENT ''由提示词生成 Agent 根据评分标准生成的批改提示词'', ADD COLUMN generated_prompt_at TIMESTAMP(3) NULL COMMENT ''批改提示词生成时间'', ADD COLUMN generated_prompt_source_hash VARCHAR(64) NULL COMMENT ''生成提示词时所依据评分标准内容的哈希，用于失效判断''',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
