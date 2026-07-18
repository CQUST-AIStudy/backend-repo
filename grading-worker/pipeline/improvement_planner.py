"""分层改进建议 Agent。

打分完成后运行:先用确定性规则按得分率把各维度分档(CRITICAL/IMPROVE/SOLID),
再调用大模型为每个维度补写"问题"与"下一步动作",并生成整体学情小结。
分档是确定性的(避免 AI 乱分);AI 只负责生成文本。无 key / provider=mock / AI 失败时
退回纯规则文案,功能依旧可见。任何异常都不应阻断主批改流程。
"""
from __future__ import annotations

from config import GRADING_AI_PROVIDER, GRADING_API_KEY
from pipeline.scorer import _post_chat_json, _extract_json_object

# 得分率分档阈值
CRITICAL_MAX_RATIO = 0.60
IMPROVE_MAX_RATIO = 0.80

TIER_ORDER = ["CRITICAL", "IMPROVE", "SOLID"]
TIER_LABELS = {
    "CRITICAL": "急需补强",
    "IMPROVE": "建议提升",
    "SOLID": "巩固拓展",
}
# 无 AI 时按档位给出的通用动作文案
TIER_DEFAULT_ACTIONS = {
    "CRITICAL": "回到该维度的基础知识点重新梳理,配合针对性练习后再提交一次。",
    "IMPROVE": "对照评语中的不足点做专项改进,补齐薄弱环节。",
    "SOLID": "已达标,可尝试更高阶的拓展题或优化实现质量。",
}
MAX_COMMENT_CHARS = 160


def _ratio(sd: dict) -> float | None:
    score = sd.get("score")
    max_score = sd.get("max_score") or 0
    if score is None or max_score <= 0:
        return None
    return float(score) / float(max_score)


def _classify_tier(sd: dict) -> str:
    """按得分率确定性分档。无分数 / 证据不足归入 CRITICAL。"""
    if sd.get("status") == "NEED_MORE_EVIDENCE":
        return "CRITICAL"
    ratio = _ratio(sd)
    if ratio is None:
        return "CRITICAL"
    if ratio < CRITICAL_MAX_RATIO:
        return "CRITICAL"
    if ratio < IMPROVE_MAX_RATIO:
        return "IMPROVE"
    return "SOLID"


def _short(text: str | None, limit: int = MAX_COMMENT_CHARS) -> str:
    text = (text or "").strip()
    if len(text) <= limit:
        return text
    return text[:limit].rstrip() + "…"


def _ai_disabled() -> bool:
    return not GRADING_API_KEY or GRADING_AI_PROVIDER == "mock"


def _build_prompt(rows: list[dict], code_findings: list[dict] | None, total: float) -> str:
    dim_lines = []
    for row in rows:
        ratio = row["ratio"]
        ratio_str = f"{ratio * 100:.0f}%" if ratio is not None else "无有效得分"
        dim_lines.append(
            f"- 维度[{row['name']}] 得分 {row['score']}/{row['max_score']} "
            f"(得分率 {ratio_str}, 档位 {row['tier']}/{TIER_LABELS[row['tier']]}) "
            f"评语: {_short(row['comment'])}"
        )
    findings_text = ""
    if code_findings:
        picked = []
        for f in code_findings[:8]:
            picked.append(
                f"- [{f.get('issue_type', '问题')}/{f.get('severity', '')}] "
                f"{_short(f.get('explanation'), 120)}"
            )
        if picked:
            findings_text = "\n\n学生代码已发现的问题:\n" + "\n".join(picked)

    return (
        "你是一名负责实验报告批改的助教。下面是某位学生各评分维度的得分与评语,"
        "维度已按得分率预先分好档位(CRITICAL 急需补强 / IMPROVE 建议提升 / SOLID 巩固拓展),"
        "请不要更改档位。请为每个维度写出:该维度当前的核心问题(problem),以及学生下一步"
        "应采取的具体、可执行的动作(action)。同时给出 2-3 句整体学情小结(overall_summary)。"
        "全部使用中文,语气面向学生本人,动作要具体不空泛。\n\n"
        f"总分: {total}\n各维度:\n" + "\n".join(dim_lines) + findings_text +
        "\n\n只返回如下 JSON:\n"
        '{"overall_summary":"...","dimensions":[{"dimension":"维度名","problem":"...","action":"..."}]}'
    )


def _empty_plan(total: float) -> dict:
    return {
        "overall_summary": f"本次总分 {total}。暂无可分层的维度数据。",
        "tiers": [],
    }


def _assemble(rows: list[dict], overall_summary: str, ai_by_dim: dict[str, dict]) -> dict:
    tiers = []
    for tier in TIER_ORDER:
        items = []
        for row in rows:
            if row["tier"] != tier:
                continue
            ai = ai_by_dim.get(row["name"], {})
            problem = _short(ai.get("problem")) or _short(row["comment"]) or "该维度存在待改进之处。"
            action = _short(ai.get("action")) or TIER_DEFAULT_ACTIONS[tier]
            items.append({
                "dimension": row["name"],
                "problem": problem,
                "action": action,
            })
        if items:
            tiers.append({"tier": tier, "label": TIER_LABELS[tier], "items": items})
    return {"overall_summary": overall_summary, "tiers": tiers}


def _fallback_summary(rows: list[dict], total: float) -> str:
    counts = {t: sum(1 for r in rows if r["tier"] == t) for t in TIER_ORDER}
    parts = []
    if counts["CRITICAL"]:
        parts.append(f"{counts['CRITICAL']} 个维度急需补强")
    if counts["IMPROVE"]:
        parts.append(f"{counts['IMPROVE']} 个维度有提升空间")
    if counts["SOLID"]:
        parts.append(f"{counts['SOLID']} 个维度表现扎实")
    detail = ",".join(parts) if parts else "各维度情况见下"
    return f"本次总分 {total}。{detail}。请优先处理急需补强的部分。"


def build_plan(
    score_dicts: list[dict],
    dimensions: list[dict],
    code_findings: list[dict] | None,
    total: float,
) -> dict | None:
    """生成分层改进建议。

    score_dicts 与 dimensions 顺序一一对应(同 tasks.py 中的 zip)。返回 dict,
    永不抛出:任何异常都退回确定性兜底文案。无维度时返回空计划。
    """
    try:
        rows = []
        for dim, sd in zip(dimensions, score_dicts):
            rows.append({
                "name": dim.get("name", "未命名维度"),
                "score": sd.get("score"),
                "max_score": sd.get("max_score"),
                "comment": sd.get("comment", ""),
                "status": sd.get("status", ""),
                "ratio": _ratio(sd),
                "tier": _classify_tier(sd),
            })

        if not rows:
            return _empty_plan(total)

        if _ai_disabled():
            return _assemble(rows, _fallback_summary(rows, total), {})

        try:
            prompt = _build_prompt(rows, code_findings, total)
            parsed, _trace = _post_chat_json(prompt, max_tokens=1500)
            if isinstance(parsed, str):
                parsed = _extract_json_object(parsed)
            overall = _short(parsed.get("overall_summary"), 300) or _fallback_summary(rows, total)
            ai_by_dim = {}
            for item in parsed.get("dimensions", []) or []:
                name = (item or {}).get("dimension")
                if name:
                    ai_by_dim[name] = item
            return _assemble(rows, overall, ai_by_dim)
        except Exception:
            # AI 调用/解析失败:退回规则兜底,功能仍可见。
            return _assemble(rows, _fallback_summary(rows, total), {})
    except Exception:
        return None
