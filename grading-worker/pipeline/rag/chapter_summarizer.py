"""Chapter summary generation: extract chapters → LLM summarize → write DB + Milvus."""
from __future__ import annotations

import logging
import re
import time
from typing import List, Optional

import requests

import config
from models.db_models import ChapterSummary, get_session
from pipeline.rag.embedding_client import embed_texts

logger = logging.getLogger(__name__)

# Chapter heading patterns for Chinese textbooks
CHAPTER_PATTERNS = [
    re.compile(r'^(第[一二三四五六七八九十百千\d]+[章节篇])\s*(.+)', re.MULTILINE),
    re.compile(r'^(\d+[\.\s]+)(.+)', re.MULTILINE),
    re.compile(r'^(Chapter\s+\d+)\s*[:\.]?\s*(.+)', re.MULTILINE | re.IGNORECASE),
]


def extract_chapters(text: str) -> List[dict]:
    """Extract chapter boundaries from document text."""
    chapters = []
    lines = text.split('\n')
    current_chapter = None
    current_content = []
    level = 1

    for line in lines:
        matched = False
        for pattern in CHAPTER_PATTERNS:
            m = pattern.match(line.strip())
            if m:
                if current_chapter:
                    chapters.append({
                        'path': current_chapter,
                        'content': '\n'.join(current_content),
                        'level': level,
                    })
                current_chapter = m.group(0).strip()
                current_content = []
                # Determine level: 章=1, 节=2, etc.
                if '章' in line or 'Chapter' in line.lower():
                    level = 1
                elif '节' in line:
                    level = 2
                else:
                    level = 1
                matched = True
                break
        if not matched and current_chapter:
            current_content.append(line)

    if current_chapter:
        chapters.append({
            'path': current_chapter,
            'content': '\n'.join(current_content),
            'level': level,
        })

    # If no chapters found, treat entire text as one chapter
    if not chapters:
        chapters.append({
            'path': '全文',
            'content': text[:5000],
            'level': 1,
        })

    return chapters


def summarize_chapter(chapter_content: str, chapter_path: str) -> str:
    """Call DeepSeek LLM to generate a chapter summary."""
    # Truncate very long chapters
    content = chapter_content[:3000] if len(chapter_content) > 3000 else chapter_content
    if not content.strip():
        return ""
    if not config.DEEPSEEK_API_KEY:
        logger.warning("Skip chapter summary because DEEPSEEK_API_KEY is not configured")
        return ""

    try:
        resp = requests.post(
            f"{config.DEEPSEEK_BASE_URL}/chat/completions",
            headers={
                "Authorization": f"Bearer {config.DEEPSEEK_API_KEY}",
                "Content-Type": "application/json",
            },
            json={
                "model": config.DEEPSEEK_MODEL,
                "messages": [
                    {"role": "system", "content": "你是一个教材摘要生成器。请用中文为以下章节内容生成一段简洁的摘要（100-200字），概括核心知识点。"},
                    {"role": "user", "content": f"章节：{chapter_path}\n\n内容：\n{content}"},
                ],
                "temperature": 0.3,
                "max_tokens": 300,
            },
            timeout=30,
        )
        resp.raise_for_status()
        data = resp.json()
        return data["choices"][0]["message"]["content"].strip()
    except Exception as e:
        logger.error("Failed to summarize chapter '%s': %s", chapter_path, e)
        return ""


def generate_chapter_summaries(doc_id: int, course_space_id: int, text: str) -> None:
    """Generate chapter summaries for a document and write to DB + Milvus."""
    session = get_session()
    try:
        chapters = extract_chapters(text)
        if not chapters:
            logger.info("No chapters found for doc_id=%d", doc_id)
            return

        summaries = []
        parent_map = {}  # level 1 chapter id for linking

        for ch in chapters:
            summary_text = summarize_chapter(ch['content'], ch['path'])
            if not summary_text:
                continue

            row = ChapterSummary(
                doc_id=doc_id,
                course_space_id=course_space_id,
                chapter_path=ch['path'],
                summary_text=summary_text,
                level=ch['level'],
                parent_chapter_id=parent_map.get(ch['level'] - 1),
            )
            session.add(row)
            session.flush()

            if ch['level'] == 1:
                parent_map[1] = row.id
            elif ch['level'] == 2:
                parent_map[2] = row.id

            summaries.append(row)
            time.sleep(0.5)  # Rate limiting

        session.commit()

        # Embed summaries and write to Milvus
        if summaries:
            texts = [s.summary_text for s in summaries]
            vectors = embed_texts(texts)

            from pipeline.rag.milvus_writer import insert_chapter_summaries
            milvus_records = []
            for row, vec in zip(summaries, vectors):
                milvus_records.append({
                    "summary_id": row.id,
                    "course_space_id": course_space_id,
                    "doc_id": doc_id,
                    "chapter_path": row.chapter_path or "",
                    "level": row.level,
                    "vector": vec,
                })
            insert_chapter_summaries(milvus_records)

            # Update milvus_id
            for row in summaries:
                row.milvus_id = row.id
            session.commit()

        logger.info("Generated %d chapter summaries for doc_id=%d", len(summaries), doc_id)

    except Exception as e:
        session.rollback()
        logger.error("Chapter summary generation failed for doc_id=%d: %s", doc_id, e)
    finally:
        session.close()
