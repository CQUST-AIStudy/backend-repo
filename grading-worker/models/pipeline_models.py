"""Pydantic models for the grading pipeline."""
from pydantic import BaseModel, Field
from typing import Optional
from enum import Enum


class ImageKind(str, Enum):
    CODE_SCREENSHOT = "code_screenshot"
    TERMINAL_LOG = "terminal_log"
    DIAGRAM = "diagram"
    PLOT = "plot"
    OTHER = "other"


class ImageInfo(BaseModel):
    page: int
    bbox: list[float] = Field(default_factory=list)  # [x0, y0, x1, y1]
    image_bytes: bytes
    kind: Optional[ImageKind] = None
    paragraph_index: Optional[int] = None  # DOCX only: index of the embedding paragraph


class ParsedPage(BaseModel):
    page_num: int
    text: str
    images: list[ImageInfo] = Field(default_factory=list)

    class Config:
        arbitrary_types_allowed = True


class ParsedDocument(BaseModel):
    pages: list[ParsedPage] = Field(default_factory=list)
    error: Optional[str] = None

    class Config:
        arbitrary_types_allowed = True


class OcrLine(BaseModel):
    text: str
    bbox: list[float] = Field(default_factory=list)
    confidence: float = 0.0


class OcrResult(BaseModel):
    text: str
    confidence: float = 0.0
    lines: list[OcrLine] = Field(default_factory=list)


class VlmResult(BaseModel):
    description_json: dict = Field(default_factory=dict)
    cached: bool = False


class EvidenceBlock(BaseModel):
    evidence_id: str
    kind: str  # text, ocr, vlm, image, vlm_failed
    page: int
    content: str
    confidence: Optional[float] = None
    image_key: Optional[str] = None
    bbox: Optional[list[float]] = None
    location: Optional[dict] = None
    metadata: dict = Field(default_factory=dict)


class EvidencePack(BaseModel):
    dimension_id: int
    blocks: list[EvidenceBlock] = Field(default_factory=list)


class ScoreResult(BaseModel):
    dimension_id: int
    score: Optional[float] = None
    max_score: float
    comment: str = ""
    evidence_ids: list[str] = Field(default_factory=list)
    annotations: list[dict] = Field(default_factory=list)
    status: str = "SCORED"  # SCORED or NEED_MORE_EVIDENCE


class TraceRecord(BaseModel):
    model_config = {"protected_namespaces": ()}

    submission_id: int
    step: str
    status: str  # SUCCESS or FAILED
    duration_ms: int = 0
    model_used: Optional[str] = None
    input_tokens: Optional[int] = None
    output_tokens: Optional[int] = None
    error_message: Optional[str] = None


class TaskMessage(BaseModel):
    taskId: int
    submissionId: int
    pdfObjectKey: str
    originalFilename: Optional[str] = None
    rubricId: int
    customPrompt: Optional[str] = None
    scoreRangeMin: Optional[float] = None
    scoreRangeMax: Optional[float] = None
