# Copy this file to `local.env.ps1` and replace placeholders with real local values.
# `start-all.ps1` will load `local.env.ps1` automatically if it exists.
#
# IMPORTANT: For local dev, activate the "dev" Spring profile:
#   $env:SPRING_PROFILES_ACTIVE = "dev"
# The dev profile enables PtaSpiderAutoStarter, DevUserSeeder, and provides
# safe fallback defaults for DB_PASSWORD and JWT_SECRET.
# Without the dev profile, DB_PASSWORD and JWT_SECRET are REQUIRED.

# --- Required credentials (no insecure defaults in production) ---
$env:DB_PASSWORD = "your-db-password-here"
$env:JWT_SECRET  = "replace-with-a-long-random-secret-at-least-32-chars"

# --- Shared local infra (recommended with deploy/local/start-infra.ps1) ---
# MySQL is mapped to 3307 by default in deploy/local/docker-compose.local.yml.
# Keep DB_NAME as `ptadatabase` unless you intentionally migrated AI_Ds to another schema.
# $env:DB_HOST = "127.0.0.1"
# $env:DB_PORT = "3307"
# $env:DB_NAME = "ptadatabase"
# $env:DB_USERNAME = "root"
#
# Shared Redis / MinIO endpoints:
# $env:REDIS_HOST = "127.0.0.1"
# $env:REDIS_PORT = "6379"
# $env:MINIO_ENDPOINT = "http://127.0.0.1:19000"
# $env:MINIO_ACCESS_KEY = "minioadmin"
# $env:MINIO_SECRET_KEY = "minioadmin"
# $env:MINIO_BUCKET = "tap-files"
# Java backend proxies /rag requests to the independent rag-service.
# $env:RAG_SERVICE_BASE_URL = "http://127.0.0.1:8001"

$env:AI_PROVIDER = "openai"
$env:OPENAI_API_KEY = ""
$env:OPENAI_BASE_URL = "https://api.deepseek.com/v1"
$env:OPENAI_MODEL = "deepseek-chat"

$env:DEEPL_API_KEY = ""
# Set this for rag-service document indexing/retrieval.
$env:DASHSCOPE_API_KEY = ""
$env:VOLCANO_API_KEY = ""
$env:ARK_API_KEY = ""

# Optional migration flag: keep false unless you explicitly need the old standalone tap-backend.
# $env:START_LEGACY_TAP_BACKEND = "true"

# Optional TAP development seed users (only works with dev profile).
# $env:TAP_DEV_SEED_USERS_ENABLED = "true"
# $env:TAP_DEV_ADMIN_PASSWORD = "change-me"
# $env:TAP_DEV_TEACHER_PASSWORD = "change-me"

# Optional local service credentials
# $env:MINIO_ACCESS_KEY = "minioadmin"
# $env:MINIO_SECRET_KEY = "minioadmin"
