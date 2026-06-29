from models.pipeline_models import EvidenceBlock, EvidencePack
from pipeline.scorer import _normalize_result


def test_normalize_result_applies_sixty_percent_floor_when_evidence_is_substantial():
    pack = EvidencePack(
        dimension_id=2,
        blocks=[
            EvidenceBlock(
                evidence_id="ev-1",
                kind="text",
                page=1,
                content=(
                    "掌握物联网终端系统架构设计、传感器数据采集编程及数据上云代码编写方法，"
                    "能够对终端系统进行代码开发与调试，掌握云平台接入及数据可视化实现方法。"
                ),
            ),
            EvidenceBlock(
                evidence_id="ev-2",
                kind="text",
                page=2,
                content=(
                    "报告展示了设备采集、无线传输、云端分析和控制反馈流程，并说明了系统开发环境、"
                    "传感器模块选择、数据上传协议和基础测试结果。"
                ),
            ),
        ],
    )
    parsed = {
        "dimension_id": 2,
        "score": 12,
        "comment": "报告已经呈现了系统流程，但实现细节仍需补充。",
        "evidence_ids": ["ev-1", "ev-2"],
        "status": "SCORED",
        "annotations": [],
    }

    result = _normalize_result(parsed, pack, {"id": 2, "max_score": 40})

    assert result.score == 24
    assert "完成度保护校准" in result.comment


def test_normalize_result_applies_dynamic_floor_from_score_range_min():
    """When score_range_min=80 is provided, the floor should be 80% of max_score."""
    pack = EvidencePack(
        dimension_id=2,
        blocks=[
            EvidenceBlock(
                evidence_id="ev-1",
                kind="text",
                page=1,
                content=(
                    "掌握物联网终端系统架构设计、传感器数据采集编程及数据上云代码编写方法，"
                    "能够对终端系统进行代码开发与调试，掌握云平台接入及数据可视化实现方法。"
                ),
            ),
            EvidenceBlock(
                evidence_id="ev-2",
                kind="text",
                page=2,
                content=(
                    "报告展示了设备采集、无线传输、云端分析和控制反馈流程，并说明了系统开发环境、"
                    "传感器模块选择、数据上传协议和基础测试结果。"
                ),
            ),
        ],
    )
    parsed = {
        "dimension_id": 2,
        "score": 12,
        "comment": "报告已经呈现了系统流程，但实现细节仍需补充。",
        "evidence_ids": ["ev-1", "ev-2"],
        "status": "SCORED",
        "annotations": [],
    }

    result = _normalize_result(parsed, pack, {"id": 2, "max_score": 40}, score_range_min=80)

    assert result.score == 32  # 80% of 40
    assert "80%" in result.comment
    assert "完成度保护校准" in result.comment


def test_normalize_result_respects_score_range_min_above_hard_minimum():
    """When score_range_min=75 (above MIN_FLOOR_RATIO=60%), floor should be 75%."""
    pack = EvidencePack(
        dimension_id=1,
        blocks=[
            EvidenceBlock(
                evidence_id="ev-1",
                kind="text",
                page=1,
                content="实验目的明确，步骤完整，结果分析合理。",
            ),
            EvidenceBlock(
                evidence_id="ev-2",
                kind="text",
                page=2,
                content="报告包含完整的实验数据和分析结论。",
            ),
        ],
    )
    parsed = {
        "dimension_id": 1,
        "score": 10,
        "comment": "内容基本合格。",
        "evidence_ids": ["ev-1"],
        "status": "SCORED",
        "annotations": [],
    }

    result = _normalize_result(parsed, pack, {"id": 1, "max_score": 20}, score_range_min=75)

    assert result.score == 15  # 75% of 20
    assert "75%" in result.comment
