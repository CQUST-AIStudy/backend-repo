# AI_Ds Deployment Guide

## 1. What needs to be deployed

This project is not a single-process app. In production it is split into these parts:

- `AI_Ds`: Spring Boot backend, packaged as one runnable JAR
- `AI_Ds-vue`: Vue frontend, packaged as static files in `dist/`
- MySQL 8: main business database
- Redis: cache, quota, grading queue, RAG queue
- MinIO: uploaded files, generated ZIPs, reports, extracted text

Optional but important for full features:

- `grading_worker`: async grading / document AI / queue consumer
- `grading_worker/main.py` FastAPI service: rerank API and worker health endpoint
- Milvus: vector retrieval for RAG knowledge base
- PTA spider service: only needed if you want PTA sync features online

## 2. Recommended deployment topology

### Minimum usable deployment

- 1 cloud server
- Nginx
- `AI_Ds` backend
- MySQL
- Redis
- MinIO
- `AI_Ds-vue/dist`

This is enough for login, normal data APIs, uploads, and most non-RAG pages.

### Full-feature deployment

- Everything above
- `grading_worker`
- FastAPI rerank API
- Milvus
- PTA spider service if you need PTA sync

This is the version you want if you need document grading, agent jobs, ZIP organize, and course-space RAG retrieval.

## 3. Configuration you must prepare

### Backend required

- `DB_HOST`
- `DB_PORT`
- `DB_NAME`
- `DB_USERNAME`
- `DB_PASSWORD`
- `REDIS_HOST`
- `REDIS_PORT`
- `MINIO_ENDPOINT`
- `MINIO_ACCESS_KEY`
- `MINIO_SECRET_KEY`
- `MINIO_BUCKET`
- `JWT_SECRET`

### AI-related

- `AI_PROVIDER`
- `OPENAI_API_KEY`
- `OPENAI_BASE_URL`
- `OPENAI_MODEL`

If you use embeddings / VLM / RAG OCR fallback, also prepare:

- `DASHSCOPE_API_KEY`

### Optional RAG

- `MILVUS_HOST`
- `MILVUS_PORT`
- `RAG_LANGCHAIN4J_ENABLED`

### Optional PTA

- `PTA_SPIDER_URL`

### Optional cross-origin deployment

If frontend and backend are on different origins, set:

- `CORS_ALLOWED_ORIGIN_PATTERNS`

Example:

```text
CORS_ALLOWED_ORIGIN_PATTERNS=https://your-domain.com,https://admin.your-domain.com
```

If you use Nginx reverse proxy and make frontend + backend same-origin, you usually do not need this.

## 4. Important project-specific caveats

- Backend defaults to database `ptadatabase`, while `deploy/local/docker-compose.local.yml` defaults MySQL to `tap`. Set `MYSQL_DATABASE=ptadatabase` in `deploy/local/.env.local`, or explicitly set `DB_NAME=ptadatabase`.
- Frontend production build should be served behind Nginx and call backend through same-origin `/api`.
- `grading_worker` originally expected `DB_USER` / `DB_PASS` and `DEEPSEEK_*`; it now also accepts backend-style `DB_USERNAME` / `DB_PASSWORD` and `OPENAI_*`, so one env file can drive both backend and worker.
- Do not use the local dev scripts for production. `scripts/run_backend_dev.ps1` forces the `dev` profile and local defaults.
- `deploy/local/.env.local` contains local secret values when configured. Keep it untracked, rotate any values that were previously exposed, and do not reuse them directly online.

## 5. Infrastructure startup

The reusable local infrastructure compose file is:

- `deploy/local/docker-compose.local.yml`

For this project, use it as the base, but make sure:

- MySQL database name is `ptadatabase`
- MinIO bucket is `tap-files`
- Start Milvus only if you need RAG

Example:

```powershell
.\deploy\local\start-infra.ps1
.\deploy\local\start-infra.ps1 -Milvus
```

Recommended secure overrides:

```env
MYSQL_DATABASE=ptadatabase
MYSQL_ROOT_PASSWORD=replace-me
REDIS_PORT=6379
MINIO_ROOT_USER=replace-me
MINIO_ROOT_PASSWORD=replace-me
MINIO_BUCKET=tap-files
MILVUS_PORT=19530
```

## 6. Backend packaging and startup

### Package

```bash
cd AI_Ds
mvn -q -DskipTests clean package
```

Output JAR:

- `AI_Ds/target/teaching-assistant-backend-0.0.1-SNAPSHOT.jar`

### Start

```bash
export DB_HOST=127.0.0.1
export DB_PORT=3307
export DB_NAME=ptadatabase
export DB_USERNAME=root
export DB_PASSWORD=replace-me
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379
export MINIO_ENDPOINT=http://127.0.0.1:19000
export MINIO_ACCESS_KEY=replace-me
export MINIO_SECRET_KEY=replace-me
export MINIO_BUCKET=tap-files
export JWT_SECRET=replace-with-a-random-string-at-least-32-chars
export AI_PROVIDER=openai
export OPENAI_BASE_URL=https://api.deepseek.com/v1
export OPENAI_API_KEY=replace-me
export OPENAI_MODEL=deepseek-chat
export DASHSCOPE_API_KEY=replace-me
export SPRING_PROFILES_ACTIVE=prod

java -jar target/teaching-assistant-backend-0.0.1-SNAPSHOT.jar
```

Health check:

- `http://127.0.0.1:8081/actuator/health`

## 7. Worker deployment

Deploy `grading_worker` only if you need async grading / document processing / queue tasks.

Install dependencies:

```bash
cd grading_worker
pip install -r requirements.txt
```

Start processes:

```bash
export DB_HOST=127.0.0.1
export DB_PORT=3307
export DB_NAME=ptadatabase
export DB_USERNAME=root
export DB_PASSWORD=replace-me
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379
export MINIO_ENDPOINT=http://127.0.0.1:19000
export MINIO_ACCESS_KEY=replace-me
export MINIO_SECRET_KEY=replace-me
export MINIO_BUCKET=tap-files
export OPENAI_BASE_URL=https://api.deepseek.com/v1
export OPENAI_API_KEY=replace-me
export OPENAI_MODEL=deepseek-chat
export DASHSCOPE_API_KEY=replace-me
export OCR_STRATEGY=vlm_only
export CELERY_CONCURRENCY=4

python run_worker.py
python run_consumer.py
uvicorn main:app --host 0.0.0.0 --port 8101
```

Notes:

- Backend pushes grading and RAG tasks into Redis queues.
- Without the worker, those async jobs will stay pending.
- The FastAPI app in `grading_worker/main.py` serves `/health` and `/rerank`.
- If backend RAG rerank is enabled, `RAG_RERANK_ENDPOINT` must point to this FastAPI service.

## 8. Frontend packaging and startup

### Package

```bash
cd AI_Ds-vue
npm install
npm run build
```

Output directory:

- `AI_Ds-vue/dist`

Because production default API base is now relative, `dist` can be placed behind the same Nginx domain and call `/api` directly.

If you insist on cross-origin deployment, build with:

```bash
VUE_APP_API_BASE_URL=https://api.your-domain.com npm run build
```

## 9. Nginx example

```nginx
server {
    listen 80;
    server_name your-domain.com;

    root /srv/ai-ds/frontend;
    index index.html;

    client_max_body_size 520m;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /actuator/health {
        proxy_pass http://127.0.0.1:8081;
    }
}
```

## 10. Suggested deployment order

1. Start MySQL, Redis, MinIO
2. Start Milvus if needed
3. Start backend and confirm `/actuator/health` is `UP`
4. Run Flyway migration automatically through backend startup
5. Start `grading_worker` if async features are needed
6. Upload frontend `dist` to Nginx directory
7. Test login, upload, and one grading task

## 11. What I would deploy for your project

For your current codebase, the safest first production rollout is:

1. `AI_Ds` backend
2. `AI_Ds-vue` frontend
3. MySQL + Redis + MinIO
4. Nginx reverse proxy

Then add:

1. `grading_worker`
2. Milvus
3. PTA spider

This reduces first-release complexity and lets you verify core business flow before enabling the heavy AI pipeline.

## 12. Deployment bundle added to this repo

The repository now includes a production deployment bundle in:

- `deploy/prod/docker-compose.prod.yml`
- `deploy/prod/.env.prod.example`
- `deploy/prod/nginx.conf`
- `deploy/prod/systemd/ai-ds-prod.service`
- `deploy/prod/systemd/ai-ds-prod-rebuild.service`

Use `deploy/prod/README.md` as the execution entry point when you deploy to the cloud server.
