"""FastAPI service for grading worker health and rerank API."""
from __future__ import annotations

import os
import re
import threading
from typing import List

from fastapi import FastAPI, File, UploadFile
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel, Field

try:
    from sentence_transformers import CrossEncoder
except Exception:  # pragma: no cover - optional dependency
    CrossEncoder = None  # type: ignore[assignment]

try:
    from pipeline.rubric_image_parser import parse_rubric_image, ParsedRubric
except Exception:  # pragma: no cover - module may be missing in some deployments
    parse_rubric_image = None  # type: ignore[assignment]
    ParsedRubric = None  # type: ignore[assignment,misc]


app = FastAPI(title="Grading Worker", version="1.2.0")

_MODEL_NAME = os.getenv("RERANK_MODEL_NAME", "BAAI/bge-reranker-base")
_MODEL_ENABLED = os.getenv("RERANK_MODEL_ENABLED", "true").strip().lower() not in {
    "0", "false", "no"
}
_MODEL_MAX_LENGTH = int(os.getenv("RERANK_MODEL_MAX_LENGTH", "512"))

_model = None
_model_error = None
_model_lock = threading.Lock()


class RerankDoc(BaseModel):
    id: str
    text: str


class RerankRequest(BaseModel):
    query: str
    documents: List[RerankDoc]
    top_n: int = Field(default=5, ge=1, le=100)


class RerankResult(BaseModel):
    id: str
    score: float


class RerankResponse(BaseModel):
    provider: str
    results: List[RerankResult]


def _get_model():
    global _model, _model_error
    if _model is not None or _model_error is not None:
        return _model

    with _model_lock:
        if _model is not None or _model_error is not None:
            return _model
        if not _MODEL_ENABLED:
            _model_error = "disabled by env RERANK_MODEL_ENABLED"
            return None
        if CrossEncoder is None:
            _model_error = "sentence-transformers not installed"
            return None
        try:
            _model = CrossEncoder(_MODEL_NAME, max_length=_MODEL_MAX_LENGTH)
        except Exception as exc:  # pragma: no cover - model env dependent
            _model_error = str(exc)
            _model = None

    return _model


def _tokenize(text: str) -> List[str]:
    if not text:
        return []
    parts = re.findall(r"[a-z0-9\u4e00-\u9fff]+", text.lower())
    tokens = []
    for part in parts:
        if len(part) >= 2:
            tokens.append(part)
        if re.fullmatch(r"[\u4e00-\u9fff]{2,}", part):
            for idx in range(len(part) - 1):
                tokens.append(part[idx: idx + 2])
    return list(dict.fromkeys(tokens))


def _lexical_score(query: str, text: str) -> float:
    tokens = _tokenize(query)
    if not tokens:
        return 0.0
    body = (text or "").lower()
    hit_count = sum(1 for token in tokens if token in body)
    overlap = hit_count / max(1, len(tokens))
    phrase_bonus = 0.2 if query.strip() and query.strip().lower() in body else 0.0
    return overlap + phrase_bonus


@app.get("/health")
def health():
    model_state = "ready" if _model is not None else ("disabled" if _model_error else "lazy")
    return {
        "status": "ok",
        "service": "grading-worker",
        "rerank_model": model_state,
        "model_name": _MODEL_NAME,
    }


@app.post("/rerank", response_model=RerankResponse)
def rerank(req: RerankRequest):
    docs = req.documents or []
    if not docs:
        return RerankResponse(provider="none", results=[])

    top_n = min(req.top_n, len(docs))
    pairs = [(req.query, (doc.text or "")[:2000]) for doc in docs]
    results = []
    provider = "lexical_fallback"

    model = _get_model()
    if model is not None:
        try:
            scores = model.predict(pairs)
            for idx, score in enumerate(scores):
                results.append(RerankResult(id=docs[idx].id, score=float(score)))
            provider = "cross_encoder"
        except Exception:
            results = []

    if not results:
        for doc in docs:
            results.append(RerankResult(id=doc.id, score=_lexical_score(req.query, doc.text)))

    results.sort(key=lambda item: item.score, reverse=True)
    return RerankResponse(provider=provider, results=results[:top_n])


@app.post("/parse-rubric-image", response_model=ParsedRubric if ParsedRubric is not None else dict)
async def parse_rubric_image_endpoint(image: UploadFile = File(...)):
    """Parse a grading rubric/scoring sheet image into structured dimensions."""
    if parse_rubric_image is None:
        return {"error": "rubric image parser is not available"}
    image_bytes = await image.read()
    result = await run_in_threadpool(parse_rubric_image, image_bytes)
    return result
