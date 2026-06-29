"""Milvus collection management and vector insertion for RAG chunks.

Collection schema matches the design doc:
  chunk_id (INT64 PK), course_space_id, doc_id, parent_id,
  chapter_path (VARCHAR 512), page_range (VARCHAR 64),
  vector (FLOAT_VECTOR dim=1024).

Index: IVF_FLAT, metric=COSINE, nlist=128.
"""
from __future__ import annotations

import logging
from typing import Dict, List

from pymilvus import (
    Collection,
    CollectionSchema,
    DataType,
    FieldSchema,
    connections,
    utility,
)

import config

logger = logging.getLogger(__name__)

_DIM = config.DASHSCOPE_EMBEDDING_DIM  # 1024


def _connect() -> None:
    """Ensure a Milvus connection is established."""
    if not connections.has_connection("default"):
        connections.connect(
            alias="default",
            host=config.MILVUS_HOST,
            port=config.MILVUS_PORT,
        )


def ensure_collection() -> Collection:
    """Create the course_chunks collection and IVF_FLAT index if not exists.

    Returns the Collection handle.
    """
    _connect()
    name = config.MILVUS_COLLECTION

    if utility.has_collection(name):
        col = Collection(name)
        col.load()
        return col

    fields = [
        FieldSchema("chunk_id", DataType.INT64, is_primary=True, auto_id=False),
        FieldSchema("course_space_id", DataType.INT64),
        FieldSchema("doc_id", DataType.INT64),
        FieldSchema("parent_id", DataType.INT64),
        FieldSchema("chapter_path", DataType.VARCHAR, max_length=512),
        FieldSchema("page_range", DataType.VARCHAR, max_length=64),
        FieldSchema("vector", DataType.FLOAT_VECTOR, dim=_DIM),
    ]
    schema = CollectionSchema(fields, description="RAG course chunks")
    col = Collection(name, schema)

    # Build IVF_FLAT index on the vector field
    index_params = {
        "index_type": "IVF_FLAT",
        "metric_type": "COSINE",
        "params": {"nlist": 128},
    }
    col.create_index("vector", index_params)
    col.load()

    logger.info("Created Milvus collection '%s' with IVF_FLAT index.", name)
    return col


def insert_chunks(records: List[Dict]) -> List[int]:
    """Insert chunk vectors into Milvus.

    Parameters
    ----------
    records : list[dict]
        Each dict must contain keys matching the collection fields:
        chunk_id, course_space_id, doc_id, parent_id,
        chapter_path, page_range, vector.

    Returns
    -------
    list[int]
        The chunk_id values that were inserted (echo back for confirmation).
    """
    if not records:
        return []

    col = ensure_collection()

    # Prepare column-oriented data for Milvus insert
    data = [
        [r["chunk_id"] for r in records],       # chunk_id
        [r["course_space_id"] for r in records], # course_space_id
        [r["doc_id"] for r in records],          # doc_id
        [r["parent_id"] for r in records],       # parent_id
        [r["chapter_path"] for r in records],    # chapter_path
        [r["page_range"] for r in records],      # page_range
        [r["vector"] for r in records],          # vector
    ]

    col.insert(data)
    col.flush()

    logger.info("Inserted %d vectors into Milvus collection '%s'.",
                len(records), config.MILVUS_COLLECTION)
    return [r["chunk_id"] for r in records]


CHAPTER_SUMMARIES_COLLECTION = "chapter_summaries"


def ensure_chapter_summaries_collection() -> Collection:
    """Create the chapter_summaries collection if not exists."""
    _connect()
    name = CHAPTER_SUMMARIES_COLLECTION

    if utility.has_collection(name):
        col = Collection(name)
        col.load()
        return col

    fields = [
        FieldSchema("summary_id", DataType.INT64, is_primary=True, auto_id=False),
        FieldSchema("course_space_id", DataType.INT64),
        FieldSchema("doc_id", DataType.INT64),
        FieldSchema("chapter_path", DataType.VARCHAR, max_length=512),
        FieldSchema("level", DataType.INT32),
        FieldSchema("vector", DataType.FLOAT_VECTOR, dim=_DIM),
    ]
    schema = CollectionSchema(fields, description="Chapter summaries for RAG")
    col = Collection(name, schema)
    col.create_index("vector", {
        "index_type": "IVF_FLAT",
        "metric_type": "COSINE",
        "params": {"nlist": 64},
    })
    col.load()
    logger.info("Created Milvus collection '%s'.", name)
    return col


def insert_chapter_summaries(records: List[Dict]) -> None:
    """Insert chapter summary vectors into Milvus."""
    if not records:
        return
    col = ensure_chapter_summaries_collection()
    data = [
        [r["summary_id"] for r in records],
        [r["course_space_id"] for r in records],
        [r["doc_id"] for r in records],
        [r["chapter_path"] for r in records],
        [r["level"] for r in records],
        [r["vector"] for r in records],
    ]
    col.insert(data)
    col.flush()
    logger.info("Inserted %d chapter summaries into Milvus.", len(records))
