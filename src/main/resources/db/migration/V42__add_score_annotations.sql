-- 为 score_item 增加 inline 批注字段，用于真实批改系统的分项评语定位
ALTER TABLE score_item
    ADD COLUMN annotations_json JSON NULL COMMENT 'AI 返回的 inline 批注列表（标记类型、位置、简短评语等）';
