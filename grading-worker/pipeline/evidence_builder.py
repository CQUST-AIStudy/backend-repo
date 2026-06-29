"""Evidence building using LangChain Document schema and BM25 retrieval."""
import uuid
from typing import Dict, List
from langchain.schema import Document
from langchain_community.retrievers import BM25Retriever
from models.pipeline_models import EvidenceBlock, EvidencePack

MIN_EVIDENCE = 3
MAX_EVIDENCE = 8
USABLE_KINDS = {"text", "ocr", "vlm", "image"}


class CodeLineSplitter:
    """Custom splitter for code content — splits on line boundaries, preserves indentation."""

    def __init__(self, max_lines: int = 20):
        self.max_lines = max_lines

    def split(self, text: str, metadata: dict) -> List[Document]:
        lines = text.split("\n")
        chunks = []
        for i in range(0, len(lines), self.max_lines):
            chunk_lines = lines[i:i + self.max_lines]
            chunk_text = "\n".join(chunk_lines)
            if chunk_text.strip():
                doc_meta = {**metadata, "chunk_start_line": i}
                chunks.append(Document(page_content=chunk_text, metadata=doc_meta))
        return chunks if chunks else [Document(page_content=text, metadata=metadata)]


def build_evidence_packs(
    evidence_blocks: List[EvidenceBlock],
    dimensions: List[dict],  # [{id, name, description, max_score, weight}]
) -> Dict[int, EvidencePack]:
    """Build evidence packs per rubric dimension using BM25 retrieval."""
    if not evidence_blocks or not dimensions:
        return {}

    # Convert evidence blocks to LangChain Documents
    splitter = CodeLineSplitter(max_lines=20)
    documents: List[Document] = []
    usable_blocks = [
        eb for eb in evidence_blocks
        if eb.kind in USABLE_KINDS and (eb.content or "").strip()
    ]
    fallback_blocks = [eb for eb in evidence_blocks if eb.kind == "vlm_failed"]
    candidate_blocks = usable_blocks if usable_blocks else evidence_blocks

    for eb in candidate_blocks:
        meta = {
            "evidence_id": eb.evidence_id,
            "kind": eb.kind,
            "page": eb.page,
            "confidence": eb.confidence,
        }
        if eb.kind in ("ocr", "text") and eb.content:
            docs = splitter.split(eb.content, meta)
            documents.extend(docs)
        else:
            documents.append(Document(page_content=eb.content or "", metadata=meta))

    if not documents:
        return {}

    result: Dict[int, EvidencePack] = {}

    for dim in dimensions:
        dim_id = dim["id"]
        query = f"{dim['name']} {dim.get('description', '')}"

        # Use BM25 to rank evidence
        retriever = BM25Retriever.from_documents(documents, k=MAX_EVIDENCE)
        relevant_docs = retriever.invoke(query)

        # Deduplicate by evidence_id and limit to bounds
        seen_ids = set()
        selected: List[EvidenceBlock] = []

        for doc in relevant_docs:
            eid = doc.metadata.get("evidence_id")
            if eid and eid not in seen_ids:
                seen_ids.add(eid)
                # Find original evidence block
                for eb in evidence_blocks:
                    if eb.evidence_id == eid:
                        selected.append(eb)
                        break
            if len(selected) >= MAX_EVIDENCE:
                break

        # If we have fewer than MIN_EVIDENCE, pad with remaining blocks
        if len(selected) < MIN_EVIDENCE:
            for eb in usable_blocks:
                if eb.evidence_id not in seen_ids:
                    selected.append(eb)
                    seen_ids.add(eb.evidence_id)
                if len(selected) >= MIN_EVIDENCE:
                    break

        # Last resort only: include failure placeholders if no usable evidence can satisfy MIN_EVIDENCE.
        if len(selected) < MIN_EVIDENCE:
            for eb in fallback_blocks:
                if eb.evidence_id not in seen_ids:
                    selected.append(eb)
                    seen_ids.add(eb.evidence_id)
                if len(selected) >= MIN_EVIDENCE:
                    break

        result[dim_id] = EvidencePack(dimension_id=dim_id, blocks=selected[:MAX_EVIDENCE])

    return result
