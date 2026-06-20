-- 默认批次总评必须跟随 tap.ai.openai.model（OPENAI_MODEL）。
-- 仅清理历史版本写死的默认值，保留教师为具体任务设置的模型覆盖。
UPDATE agent_config
SET model = ''
WHERE code = 'batch_review_default'
  AND model = 'qwen-plus-latest';
