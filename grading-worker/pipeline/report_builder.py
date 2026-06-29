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


def generate_pdf(student_name: str, scores: list[dict], evidence_blocks: list[dict], total_score: float) -> bytes:
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
