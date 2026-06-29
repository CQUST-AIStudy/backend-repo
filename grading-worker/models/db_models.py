"""SQLAlchemy models mirroring the JPA entities for direct MySQL access."""
from sqlalchemy import (
    Column, BigInteger, Integer, String, Text, DECIMAL, TIMESTAMP, JSON,
    ForeignKey, create_engine
)
from sqlalchemy.orm import declarative_base, sessionmaker, relationship
from sqlalchemy.sql import func
from config import DATABASE_URL

Base = declarative_base()
engine = create_engine(DATABASE_URL, pool_size=5, max_overflow=10, pool_recycle=3600)
SessionLocal = sessionmaker(bind=engine)


def get_session():
    return SessionLocal()


class GradingRubric(Base):
    __tablename__ = "grading_rubric"
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    teacher_id = Column(BigInteger, nullable=False)
    name = Column(String(256), nullable=False)
    subject = Column(String(128))
    description = Column(Text)
    dimensions = relationship("RubricDimension", back_populates="rubric", order_by="RubricDimension.sort_order")


class RubricDimension(Base):
    __tablename__ = "rubric_dimension"
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    rubric_id = Column(BigInteger, ForeignKey("grading_rubric.id"), nullable=False)
    name = Column(String(256), nullable=False)
    description = Column(Text)
    max_score = Column(DECIMAL(5, 1), nullable=False)
    weight = Column(Integer, nullable=False)
    sort_order = Column(Integer, nullable=False, default=0)
    rubric = relationship("GradingRubric", back_populates="dimensions")


class GradingTask(Base):
    __tablename__ = "grading_task"
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    teacher_id = Column(BigInteger, nullable=False)
    rubric_id = Column(BigInteger, ForeignKey("grading_rubric.id"), nullable=False)
    status = Column(String(16), nullable=False, default="PENDING")
    total_count = Column(Integer, nullable=False, default=0)
    completed_count = Column(Integer, nullable=False, default=0)
    failed_count = Column(Integer, nullable=False, default=0)


class GradingSubmission(Base):
    __tablename__ = "grading_submission"
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    task_id = Column(BigInteger, ForeignKey("grading_task.id"), nullable=False)
    student_name = Column(String(128))
    pdf_object_key = Column(Text, nullable=False)
    status = Column(String(24), nullable=False, default="PENDING")
    total_score = Column(DECIMAL(6, 2))
    error_message = Column(Text)
    updated_at = Column(TIMESTAMP(3), server_default=func.now(), onupdate=func.now())


class EvidenceBlock(Base):
    __tablename__ = "evidence_block"
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    submission_id = Column(BigInteger, ForeignKey("grading_submission.id"), nullable=False)
    evidence_id = Column(String(64), nullable=False, unique=True)
    kind = Column(String(16), nullable=False)
    page = Column(Integer)
    bbox_json = Column(JSON)
    content = Column(Text)
    confidence = Column(DECIMAL(4, 3))
    image_key = Column(Text)
    metadata_json = Column(JSON)
    location_json = Column(JSON)
    created_at = Column(TIMESTAMP(3), server_default=func.now())


class ScoreItem(Base):
    __tablename__ = "score_item"
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    submission_id = Column(BigInteger, ForeignKey("grading_submission.id"), nullable=False)
    dimension_id = Column(BigInteger, ForeignKey("rubric_dimension.id"), nullable=False)
    score = Column(DECIMAL(5, 1))
    max_score = Column(DECIMAL(5, 1), nullable=False)
    weight = Column(Integer, nullable=False)
    comment = Column(Text)
    evidence_ids_json = Column(JSON)
    annotations_json = Column(JSON)
    status = Column(String(24), nullable=False, default="PENDING")
    created_at = Column(TIMESTAMP(3), server_default=func.now())
    updated_at = Column(TIMESTAMP(3), server_default=func.now(), onupdate=func.now())


class GradingTrace(Base):
    __tablename__ = "grading_trace"
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    submission_id = Column(BigInteger, ForeignKey("grading_submission.id"), nullable=False)
    step = Column(String(64), nullable=False)
    status = Column(String(16), nullable=False)
    duration_ms = Column(BigInteger)
    model_used = Column(String(64))
    input_tokens = Column(Integer)
    output_tokens = Column(Integer)
    error_message = Column(Text)
    metadata_json = Column(JSON)
    created_at = Column(TIMESTAMP(3), server_default=func.now())


class ReportFile(Base):
    __tablename__ = "report_file"
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    task_id = Column(BigInteger, ForeignKey("grading_task.id"), nullable=False)
    submission_id = Column(BigInteger, ForeignKey("grading_submission.id"))
    file_type = Column(String(8), nullable=False)
    object_key = Column(Text, nullable=False)
    created_at = Column(TIMESTAMP(3), server_default=func.now())


# ---------------------------------------------------------------------------
# RAG models
# ---------------------------------------------------------------------------

class Document(Base):
    __tablename__ = "document"
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    extracted_text = Column(Text)
    extracted_text_key = Column(Text)


class CourseSpaceDocument(Base):
    __tablename__ = "course_space_document"
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    course_space_id = Column(BigInteger, nullable=False)
    document_id = Column(BigInteger, ForeignKey("document.id"), nullable=False)
    doc_type = Column(String(32), default="textbook")
    status = Column(String(16), nullable=False, default="PENDING")
    chunk_count = Column(Integer, nullable=False, default=0)
    error_message = Column(Text)
    created_at = Column(TIMESTAMP(3), server_default=func.now())


class DocChunk(Base):
    __tablename__ = "doc_chunk"
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    document_id = Column(BigInteger, ForeignKey("document.id"), nullable=False)
    course_space_id = Column(BigInteger, nullable=False)
    chunk_type = Column(String(8), nullable=False)
    parent_id = Column(BigInteger, ForeignKey("doc_chunk.id"))
    chunk_index = Column(Integer, nullable=False, default=0)
    content = Column(Text, nullable=False)
    chapter_path = Column(String(512))
    page_range = Column(String(64))
    token_count = Column(Integer, nullable=False, default=0)
    milvus_id = Column(BigInteger)
    created_at = Column(TIMESTAMP(3), server_default=func.now())


class ChapterSummary(Base):
    __tablename__ = "chapter_summary"
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    doc_id = Column(BigInteger, ForeignKey("document.id"), nullable=False)
    course_space_id = Column(BigInteger, nullable=False)
    chapter_path = Column(String(512), nullable=False)
    summary_text = Column(Text, nullable=False)
    level = Column(Integer, nullable=False, default=1)
    parent_chapter_id = Column(BigInteger, ForeignKey("chapter_summary.id"))
    milvus_id = Column(BigInteger)
    created_at = Column(TIMESTAMP(3), server_default=func.now())
