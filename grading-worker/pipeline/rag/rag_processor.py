"""Full RAG processing pipeline: chunk → embed → write Milvus + MySQL.

Orchestrates the end-to-end flow for a single course_space_document:
1. Read document text from MySQL (or MinIO if extracted_text_key is set)
2. Two-level chunking
3. Save parent chunks to MySQL doc_chunk (type='parent')
4. Save child chunks to MySQL doc_chunk (type='child', with parent_id)
5. Embed all child chunks via DashScope
6. Write child vectors to Milvus
7. Update milvus_id on child doc_chunk records
8. Update course_space_document status to READY (or FAILED on error)
9. Update chunk_count
"""
from __future__ import annotations

import io
import logging

from minio import Minio

import config
from models.db_models import (
    CourseSpaceDocument,
    DocChunk,
    Document,
    get_session,
)
from pipeline.rag.chunker import two_level_chunk
from pipeline.rag.embedding_client import embed_texts
from pipeline.rag.milvus_writer import insert_chunks

logger = logging.getLogger(__name__)

_MIN_VISIBLE_CHARS = 120


def _get_minio() -> Minio:
    return Minio(
        config.MINIO_ENDPOINT,
        access_key=config.MINIO_ACCESS_KEY,
        secret_key=config.MINIO_SECRET_KEY,
        secure=config.MINIO_SECURE,
    )


def _read_text_from_minio(key: str) -> str:
    """Download extracted text stored in MinIO."""
    client = _get_minio()
    resp = client.get_object(config.MINIO_BUCKET, key)
    try:
        return resp.read().decode("utf-8")
    finally:
        resp.close()
        resp.release_conn()


def process_document(course_space_doc_id: int) -> None:
    """Full RAG processing pipeline for a single document.

    Parameters
    ----------
    course_space_doc_id : int
        Primary key of the ``course_space_document`` row.
    """
    session = get_session()
    try:
        # --- Load records ---------------------------------------------------
        csd = session.query(CourseSpaceDocument).get(course_space_doc_id)
        if csd is None:
            logger.error("CourseSpaceDocument %d not found.", course_space_doc_id)
            return

        csd.status = "PROCESSING"
        session.commit()

        doc = session.query(Document).get(csd.document_id)
        if doc is None:
            _fail(session, csd, "Document row not found")
            return

        # --- 1. Get document text -------------------------------------------
        text = doc.extracted_text
        if not text and doc.extracted_text_key:
            text = _read_text_from_minio(doc.extracted_text_key)
        if not text:
            _fail(session, csd, "No extracted text available")
            return
        text = _normalize_text(text)
        if not _is_usable_text(text):
            _fail(
                session,
                csd,
                "Extracted text quality is too low. Please upload a text-based PDF/DOCX/TXT, or run OCR before importing.",
            )
            return

        _clear_existing_chunks(session, csd.course_space_id, csd.document_id)

        # --- 2. Two-level chunking ------------------------------------------
        parents = two_level_chunk(text, doc_id=csd.document_id)
        if not parents:
            _fail(session, csd, "Chunking produced zero chunks")
            return

        # --- 3 & 4. Save chunks to MySQL ------------------------------------
        child_chunks_db = []
        total_chunk_count = 0

        for parent in parents:
            parent_row = DocChunk(
                document_id=csd.document_id,
                course_space_id=csd.course_space_id,
                chunk_type="parent",
                chunk_index=parent.chunk_index,
                content=parent.content,
                chapter_path=parent.chapter_path,
                page_range=parent.page_range,
                token_count=parent.token_count,
            )
            session.add(parent_row)
            session.flush()  # get parent_row.id

            for child in parent.children:
                child_row = DocChunk(
                    document_id=csd.document_id,
                    course_space_id=csd.course_space_id,
                    chunk_type="child",
                    parent_id=parent_row.id,
                    chunk_index=child.chunk_index,
                    content=child.content,
                    chapter_path=child.chapter_path,
                    page_range=child.page_range,
                    token_count=child.token_count,
                )
                session.add(child_row)
                session.flush()
                child_chunks_db.append(child_row)
                total_chunk_count += 1

        session.commit()

        # --- 5. Embed all child chunks -------------------------------------
        child_texts = [c.content for c in child_chunks_db]
        vectors = []
        try:
            vectors = embed_texts(child_texts)
        except Exception as embed_err:
            logger.warning("Embedding failed (non-fatal, skipping Milvus): %s", embed_err)

        # --- 6. Write child vectors to Milvus (skip if no vectors or Milvus unavailable) ---
        if vectors:
            try:
                milvus_records = []
                for child_row, vec in zip(child_chunks_db, vectors):
                    milvus_records.append({
                        "chunk_id": child_row.id,
                        "course_space_id": csd.course_space_id,
                        "doc_id": csd.document_id,
                        "parent_id": child_row.parent_id or 0,
                        "chapter_path": child_row.chapter_path or "",
                        "page_range": child_row.page_range or "",
                        "vector": vec,
                    })
                insert_chunks(milvus_records)

                # --- 7. Update milvus_id on child doc_chunk records ---------
                for child_row in child_chunks_db:
                    child_row.milvus_id = child_row.id
                session.commit()
            except Exception as milvus_err:
                logger.warning("Milvus insert failed (non-fatal): %s", milvus_err)
        else:
            logger.info("Skipping Milvus insert (no vectors available).")

        # --- 8 & 9. Mark READY + update chunk_count ------------------------
        csd.status = "READY"
        csd.chunk_count = total_chunk_count
        session.commit()

        # --- 10. Generate chapter summaries (best-effort) -------------------
        try:
            from pipeline.rag.chapter_summarizer import generate_chapter_summaries
            generate_chapter_summaries(csd.document_id, csd.course_space_id, text)
        except Exception as exc:
            logger.warning("Chapter summary generation failed (non-fatal): %s", exc)

        logger.info(
            "RAG processing complete for csd=%d: %d parent, %d child chunks.",
            course_space_doc_id, len(parents), total_chunk_count,
        )

    except Exception as exc:
        session.rollback()
        try:
            csd = session.query(CourseSpaceDocument).get(course_space_doc_id)
            if csd:
                _fail(session, csd, str(exc)[:500])
        except Exception:
            pass
        raise
    finally:
        session.close()


def _fail(session, csd: CourseSpaceDocument, error_msg: str) -> None:
    """Mark the course_space_document as FAILED."""
    csd.status = "FAILED"
    csd.error_message = error_msg
    session.commit()
    logger.error("RAG processing FAILED for csd=%d: %s", csd.id, error_msg)


def _clear_existing_chunks(session, course_space_id: int, document_id: int) -> None:
    session.query(DocChunk).filter(
        DocChunk.course_space_id == course_space_id,
        DocChunk.document_id == document_id,
        DocChunk.chunk_type == "child",
    ).delete(synchronize_session=False)
    session.query(DocChunk).filter(
        DocChunk.course_space_id == course_space_id,
        DocChunk.document_id == document_id,
        DocChunk.chunk_type == "parent",
    ).delete(synchronize_session=False)
    session.commit()


def _normalize_text(text: str) -> str:
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    normalized = normalized.strip()
    return normalized


def _is_usable_text(text: str) -> bool:
    visible = "".join(text.split())
    if len(visible) < _MIN_VISIBLE_CHARS:
        return False

    lines = [line.strip() for line in text.split("\n") if line.strip()]
    if not lines:
        return False

    informative_lines = [line for line in lines if _is_informative_line(line)]
    text_like_chars = sum(1 for ch in text if ch.isalnum() or "\u4e00" <= ch <= "\u9fff")
    density = text_like_chars / max(len(visible), 1)
    informative_ratio = len(informative_lines) / len(lines)
    return density >= 0.45 and len(informative_lines) >= 2 and informative_ratio >= 0.35


def _is_informative_line(line: str) -> bool:
    if len(line) < 8:
        return False
    lowered = line.lower()
    if (lowered.startswith("[") and lowered.endswith("]")) or ("=" in line and len(line) < 40):
        return False
    if line in {"封面页", "书名页", "版权页", "前言", "目录", "目次", "索引"}:
        return False
    text_like_chars = sum(1 for ch in line if ch.isalnum() or "\u4e00" <= ch <= "\u9fff")
    return text_like_chars >= 8
