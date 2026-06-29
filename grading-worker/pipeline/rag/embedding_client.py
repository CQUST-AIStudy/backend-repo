"""DashScope text-embedding-v3 client with automatic batching.

Uses httpx to call the OpenAI-compatible endpoint.  Batch size is capped at 25
(DashScope limit).  Returns 1024-dim float vectors.
"""
from __future__ import annotations

import logging
from typing import List

import httpx

import config

logger = logging.getLogger(__name__)

_BATCH_SIZE = 25
_TIMEOUT = 60  # seconds per batch request


def embed_texts(texts: List[str]) -> List[List[float]]:
    """Embed a list of texts via DashScope, handling batching internally.

    Parameters
    ----------
    texts : list[str]
        Texts to embed.  Empty strings are allowed but will still consume a
        slot in the batch.

    Returns
    -------
    list[list[float]]
        One 1024-dim vector per input text, in the same order.

    Raises
    ------
    RuntimeError
        If the API returns an error or a non-200 status.
    """
    if not texts:
        return []

    all_vectors: List[List[float]] = []

    for start in range(0, len(texts), _BATCH_SIZE):
        batch = texts[start : start + _BATCH_SIZE]
        vectors = _call_api(batch)
        all_vectors.extend(vectors)

    return all_vectors


def _call_api(batch: List[str]) -> List[List[float]]:
    """Send a single batch request to DashScope embedding endpoint."""
    if not config.DASHSCOPE_API_KEY:
        raise RuntimeError("DASHSCOPE_API_KEY is not configured")

    payload = {
        "model": config.DASHSCOPE_EMBEDDING_MODEL,
        "input": batch,
        "dimensions": config.DASHSCOPE_EMBEDDING_DIM,
    }
    headers = {
        "Authorization": f"Bearer {config.DASHSCOPE_API_KEY}",
        "Content-Type": "application/json",
    }

    with httpx.Client(timeout=_TIMEOUT) as client:
        resp = client.post(
            config.DASHSCOPE_EMBEDDING_ENDPOINT,
            json=payload,
            headers=headers,
        )

    if resp.status_code != 200:
        raise RuntimeError(
            f"DashScope embedding API error {resp.status_code}: {resp.text[:500]}"
        )

    data = resp.json()
    # Response follows OpenAI format: data[].embedding sorted by index
    items = sorted(data["data"], key=lambda x: x["index"])
    return [item["embedding"] for item in items]
