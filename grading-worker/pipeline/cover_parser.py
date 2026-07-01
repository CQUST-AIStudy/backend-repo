"""Recognize the cover-page 课程目标 (course-objective) table of a student report.

The first page of these lab reports carries a table whose rows are 目标1 / 目标2 / ...
with a 分值 (max score) column and 优/良/中/及格/不及格 level ranges. We render that
page to an image and let the VLM (parse_rubric task) read it, then normalize the result
into a stable structure the backend uses to map rubric dimensions onto the correct
objective rows and to fill the cover table with authoritative max scores.

Output structure (persisted as grading_submission.cover_objectives_json):
{
  "source": "vlm",
  "confidence": 0.95,
  "objectives": [
    {"index": 1, "label": "目标1", "maxScore": 20.0, "levelRanges": {"优": "18-20", ...}},
    {"index": 2, "label": "目标2", "maxScore": 40.0, "levelRanges": {...}}
  ]
}
Returns None when recognition is unavailable or the page does not look like a cover table.
"""
import re

import fitz  # PyMuPDF

from pipeline.vlm_client import call_vlm

COVER_RENDER_DPI = 200
_OBJECTIVE_LABEL_RE = re.compile(r"目标\s*([0-9一二三四五六七八九十]+)")
_CN_NUM = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5,
           "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}


def render_first_page_png(pdf_bytes: bytes) -> bytes | None:
    """Render page 0 of a PDF to PNG bytes. Returns None on failure / non-PDF."""
    if not pdf_bytes or not pdf_bytes[:5].startswith(b"%PDF"):
        return None
    doc = None
    try:
        doc = fitz.open(stream=pdf_bytes, filetype="pdf")
        if len(doc) == 0:
            return None
        page = doc[0]
        pix = page.get_pixmap(dpi=COVER_RENDER_DPI, alpha=False)
        return pix.tobytes("png")
    except Exception:
        return None
    finally:
        if doc is not None:
            try:
                doc.close()
            except Exception:
                pass


def _to_float(value):
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    text = str(value).strip()
    if not text:
        return None
    m = re.search(r"\d+(?:\.\d+)?", text)
    return float(m.group(0)) if m else None


def _normalize_label(raw_name: str, fallback_index: int) -> tuple[str, int]:
    """Return (canonical_label, objective_index) like ("目标1", 1)."""
    name = (raw_name or "").strip()
    m = _OBJECTIVE_LABEL_RE.search(name)
    if m:
        token = m.group(1)
        if token.isdigit():
            idx = int(token)
        else:
            idx = _CN_NUM.get(token, fallback_index)
        return f"目标{idx}", idx
    return f"目标{fallback_index}", fallback_index


def _looks_like_course_objective(dimensions: list) -> bool:
    """Only treat the recognition as a cover course-objective table when at least one
    row is clearly labelled 目标N. Generic rubric sheets without 目标 rows are ignored
    so we do not fabricate a mapping."""
    for dim in dimensions:
        if isinstance(dim, dict) and _OBJECTIVE_LABEL_RE.search(str(dim.get("name") or "")):
            return True
    return False


def recognize_cover_objectives(pdf_bytes: bytes) -> dict | None:
    """Render the cover page, ask the VLM to parse the 课程目标 table, and normalize."""
    image_bytes = render_first_page_png(pdf_bytes)
    if not image_bytes:
        return None

    result = call_vlm(image_bytes, task="parse_rubric")
    payload = result.description_json or {}
    if not payload or "error" in payload:
        return None

    raw_dimensions = payload.get("dimensions")
    if not isinstance(raw_dimensions, list) or not raw_dimensions:
        return None
    if not _looks_like_course_objective(raw_dimensions):
        return None

    objectives = []
    seen_indexes = set()
    fallback_index = 0
    for dim in raw_dimensions:
        if not isinstance(dim, dict):
            continue
        fallback_index += 1
        label, idx = _normalize_label(dim.get("name", ""), fallback_index)
        # Avoid duplicate objective indexes (keep the first occurrence).
        if idx in seen_indexes:
            continue
        seen_indexes.add(idx)
        objectives.append({
            "index": idx,
            "label": label,
            "maxScore": _to_float(dim.get("max_score")),
            "levelRanges": dim.get("level_ranges") if isinstance(dim.get("level_ranges"), dict) else None,
        })

    if not objectives:
        return None

    objectives.sort(key=lambda o: o["index"])

    try:
        confidence = float(payload.get("confidence") or 0.0)
    except Exception:
        confidence = 0.0

    return {
        "source": "vlm",
        "confidence": confidence,
        "objectives": objectives,
    }
