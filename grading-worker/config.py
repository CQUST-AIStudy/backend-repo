"""Configuration for the grading worker."""

import os

from urllib.parse import quote_plus, urlparse



# Redis

REDIS_HOST = os.getenv("REDIS_HOST", "localhost")

REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))

REDIS_USERNAME = os.getenv("REDIS_USERNAME") or os.getenv("REDIS_USER", "")

REDIS_PASSWORD = os.getenv("REDIS_PASSWORD") or os.getenv("REDIS_PASS", "")

if REDIS_USERNAME and REDIS_PASSWORD:

    _redis_auth = f"{quote_plus(REDIS_USERNAME)}:{quote_plus(REDIS_PASSWORD)}@"

elif REDIS_PASSWORD:

    _redis_auth = f":{quote_plus(REDIS_PASSWORD)}@"

else:

    _redis_auth = ""

REDIS_URL = f"redis://{_redis_auth}{REDIS_HOST}:{REDIS_PORT}/0"



# MySQL

DB_HOST = os.getenv("DB_HOST", "localhost")

DB_PORT = int(os.getenv("DB_PORT", "3306"))

DB_NAME = os.getenv("DB_NAME", "ptadatabase")

DB_USER = os.getenv("DB_USER") or os.getenv("DB_USERNAME", "root")

DB_PASS = os.getenv("DB_PASS") or os.getenv("DB_PASSWORD", "123456")

DATABASE_URL = f"mysql+pymysql://{quote_plus(DB_USER)}:{quote_plus(DB_PASS)}@{DB_HOST}:{DB_PORT}/{DB_NAME}?charset=utf8mb4"



# MinIO / S3

def _normalize_minio_endpoint(raw_value: str) -> tuple[str, bool | None]:

    raw = (raw_value or "").strip()

    if not raw:

        return "localhost:9000", None

    if "://" not in raw:

        return raw.rstrip("/"), None



    parsed = urlparse(raw)

    endpoint = (parsed.netloc or parsed.path or "").rstrip("/")

    secure = parsed.scheme.lower() == "https"

    return endpoint or "localhost:9000", secure





MINIO_ENDPOINT, _minio_secure_from_endpoint = _normalize_minio_endpoint(

    os.getenv("MINIO_ENDPOINT", "localhost:9000")

)

MINIO_ACCESS_KEY = os.getenv("MINIO_ACCESS_KEY", "minioadmin")

MINIO_SECRET_KEY = os.getenv("MINIO_SECRET_KEY", "minioadmin")

MINIO_BUCKET = os.getenv("MINIO_BUCKET", "tap-files")

_minio_secure_env = os.getenv("MINIO_SECURE")

MINIO_SECURE = (

    _minio_secure_env.lower() == "true"

    if _minio_secure_env is not None

    else bool(_minio_secure_from_endpoint)

)



# DeepSeek / OpenAI-compatible API

DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY") or os.getenv("OPENAI_API_KEY", "")

DEEPSEEK_BASE_URL = os.getenv("DEEPSEEK_BASE_URL") or os.getenv("OPENAI_BASE_URL", "https://api.deepseek.com/v1")

DEEPSEEK_MODEL = os.getenv("DEEPSEEK_MODEL") or os.getenv("OPENAI_MODEL", "deepseek-chat")

DEEPSEEK_RATE_LIMIT = int(os.getenv("DEEPSEEK_RATE_LIMIT", "30"))  # requests per minute



# DashScope Embedding

DASHSCOPE_API_KEY = os.getenv("DASHSCOPE_API_KEY", "")

DASHSCOPE_EMBEDDING_MODEL = "text-embedding-v3"

DASHSCOPE_EMBEDDING_DIM = 1024

DASHSCOPE_EMBEDDING_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings"



# Qwen / DashScope compatible chat

DASHSCOPE_COMPAT_BASE_URL = os.getenv("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")

QWEN_TEXT_MODEL = os.getenv("QWEN_TEXT_MODEL", "qwen-plus-latest")

QWEN_VLM_MODEL = os.getenv("QWEN_VLM_MODEL", "qwen-vl-max-latest")

QWEN_RATE_LIMIT = int(os.getenv("QWEN_RATE_LIMIT", "20"))



GRADING_AI_PROVIDER = os.getenv(

    "GRADING_AI_PROVIDER",

    "qwen" if DASHSCOPE_API_KEY else ("deepseek" if DEEPSEEK_API_KEY else "mock"),

).strip().lower()

if GRADING_AI_PROVIDER == "qwen":

    GRADING_API_KEY = DASHSCOPE_API_KEY

    GRADING_BASE_URL = DASHSCOPE_COMPAT_BASE_URL

    GRADING_MODEL = os.getenv("GRADING_MODEL", QWEN_TEXT_MODEL)

    GRADING_RATE_LIMIT = QWEN_RATE_LIMIT

else:

    GRADING_API_KEY = DEEPSEEK_API_KEY

    GRADING_BASE_URL = DEEPSEEK_BASE_URL

    GRADING_MODEL = os.getenv("GRADING_MODEL", DEEPSEEK_MODEL)

    GRADING_RATE_LIMIT = DEEPSEEK_RATE_LIMIT



# VLM API

VLM_API_URL = os.getenv("VLM_API_URL", f"{DASHSCOPE_COMPAT_BASE_URL}/chat/completions" if DASHSCOPE_API_KEY else "")

VLM_API_KEY = os.getenv("VLM_API_KEY", DASHSCOPE_API_KEY)

VLM_MODEL = os.getenv("VLM_MODEL", QWEN_VLM_MODEL)

VLM_RATE_LIMIT = int(os.getenv("VLM_RATE_LIMIT", "8"))  # requests per minute



# 是否启用 VLM 统一图片分析（用一个 prompt 同时完成分类 + 提取/描述）

USE_VLM_UNIFIED_ANALYSIS = (

    os.getenv("USE_VLM_UNIFIED_ANALYSIS", "false").strip().lower() == "true"

)



OCR_STRATEGY = os.getenv(

    "OCR_STRATEGY",

    "qwen_first" if DASHSCOPE_API_KEY else "ocr_first",

).strip().lower()



# Celery

CELERY_CONCURRENCY = int(os.getenv("CELERY_CONCURRENCY", "3" if os.name == "nt" else "6"))

CELERY_POOL = os.getenv("CELERY_POOL", "threads" if os.name == "nt" else "prefork")



# Fallback scoring concurrency inside each submission.

# Primary path uses one batch AI call per submission, so keep fallback nested concurrency conservative.

DIMENSION_SCORE_CONCURRENCY = int(os.getenv("DIMENSION_SCORE_CONCURRENCY", "1"))



# Queue keys

TASK_QUEUE_KEY = "grading:tasks"

RESULT_CHANNEL = "grading:results"
# Fine-grained per-submission stage progress channel (consumed by backend SSE).
PROGRESS_CHANNEL = "grading:progress"
# 是否启用学生报告封面"课程目标"表的 VLM 首页识别。
COVER_RECOGNITION_ENABLED = (
    os.getenv("COVER_RECOGNITION_ENABLED", "true").strip().lower() == "true"
)

# 是否启用代码逻辑分析 Agent(AI 阅读代码证据定位逻辑/边界问题)。
CODE_ANALYSIS_ENABLED = (
    os.getenv("CODE_ANALYSIS_ENABLED", "true").strip().lower() == "true"
)

# 是否启用分层改进建议 Agent(打分后按薄弱程度聚合)。
IMPROVEMENT_PLAN_ENABLED = (
    os.getenv("IMPROVEMENT_PLAN_ENABLED", "true").strip().lower() == "true"
)

# 是否在沙箱内实际执行学生代码(默认关闭,后期增强预留)。
CODE_EXEC_ENABLED = (
    os.getenv("CODE_EXEC_ENABLED", "false").strip().lower() == "true"
)



# Milvus

MILVUS_HOST = os.getenv("MILVUS_HOST", "localhost")

MILVUS_PORT = int(os.getenv("MILVUS_PORT", "19530"))

MILVUS_COLLECTION = "course_chunks"



# RAG Queue

RAG_TASK_QUEUE_KEY = "rag:tasks"



try:

    from local_settings import *  # noqa: F401,F403

except Exception:

    pass

