"""Parse a grading-rubric image into structured dimensions using VLM."""
from __future__ import annotations

from decimal import Decimal
from typing import Any

from pydantic import BaseModel, Field, field_validator

from pipeline.vlm_client import call_vlm


class ParsedDimension(BaseModel):
    name: str
    description: str = ""
    max_score: float = Field(..., gt=0)
    weight: int = 0
    level_ranges: dict[str, str] = Field(default_factory=dict)
    teacher_score: float | None = None
    teacher_level: str | None = None

    @field_validator("name")
    @classmethod
    def name_not_empty(cls, v: str) -> str:
        v = (v or "").strip()
        if not v:
            raise ValueError("dimension name must not be empty")
        return v


class ParsedRubric(BaseModel):
    rubric_name: str = ""
    dimensions: list[ParsedDimension] = Field(default_factory=list)
    total_score: float | None = None
    confidence: float = 0.0
    raw: dict[str, Any] = Field(default_factory=dict)


def _to_float(value: Any, default: float | None = None) -> float | None:
    if value is None:
        return default
    if isinstance(value, (int, float)):
        return float(value)
    try:
        return float(str(value).strip())
    except (TypeError, ValueError):
        return default


def _to_int(value: Any, default: int | None = None) -> int | None:
    if value is None:
        return default
    if isinstance(value, int):
        return value
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return default


def _normalize_weights(dimensions: list[ParsedDimension]) -> None:
    """Normalize dimension weights so they sum to 100."""
    if not dimensions:
        return
    explicit_weights = [d.weight for d in dimensions if d.weight > 0]
    if len(explicit_weights) == len(dimensions):
        total = sum(explicit_weights)
        if total > 0 and total != 100:
            for d in dimensions:
                d.weight = int(round(d.weight * 100 / total))
    else:
        total_score = sum(d.max_score for d in dimensions)
        if total_score > 0:
            for d in dimensions:
                d.weight = int(round(d.max_score * 100 / total_score))
    # Fix rounding errors so total is exactly 100
    total = sum(d.weight for d in dimensions)
    if total != 100 and dimensions:
        diff = 100 - total
        dimensions[0].weight = max(1, dimensions[0].weight + diff)


def _build_dimension(raw_dim: dict[str, Any]) -> ParsedDimension | None:
    name = str(raw_dim.get("name") or "").strip()
    if not name:
        return None
    max_score = _to_float(raw_dim.get("max_score"), 0.0)
    if max_score is None or max_score <= 0:
        return None
    return ParsedDimension(
        name=name,
        description=str(raw_dim.get("description") or "").strip(),
        max_score=max_score,
        weight=_to_int(raw_dim.get("weight"), 0) or 0,
        level_ranges=raw_dim.get("level_ranges") or {},
        teacher_score=_to_float(raw_dim.get("teacher_score")),
        teacher_level=str(raw_dim.get("teacher_level") or "").strip() or None,
    )


def parse_rubric_image(image_bytes: bytes) -> ParsedRubric:
    """Parse a rubric image and return a structured rubric.

    Returns an empty ParsedRubric with no dimensions on failure rather than
    raising, so callers can decide how to degrade.
    """
    vlm_result = call_vlm(image_bytes, task="parse_rubric")
    raw = vlm_result.description_json or {}

    if isinstance(raw, str):
        return ParsedRubric(raw={"error": "vlm returned string", "value": raw})

    if raw.get("error"):
        return ParsedRubric(raw=raw)

    dimensions: list[ParsedDimension] = []
    raw_dimensions = raw.get("dimensions") or []
    if isinstance(raw_dimensions, dict):
        # VLM may return a dict keyed by name
        for name, value in raw_dimensions.items():
            if isinstance(value, dict):
                value = dict(value)
                value.setdefault("name", name)
                dim = _build_dimension(value)
                if dim:
                    dimensions.append(dim)
    elif isinstance(raw_dimensions, list):
        for raw_dim in raw_dimensions:
            if isinstance(raw_dim, dict):
                dim = _build_dimension(raw_dim)
                if dim:
                    dimensions.append(dim)

    _normalize_weights(dimensions)

    return ParsedRubric(
        rubric_name=str(raw.get("rubric_name") or "").strip(),
        dimensions=dimensions,
        total_score=_to_float(raw.get("total_score")),
        confidence=_to_float(raw.get("confidence"), 0.0) or 0.0,
        raw=raw,
    )


def parsed_rubric_to_dimension_dicts(parsed: ParsedRubric) -> list[dict[str, Any]]:
    """Convert a ParsedRubric into plain dicts matching the Java DimensionInput shape."""
    return [
        {
            "name": d.name,
            "description": d.description,
            "max_score": Decimal(str(d.max_score)),
            "weight": d.weight,
            "level_ranges": d.level_ranges,
            "teacher_score": d.teacher_score,
            "teacher_level": d.teacher_level,
        }
        for d in parsed.dimensions
    ]
