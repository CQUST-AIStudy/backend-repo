"""Two-level document chunker: parent (1000-1500 tokens) → child (200-350 tokens).

Uses LangChain RecursiveCharacterTextSplitter with tiktoken cl100k_base for
accurate token counting.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import List

import tiktoken
from langchain.text_splitter import RecursiveCharacterTextSplitter

# ---------------------------------------------------------------------------
# Token helpers
# ---------------------------------------------------------------------------
_ENC = tiktoken.get_encoding("cl100k_base")


def _token_len(text: str) -> int:
    """Return the number of tokens using cl100k_base encoding."""
    return len(_ENC.encode(text))


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------

@dataclass
class ChildChunk:
    content: str
    chunk_index: int
    token_count: int
    chapter_path: str = ""
    page_range: str = ""


@dataclass
class ParentChunk:
    content: str
    chunk_index: int
    token_count: int
    chapter_path: str = ""
    page_range: str = ""
    children: List[ChildChunk] = field(default_factory=list)


# ---------------------------------------------------------------------------
# Splitters
# ---------------------------------------------------------------------------

# Parent-level: split by section markers first, then fall back to paragraphs
_PARENT_SPLITTER = RecursiveCharacterTextSplitter(
    separators=["\n## ", "\n### ", "\n\n\n", "\n\n", "\n", " "],
    chunk_size=1500,
    chunk_overlap=100,
    length_function=_token_len,
    keep_separator=True,
)

# Child-level: split within a parent by sentence boundaries
_CHILD_SPLITTER = RecursiveCharacterTextSplitter(
    separators=["\n\n", "\n", "。", ".", "！", "!", "？", "?", "；", ";", " "],
    chunk_size=350,
    chunk_overlap=30,
    length_function=_token_len,
    keep_separator=True,
)


# ---------------------------------------------------------------------------
# Chapter path detection
# ---------------------------------------------------------------------------

def _detect_chapter_path(text: str) -> str:
    """Try to extract a chapter/section heading from the beginning of text."""
    for line in text.strip().split("\n")[:3]:
        stripped = line.strip()
        if stripped.startswith("#"):
            return stripped.lstrip("# ").strip()
    return ""


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def two_level_chunk(text: str, doc_id: int = 0) -> List[ParentChunk]:
    """Split *text* into parent chunks, each containing child chunks.

    Parameters
    ----------
    text : str
        Full document text.
    doc_id : int
        Document ID for metadata (reserved for future use).

    Returns
    -------
    list[ParentChunk]
        Ordered list of parent chunks with nested children.
    """
    if not text or not text.strip():
        return []

    parent_texts = _PARENT_SPLITTER.split_text(text)
    parents: List[ParentChunk] = []

    for p_idx, p_text in enumerate(parent_texts):
        chapter = _detect_chapter_path(p_text)
        parent = ParentChunk(
            content=p_text,
            chunk_index=p_idx,
            token_count=_token_len(p_text),
            chapter_path=chapter,
        )

        child_texts = _CHILD_SPLITTER.split_text(p_text)
        for c_idx, c_text in enumerate(child_texts):
            child = ChildChunk(
                content=c_text,
                chunk_index=c_idx,
                token_count=_token_len(c_text),
                chapter_path=chapter,
            )
            parent.children.append(child)

        parents.append(parent)

    return parents
