import pytest

from models.pipeline_models import EvidenceBlock, EvidencePack
from pipeline import scorer


def test_score_dimension_does_not_mock_score_when_model_json_is_invalid(monkeypatch):
    pack = EvidencePack(
        dimension_id=1,
        blocks=[
            EvidenceBlock(
                evidence_id="ev-1",
                kind="text",
                page=3,
                content="实验结果中写到 swap 函数运行异常，程序在交换前输出后直接终止。",
            ),
            EvidenceBlock(
                evidence_id="ev-2",
                kind="text",
                page=4,
                content="代码片段显示 int *temp 未分配内存就被直接解引用赋值。",
            ),
        ],
    )

    def invalid_json(*args, **kwargs):
        raise ValueError("No JSON object found")

    monkeypatch.setattr(scorer, "_rate_limit_wait", lambda: None)
    monkeypatch.setattr(scorer, "_post_chat_json", invalid_json)

    result, _ = scorer.score_dimension(
        pack,
        {"id": 1, "name": "代码正确性", "max_score": 40, "weight": 40},
    )

    assert result.status == "NEED_MORE_EVIDENCE"
    assert result.score is None
    assert "模型返回内容无法解析" in result.comment
