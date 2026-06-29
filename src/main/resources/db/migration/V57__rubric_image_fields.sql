ALTER TABLE grading_rubric
    ADD COLUMN image_object_key VARCHAR(512) NULL COMMENT '评分表原图在对象存储中的 key',
    ADD COLUMN image_parsed_at TIMESTAMP(3) NULL COMMENT '评分表图片解析时间',
    ADD COLUMN image_parsed_json JSON NULL COMMENT 'VLM 解析评分表图片的原始 JSON 结果';
