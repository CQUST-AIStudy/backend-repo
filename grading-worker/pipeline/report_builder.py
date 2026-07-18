"""Report builder: generates text-based PDF reports using PyMuPDF."""
import fitz  # PyMuPDF


A4_WIDTH = 595
A4_HEIGHT = 842
PAGE_MARGIN = 50
CONTENT_WIDTH = A4_WIDTH - PAGE_MARGIN * 2
BODY_FONT = "china-s"
TITLE_SIZE = 18
HEADING_SIZE = 14
BODY_SIZE = 11
LINE_GAP = 5


def _safe_text(value) -> str:
    if value is None:
        return ""
    return str(value).replace("\r\n", "\n").replace("\r", "\n").strip()


def _wrap_text(text: str, fontname: str, fontsize: float, max_width: float) -> list[str]:
    wrapped: list[str] = []
    paragraphs = _safe_text(text).split("\n") or [""]
    for paragraph in paragraphs:
        if not paragraph:
            wrapped.append("")
            continue
        current = ""
        for ch in paragraph:
            candidate = current + ch
            if not current or fitz.get_text_length(candidate, fontname=fontname, fontsize=fontsize) <= max_width:
                current = candidate
            else:
                wrapped.append(current)
                current = ch
        if current:
            wrapped.append(current)
    return wrapped or [""]


def _new_page(doc: fitz.Document) -> tuple[fitz.Page, float]:
    return doc.new_page(width=A4_WIDTH, height=A4_HEIGHT), PAGE_MARGIN


def _ensure_space(doc: fitz.Document, page: fitz.Page, y: float, needed_height: float) -> tuple[fitz.Page, float]:
    if y + needed_height <= A4_HEIGHT - PAGE_MARGIN:
        return page, y
    return _new_page(doc)


def _write_lines(
    doc: fitz.Document,
    page: fitz.Page,
    y: float,
    lines: list[str],
    *,
    fontname: str = BODY_FONT,
    fontsize: float = BODY_SIZE,
    indent: float = 0,
) -> tuple[fitz.Page, float]:
    line_height = fontsize + LINE_GAP
    for line in lines:
        page, y = _ensure_space(doc, page, y, line_height)
        page.insert_text((PAGE_MARGIN + indent, y), line, fontname=fontname, fontsize=fontsize)
        y += line_height
    return page, y


def _score_block_lines(score: dict) -> list[str]:
    status = _safe_text(score.get("status"))
    status_suffix = " [证据不足]" if status == "NEED_MORE_EVIDENCE" else ""
    evidence_refs = ", ".join(str(eid) for eid in (score.get("evidence_ids") or []))
    return [
        f"维度: {_safe_text(score.get('dimension_name'))}{status_suffix}",
        f"得分: {_safe_text(score.get('score') if score.get('score') is not None else 'N/A')} / {_safe_text(score.get('max_score'))}    权重: {_safe_text(score.get('weight'))}%",
        f"评语: {_safe_text(score.get('comment')) or '无'}",
        f"证据引用: {evidence_refs or '无'}",
    ]


def _evidence_block_lines(evidence: dict) -> list[str]:
    header = (
        f"[{_safe_text(evidence.get('evidence_id'))}] "
        f"类型: {_safe_text(evidence.get('kind'))} | 页码: {_safe_text(evidence.get('page')) or '-'}"
    )
    content = _safe_text(evidence.get("content"))[:600] or "无内容"
    return [header, content]


_SEVERITY_LABELS = {"HIGH": "高", "MEDIUM": "中", "LOW": "低"}


def _write_code_analysis(doc, page, y, code_analysis: dict):
    findings = code_analysis.get("findings") or []
    summary = _safe_text(code_analysis.get("code_summary"))
    language = _safe_text(code_analysis.get("language"))
    if not summary and not findings:
        return page, y

    page, y = _write_lines(doc, page, y, ["代码问题定位"], fontname=BODY_FONT, fontsize=HEADING_SIZE)
    intro = f"识别语言: {language or '未知'}"
    if summary:
        intro += f"    整体评估: {summary}"
    for line in _wrap_text(intro, BODY_FONT, BODY_SIZE, CONTENT_WIDTH):
        page, y = _write_lines(doc, page, y, [line])
    y += 4

    if not findings:
        page, y = _write_lines(doc, page, y, ["未发现明显逻辑/边界问题。", ""])
        return page, y

    for idx, f in enumerate(findings, 1):
        sev = _SEVERITY_LABELS.get(str(f.get("severity", "")).upper(), "中")
        head = f"{idx}. [{_safe_text(f.get('issue_type')) or '问题'}] 严重度: {sev}    位置: {_safe_text(f.get('evidence_id')) or '-'}"
        block_lines = _wrap_text(head, BODY_FONT, BODY_SIZE, CONTENT_WIDTH)
        anchor = _safe_text(f.get("anchor_text"))
        if anchor:
            block_lines += _wrap_text(f"代码: {anchor}", BODY_FONT, BODY_SIZE, CONTENT_WIDTH - 12)
        explanation = _safe_text(f.get("explanation"))
        if explanation:
            block_lines += _wrap_text(f"问题: {explanation}", BODY_FONT, BODY_SIZE, CONTENT_WIDTH - 12)
        suggestion = _safe_text(f.get("suggestion"))
        if suggestion:
            block_lines += _wrap_text(f"建议: {suggestion}", BODY_FONT, BODY_SIZE, CONTENT_WIDTH - 12)
        page, y = _write_lines(doc, page, y, block_lines[:1])
        page, y = _write_lines(doc, page, y, block_lines[1:], indent=12)
        y += 2
    y += 4
    return page, y


def _write_improvement_plan(doc, page, y, improvement_plan: dict):
    tiers = improvement_plan.get("tiers") or []
    overall = _safe_text(improvement_plan.get("overall_summary"))
    if not overall and not tiers:
        return page, y

    page, y = _write_lines(doc, page, y, ["分层改进建议"], fontname=BODY_FONT, fontsize=HEADING_SIZE)
    if overall:
        for line in _wrap_text(overall, BODY_FONT, BODY_SIZE, CONTENT_WIDTH):
            page, y = _write_lines(doc, page, y, [line])
        y += 4

    for tier in tiers:
        label = _safe_text(tier.get("label")) or _safe_text(tier.get("tier"))
        items = tier.get("items") or []
        if not items:
            continue
        page, y = _write_lines(doc, page, y, [f"【{label}】"])
        for item in items:
            dim = _safe_text(item.get("dimension"))
            problem = _safe_text(item.get("problem"))
            action = _safe_text(item.get("action"))
            head = f"- {dim}"
            block_lines = _wrap_text(head, BODY_FONT, BODY_SIZE, CONTENT_WIDTH)
            if problem:
                block_lines += _wrap_text(f"问题: {problem}", BODY_FONT, BODY_SIZE, CONTENT_WIDTH - 12)
            if action:
                block_lines += _wrap_text(f"建议: {action}", BODY_FONT, BODY_SIZE, CONTENT_WIDTH - 12)
            page, y = _write_lines(doc, page, y, block_lines[:1])
            page, y = _write_lines(doc, page, y, block_lines[1:], indent=12)
        y += 4
    return page, y


def generate_pdf(
    student_name: str,
    scores: list[dict],
    evidence_blocks: list[dict],
    total_score: float,
    code_analysis: dict | None = None,
    improvement_plan: dict | None = None,
) -> bytes:
    """Generate a simple PDF report and return bytes."""
    doc = fitz.open()
    page, y = _new_page(doc)

    page, y = _write_lines(doc, page, y, ["实验报告批改结果"], fontname=BODY_FONT, fontsize=TITLE_SIZE)
    y += 6
    page, y = _write_lines(doc, page, y, [f"学生: {_safe_text(student_name) or '未知'}", f"总分: {total_score}"])
    y += 8

    page, y = _write_lines(doc, page, y, ["评分详情"], fontname=BODY_FONT, fontsize=HEADING_SIZE)
    for score in scores:
        block_lines: list[str] = []
        for raw in _score_block_lines(score):
            block_lines.extend(_wrap_text(raw, BODY_FONT, BODY_SIZE, CONTENT_WIDTH))
        block_lines.append("")
        page, y = _write_lines(doc, page, y, block_lines)

    if code_analysis:
        page, y = _write_code_analysis(doc, page, y, code_analysis)

    if improvement_plan:
        page, y = _write_improvement_plan(doc, page, y, improvement_plan)

    page, y = _write_lines(doc, page, y, ["证据材料"], fontname=BODY_FONT, fontsize=HEADING_SIZE)
    for evidence in evidence_blocks[:20]:
        header, content = _evidence_block_lines(evidence)
        block_lines = _wrap_text(header, BODY_FONT, BODY_SIZE, CONTENT_WIDTH)
        block_lines.extend(_wrap_text(content, BODY_FONT, BODY_SIZE, CONTENT_WIDTH - 12))
        block_lines.append("")
        page, y = _write_lines(doc, page, y, block_lines[:1])
        page, y = _write_lines(doc, page, y, block_lines[1:], indent=12)

    page, y = _write_lines(doc, page, y, ["本报告由 AI 辅助批改系统自动生成。"])
    pdf_bytes = doc.tobytes()
    doc.close()
    return pdf_bytes
