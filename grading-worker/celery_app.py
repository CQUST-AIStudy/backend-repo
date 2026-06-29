"""Celery application configuration."""
import os
import sys

# Ensure grading_worker directory is on sys.path so Celery can discover modules
_this_dir = os.path.dirname(os.path.abspath(__file__))
if _this_dir not in sys.path:
    sys.path.insert(0, _this_dir)

from celery import Celery
from config import REDIS_URL, CELERY_CONCURRENCY

app = Celery("grading_worker", broker=REDIS_URL, backend=REDIS_URL)

app.conf.update(
    task_serializer="json",
    accept_content=["json"],
    result_serializer="json",
    timezone="Asia/Shanghai",
    enable_utc=True,
    worker_concurrency=CELERY_CONCURRENCY,
    task_acks_late=True,
    worker_prefetch_multiplier=1,
    task_default_queue="grading",
)

# Auto-discover tasks
app.autodiscover_tasks(["tasks"])
