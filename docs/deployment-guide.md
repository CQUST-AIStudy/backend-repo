# AI_Ds Deployment Guide

## Runtime Components

- `AI_Ds`: Spring Boot backend for auth, business APIs, documents, grading, PTA integration, and `/rag` proxying.
- `rag-service`: independent FastAPI RAG service. All knowledge-base indexing, retrieval, chat, annotations, and RAG analytics live here.
- `AI_Ds-vue`: Vue frontend.
- MySQL 8: main business database.
- Redis: cache, quota, and async queues.
- MinIO: uploaded files, generated ZIPs, reports, and extracted text.

Optional services:

- `grading_worker`: async grading and document AI tasks.
- `pta-spider`: PTA sync.
- `recommendation-service`: recommendation APIs.

Milvus and Java in-process RAG are no longer part of the active deployment.

## Required Backend Environment

```env
DB_HOST=127.0.0.1
DB_PORT=3306
DB_NAME=ptadatabase
DB_USERNAME=root
DB_PASSWORD=replace-me
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
MINIO_ENDPOINT=http://127.0.0.1:19000
MINIO_ACCESS_KEY=replace-me
MINIO_SECRET_KEY=replace-me
MINIO_BUCKET=tap-files
JWT_SECRET=replace-with-a-random-string-at-least-32-chars
AI_PROVIDER=openai
OPENAI_BASE_URL=https://api.deepseek.com/v1
OPENAI_API_KEY=replace-me
OPENAI_MODEL=deepseek-chat
RAG_SERVICE_BASE_URL=http://127.0.0.1:8001
SPRING_PROFILES_ACTIVE=prod
```

## Required RAG Service Environment

```env
RAG_HOST=0.0.0.0
RAG_PORT=8001
RAG_DATA_DIR=./data
RAG_JWT_SECRET=replace-with-same-value-as-JWT_SECRET
RAG_JWT_ISSUER=tap
DASHSCOPE_API_KEY=replace-me
```

`RAG_JWT_SECRET` must match the Java backend `JWT_SECRET`, because the backend proxy issues bearer tokens for `rag-service`.

## Local Docker Compose

The root `docker-compose.yml` includes `rag-service` and configures the Java backend with:

```env
RAG_SERVICE_BASE_URL=http://rag-service:8001
```

Start the local stack with:

```bash
docker compose up --build
```

Frontend calls same-origin `/rag/...`; Nginx can proxy that directly to `rag-service`, while Java keeps a `/rag` proxy for legacy-session based local access.

## Backend Packaging

```bash
cd backend-repo/AI_Ds
mvn -q -DskipTests clean package
java -jar target/teaching-assistant-backend-0.0.1-SNAPSHOT.jar
```

Health check:

- `http://127.0.0.1:8081/actuator/health`

## RAG Service Startup

```bash
cd rag-service
uv sync
uv run uvicorn app.main:app --host 0.0.0.0 --port 8001
```

Health check:

- `http://127.0.0.1:8001/health`

## Nginx Sketch

```nginx
location /api/ {
    proxy_pass http://127.0.0.1:8081/api/;
}

location /rag/ {
    proxy_pass http://127.0.0.1:8001/rag/;
    proxy_read_timeout 300s;
}
```
