import pytest

from models.pipeline_models import EvidenceBlock
from pipeline import code_analyzer
from pipeline.code_analyzer import analyze_code, _collect_code_evidence, _normalize


def _code_text_block():
    return EvidenceBlock(
        evidence_id="ev-1",
        kind="text",
        page=2,
        content="int main(){\n  int a[3];\n  for(int i=0;i<=3;i++){ a[i]=i; }\n  return 0;\n}",
    )


def _code_image_block():
    return EvidenceBlock(
        evidence_id="ev-2",
        kind="vlm",
        page=3,
        content="def f(x):\n    return 1/x",
        metadata={"image_kind": "code_screenshot"},
    )


def _prose_block():
    return EvidenceBlock(
        evidence_id="ev-3",
        kind="text",
        page=1,
        content="本实验主要研究排序算法的时间复杂度与稳定性,并给出实验结论。",
    )


def test_collect_code_evidence_detects_code_and_ignores_prose():
    items = _collect_code_evidence([_prose_block(), _code_text_block(), _code_image_block()])
    ids = {i["evidence_id"] for i in items}
    assert ids == {"ev-1", "ev-2"}


def test_analyze_code_returns_none_when_no_code_evidence():
    assert analyze_code([_prose_block()]) is None


def test_analyze_code_returns_none_when_ai_disabled(monkeypatch):
    monkeypatch.setattr(code_analyzer, "GRADING_API_KEY", "")
    assert analyze_code([_code_text_block()]) is None


def test_normalize_remaps_unknown_evidence_id_and_clamps_severity():
    parsed = {
        "language": "C",
        "code_summary": "存在数组越界。",
        "findings": [
            {"evidence_id": "bogus", "issue_type": "边界越界", "severity": "critical",
             "explanation": "i<=3 越界访问 a[3]", "suggestion": "改为 i<3"},
            {"evidence_id": "ev-1", "issue_type": "", "severity": "HIGH",
             "explanation": "", "suggestion": "无"},  # explanation 为空应被丢弃
        ],
    }
    result = _normalize(parsed, valid_ids={"ev-1"})
    assert result["language"] == "c"
    assert len(result["findings"]) == 1
    f = result["findings"][0]
    assert f["evidence_id"] == "ev-1"  # bogus 被重映射到已知 id
    assert f["severity"] == "MEDIUM"   # 非法 severity 归一为 MEDIUM
    assert f["issue_type"] == "边界越界"


def test_normalize_returns_none_when_empty():
    assert _normalize({"code_summary": "", "findings": []}, valid_ids=set()) is None


def test_analyze_code_happy_path(monkeypatch):
    monkeypatch.setattr(code_analyzer, "GRADING_API_KEY", "sk-test")
    monkeypatch.setattr(code_analyzer, "GRADING_AI_PROVIDER", "deepseek")

    def fake_post(prompt, max_tokens):
        return (
            {
                "language": "c",
                "code_summary": "循环边界越界。",
                "findings": [
                    {"evidence_id": "ev-1", "anchor_text": "for(int i=0;i<=3;i++)",
                     "issue_type": "边界越界", "severity": "HIGH",
                     "explanation": "i<=3 访问 a[3] 越界", "suggestion": "改为 i<3"},
                ],
            },
            {},
        )

    monkeypatch.setattr(code_analyzer, "_post_chat_json", fake_post)
    result = analyze_code([_code_text_block()], rubric_subject="C 语言程序设计")
    assert result["code_summary"] == "循环边界越界。"
    assert result["findings"][0]["severity"] == "HIGH"


def test_analyze_code_never_raises_on_ai_failure(monkeypatch):
    monkeypatch.setattr(code_analyzer, "GRADING_API_KEY", "sk-test")
    monkeypatch.setattr(code_analyzer, "GRADING_AI_PROVIDER", "deepseek")

    def boom(prompt, max_tokens):
        raise RuntimeError("timeout")

    monkeypatch.setattr(code_analyzer, "_post_chat_json", boom)
    assert analyze_code([_code_text_block()]) is None
