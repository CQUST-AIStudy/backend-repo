"""代码逻辑分析 Agent。

从已抽取的证据中聚合"代码类"内容(代码截图 / 终端日志 的 VLM/OCR 文本,以及
正文里看起来像代码的文本),交给大模型做逻辑/边界层面的判断——由 AI 阅读真实内容
定位问题,而不是靠硬编码规则匹配。输出结构化 findings(位置/类型/严重度/思路)。

设计原则:
- 无代码证据 → 返回 None(跳过)。
- 无 key / provider=mock / AI 调用或解析失败 → 返回 None(不编造 findings)。
- 任何异常都不抛出,不阻断主批改流程。
- 只定位问题、给改进思路,不产出完整代码。
"""
from __future__ import annotations

from config import GRADING_AI_PROVIDER, GRADING_API_KEY
from pipeline.scorer import _post_chat_json, _extract_json_object

# 归一化后包含这些子串的 image_kind 视为代码类证据
CODE_IMAGE_KINDS = ("code_screenshot", "terminal_log")
VALID_SEVERITIES = {"HIGH", "MEDIUM", "LOW"}
MAX_FINDINGS = 12
MAX_CODE_BLOCKS = 10
MAX_CHARS_PER_BLOCK = 1600
MAX_TEXT_CHARS = 600

# 正文文本"像代码"的启发式特征(仅用于挑选喂给 AI 的候选,判断仍由 AI 做)
_CODE_HINTS = (
    "#include", "int main", "printf(", "scanf(", "return 0;",
    "def ", "import ", "class ", "public static", "System.out",
    "for(", "for (", "while(", "while (", "std::", "malloc(", "->",
)


def _normalize_kind(block) -> str:
    meta = getattr(block, "metadata", None) or {}
    raw = str(meta.get("image_kind", "")).lower()
    return raw


def _looks_like_code(text: str) -> bool:
    if not text:
        return False
    if "```" in text:
        return True
    hits = sum(1 for h in _CODE_HINTS if h in text)
    if hits >= 2:
        return True
    # 分号 / 大括号密度较高也倾向于代码
    braces = text.count("{") + text.count("}")
    semis = text.count(";")
    return (braces >= 2 and semis >= 2)


def _collect_code_evidence(evidence_blocks: list) -> list[dict]:
    picked = []
    for eb in evidence_blocks:
        kind = getattr(eb, "kind", "")
        content = (getattr(eb, "content", "") or "").strip()
        if not content:
            continue
        norm_kind = _normalize_kind(eb)
        is_code_image = (
            kind in ("vlm", "ocr")
            and any(k in norm_kind for k in CODE_IMAGE_KINDS)
        )
        is_code_text = kind == "text" and _looks_like_code(content)
        if is_code_image or is_code_text:
            picked.append({
                "evidence_id": getattr(eb, "evidence_id", ""),
                "page": getattr(eb, "page", None),
                "content": content[:MAX_CHARS_PER_BLOCK]
                if is_code_image else content[:MAX_TEXT_CHARS],
            })
        if len(picked) >= MAX_CODE_BLOCKS:
            break
    return picked


def _ai_disabled() -> bool:
    return not GRADING_API_KEY or GRADING_AI_PROVIDER == "mock"


def _build_prompt(code_items: list[dict], rubric_subject: str) -> str:
    subject_line = f"实验主题: {rubric_subject}\n\n" if rubric_subject else ""
    blocks = []
    for item in code_items:
        page = f"(第{item['page']}页)" if item["page"] is not None else ""
        blocks.append(f"[{item['evidence_id']}]{page}\n{item['content']}")
    joined = "\n\n---\n\n".join(blocks)
    return (
        "你是一名严谨的编程助教。下面是从学生实验报告中提取的代码/运行日志片段"
        "(可能来自截图 OCR,存在少量识别噪声)。请你**基于代码的实际内容**判断其中的"
        "逻辑与边界问题——例如逻辑错误、数组越界、空指针、死循环、复杂度过高、未处理异常等。"
        "只定位问题并给出改进思路,不要输出完整代码。语言用中文。"
        "若某片段并非代码或无明显问题,则不必强行编造。\n\n"
        f"{subject_line}代码片段:\n{joined}\n\n"
        "只返回如下 JSON(evidence_id 必须来自上面出现过的编号,anchor_text 摘录相关代码原文):\n"
        '{"language":"c|python|java|unknown","code_summary":"整体正确性概述,80字以内",'
        '"findings":[{"evidence_id":"...","anchor_text":"...","issue_type":"逻辑错误|边界越界|空指针|死循环|复杂度|未处理异常|其他",'
        '"severity":"HIGH|MEDIUM|LOW","explanation":"问题说明","suggestion":"改进思路"}]}'
    )


def _normalize(parsed: dict, valid_ids: set[str]) -> dict | None:
    if not isinstance(parsed, dict):
        return None
    language = str(parsed.get("language", "unknown")).strip().lower() or "unknown"
    code_summary = str(parsed.get("code_summary", "")).strip()[:200]
    raw_findings = parsed.get("findings", []) or []
    default_id = next(iter(valid_ids), None)
    findings = []
    for f in raw_findings:
        if not isinstance(f, dict):
            continue
        eid = f.get("evidence_id")
        if eid not in valid_ids:
            eid = default_id
        severity = str(f.get("severity", "MEDIUM")).strip().upper()
        if severity not in VALID_SEVERITIES:
            severity = "MEDIUM"
        explanation = str(f.get("explanation", "")).strip()
        if not explanation:
            continue
        findings.append({
            "evidence_id": eid,
            "anchor_text": str(f.get("anchor_text", "")).strip()[:300],
            "issue_type": str(f.get("issue_type", "其他")).strip()[:32] or "其他",
            "severity": severity,
            "explanation": explanation[:400],
            "suggestion": str(f.get("suggestion", "")).strip()[:400],
        })
        if len(findings) >= MAX_FINDINGS:
            break
    if not code_summary and not findings:
        return None
    return {"language": language, "code_summary": code_summary, "findings": findings}


def analyze_code(
    evidence_blocks: list,
    dimensions: list[dict] | None = None,
    rubric_subject: str = "",
) -> dict | None:
    """对代码类证据做 AI 逻辑分析。

    返回 {"language","code_summary","findings":[...]} 或 None(无代码证据 / AI 不可用 /
    解析失败)。永不抛出异常。
    """
    try:
        code_items = _collect_code_evidence(evidence_blocks or [])
        if not code_items:
            return None
        if _ai_disabled():
            return None

        valid_ids = {item["evidence_id"] for item in code_items if item["evidence_id"]}
        prompt = _build_prompt(code_items, rubric_subject or "")
        try:
            parsed, _trace = _post_chat_json(prompt, max_tokens=1800)
        except Exception:
            return None
        if isinstance(parsed, str):
            try:
                parsed = _extract_json_object(parsed)
            except Exception:
                return None
        return _normalize(parsed, valid_ids)
    except Exception:
        return None
