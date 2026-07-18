import pytest

from pipeline import improvement_planner
from pipeline.improvement_planner import build_plan, _classify_tier


def _sd(score, max_score=10, status="SCORED", comment="c"):
    return {"score": score, "max_score": max_score, "status": status, "comment": comment}


def test_classify_tier_boundaries():
    # < 0.6 -> CRITICAL
    assert _classify_tier(_sd(5, 10)) == "CRITICAL"
    assert _classify_tier(_sd(5.9, 10)) == "CRITICAL"
    # 0.6 <= ratio < 0.8 -> IMPROVE (0.6 边界属于 IMPROVE)
    assert _classify_tier(_sd(6, 10)) == "IMPROVE"
    assert _classify_tier(_sd(7.9, 10)) == "IMPROVE"
    # >= 0.8 -> SOLID (0.8 边界属于 SOLID)
    assert _classify_tier(_sd(8, 10)) == "SOLID"
    assert _classify_tier(_sd(10, 10)) == "SOLID"


def test_classify_tier_none_and_need_more_evidence():
    assert _classify_tier(_sd(None, 10)) == "CRITICAL"
    assert _classify_tier(_sd(9, 10, status="NEED_MORE_EVIDENCE")) == "CRITICAL"
    assert _classify_tier(_sd(9, 0)) == "CRITICAL"  # max_score<=0


def test_build_plan_fallback_when_ai_disabled(monkeypatch):
    monkeypatch.setattr(improvement_planner, "GRADING_API_KEY", "")
    dims = [{"name": "基础"}, {"name": "进阶"}, {"name": "拓展"}]
    scores = [_sd(4, 10, comment="基础薄弱"), _sd(7, 10), _sd(9, 10)]

    plan = build_plan(scores, dims, None, 62.0)

    assert plan is not None
    tiers = {t["tier"]: t for t in plan["tiers"]}
    assert set(tiers) == {"CRITICAL", "IMPROVE", "SOLID"}
    # 每档各一个维度
    assert tiers["CRITICAL"]["items"][0]["dimension"] == "基础"
    assert tiers["IMPROVE"]["items"][0]["dimension"] == "进阶"
    assert tiers["SOLID"]["items"][0]["dimension"] == "拓展"
    # fallback 下每个 item 均有非空 problem/action
    for t in plan["tiers"]:
        for item in t["items"]:
            assert item["problem"]
            assert item["action"]
    assert "62.0" in plan["overall_summary"]


def test_build_plan_uses_ai_text_but_keeps_deterministic_tier(monkeypatch):
    monkeypatch.setattr(improvement_planner, "GRADING_API_KEY", "sk-test")
    monkeypatch.setattr(improvement_planner, "GRADING_AI_PROVIDER", "deepseek")

    def fake_post(prompt, max_tokens):
        return (
            {
                "overall_summary": "整体总结。",
                "dimensions": [
                    {"dimension": "基础", "problem": "AI问题", "action": "AI动作"},
                ],
            },
            {},
        )

    monkeypatch.setattr(improvement_planner, "_post_chat_json", fake_post)

    dims = [{"name": "基础"}, {"name": "进阶"}]
    scores = [_sd(3, 10), _sd(9, 10)]
    plan = build_plan(scores, dims, None, 55.0)

    assert plan["overall_summary"] == "整体总结。"
    tiers = {t["tier"]: t for t in plan["tiers"]}
    # 基础得分率 0.3 仍在 CRITICAL(AI 不能改档),并采用 AI 文本
    assert tiers["CRITICAL"]["items"][0]["problem"] == "AI问题"
    assert tiers["CRITICAL"]["items"][0]["action"] == "AI动作"
    # 未被 AI 覆盖的维度使用兜底文案
    assert tiers["SOLID"]["items"][0]["dimension"] == "进阶"
    assert tiers["SOLID"]["items"][0]["action"]


def test_build_plan_never_raises_on_ai_failure(monkeypatch):
    monkeypatch.setattr(improvement_planner, "GRADING_API_KEY", "sk-test")
    monkeypatch.setattr(improvement_planner, "GRADING_AI_PROVIDER", "deepseek")

    def boom(prompt, max_tokens):
        raise RuntimeError("network down")

    monkeypatch.setattr(improvement_planner, "_post_chat_json", boom)
    plan = build_plan([_sd(4, 10)], [{"name": "基础"}], None, 40.0)
    assert plan is not None
    assert plan["tiers"][0]["items"][0]["dimension"] == "基础"


def test_build_plan_empty_dimensions():
    plan = build_plan([], [], None, 0.0)
    assert plan is not None
    assert plan["tiers"] == []
