from models.pipeline_models import EvidenceBlock, EvidencePack
from pipeline.scorer import _normalize_result


def test_normalize_result_builds_evidence_anchored_annotation_when_model_omits_it():
    pack = EvidencePack(
        dimension_id=1,
        blocks=[EvidenceBlock(
            evidence_id="ev-1",
            kind="text",
            page=3,
            content="for (int i = 0; i <= n; i++) {\n    sum += scores[i];\n}",
        )],
    )
    parsed = {
        "dimension_id": 1,
        "score": 4,
        "comment": "循环条件 i <= n 会造成数组越界，应改为 i < n。",
        "evidence_ids": ["ev-1"],
        "status": "SCORED",
        "annotations": [],
    }

    result = _normalize_result(parsed, pack, {"id": 1, "max_score": 10})

    assert result.annotations
    annotation = result.annotations[0]
    assert annotation["evidence_id"] == "ev-1"
    assert annotation["type"] in {"CROSS", "WAVE"}
    assert annotation["anchor_text"] in pack.blocks[0].content
