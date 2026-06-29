"""Launcher script that ensures correct working directory and starts Celery worker."""
import os
import sys

# Set working directory to this script's directory
os.chdir(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from celery_app import app
from config import CELERY_CONCURRENCY, CELERY_POOL

if __name__ == "__main__":
    concurrency = max(1, int(CELERY_CONCURRENCY))
    pool = CELERY_POOL or ("threads" if os.name == "nt" else "prefork")
    app.worker_main([
        "worker",
        "--loglevel=info",
        f"--concurrency={concurrency}",
        "-Q", "grading",
        f"--pool={pool}",
        "--prefetch-multiplier=1",
    ])
