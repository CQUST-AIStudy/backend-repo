-- 为 evidence_block 增加位置定位字段，用于真实批改系统中 AI 标记的精确定位
ALTER TABLE evidence_block
    ADD COLUMN location_json JSON NULL COMMENT '证据在原始文档中的位置信息（页、段落、行、bbox 等）';
