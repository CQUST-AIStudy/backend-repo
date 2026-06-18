-- 批次总评结果字段
-- 生产库曾经可能执行到一半后失败，因此这里用 information_schema 做幂等保护。
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'grading_task' AND COLUMN_NAME = 'batch_review_json'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE grading_task ADD COLUMN batch_review_json JSON NULL COMMENT ''批次总评 Agent 生成的 JSON 结果''',
    'DO 0'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'grading_task' AND COLUMN_NAME = 'batch_review_status'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE grading_task ADD COLUMN batch_review_status VARCHAR(24) NOT NULL DEFAULT ''PENDING'' COMMENT ''批次总评状态：PENDING/GENERATING/COMPLETED/FAILED''',
    'DO 0'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'grading_task' AND COLUMN_NAME = 'batch_review_prompt'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE grading_task ADD COLUMN batch_review_prompt TEXT NULL COMMENT ''该任务自定义的批次总评 prompt（覆盖默认配置）''',
    'DO 0'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'grading_task' AND COLUMN_NAME = 'batch_review_model'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE grading_task ADD COLUMN batch_review_model VARCHAR(128) NULL COMMENT ''该任务自定义的批次总评模型（覆盖默认配置）''',
    'DO 0'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Agent 配置模板表（可复用、可缓存到 Redis）
CREATE TABLE IF NOT EXISTS agent_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL UNIQUE COMMENT '配置编码，如 batch_review_default',
    name VARCHAR(128) NOT NULL COMMENT '配置名称',
    prompt_template TEXT NOT NULL COMMENT 'Prompt 模板，可用占位符如 {{experimentName}}',
    model VARCHAR(128) NOT NULL COMMENT '模型名称',
    temperature DECIMAL(3, 2) NOT NULL DEFAULT 0.3 COMMENT '采样温度',
    max_tokens INT NOT NULL DEFAULT 1600 COMMENT '最大输出 token 数',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 配置模板';

-- 插入默认批次总评配置
INSERT INTO agent_config (code, name, prompt_template, model, temperature, max_tokens, enabled)
VALUES ('batch_review_default', '默认批次总评 Agent',
'你是一位高校实验课主讲教师。以下是一个批改任务中所有学生的评分结果汇总。请阅读后输出一份面向教师的批次总评，帮助老师把握全班情况。

输出严格 JSON：
{
  "summary": "总体情况，80-120字",
  "commonIssues": [
    {"issue": "共性问题1", "affectedRatio": "约30%", "suggestion": "教学改进建议"}
  ],
  "strengths": ["全班表现较好的方面1"],
  "teachingAdvice": "下一次课或下一次实验可以重点讲什么、布置什么补救练习",
  "scoreDistribution": {"high": 0, "medium": 0, "low": 0}
}

输入数据：
- 实验名称：{{experimentName}}
- 评分维度：{{dimensions}}
- 各学生得分与评语：{{submissionsSummary}}',
'qwen-plus-latest', 0.3, 1600, 1)
ON DUPLICATE KEY UPDATE prompt_template = VALUES(prompt_template), model = VALUES(model);
