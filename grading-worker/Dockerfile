# Grading worker image (slim): OCR via Qwen VLM (vlm_only), no PaddleOCR / sentence-transformers.
FROM python:3.11-slim-bookworm

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    TZ=Asia/Shanghai

WORKDIR /app

# Slim dependency set: heavy OCR/embedding libs excluded (lazy-imported, unused with OCR_STRATEGY=vlm_only)
COPY requirements-docker.txt .
RUN pip install -r requirements-docker.txt -i https://pypi.tuna.tsinghua.edu.cn/simple

COPY . .
# local_settings.py must not leak developer overrides into the image
RUN rm -f local_settings.py

CMD ["python", "-u", "run_worker.py"]
