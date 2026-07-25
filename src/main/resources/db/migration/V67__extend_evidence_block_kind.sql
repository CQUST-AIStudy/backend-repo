-- worker 新增 code / code_analysis 两种证据块类型，扩展 kind 约束
ALTER TABLE evidence_block DROP CHECK chk_evidence_block_kind;
ALTER TABLE evidence_block ADD CONSTRAINT chk_evidence_block_kind
    CHECK (kind IN ('text','ocr','vlm','vlm_failed','code','code_analysis'));
