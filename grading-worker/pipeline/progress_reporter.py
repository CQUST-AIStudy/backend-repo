"""Publish fine-grained per-submission stage progress to Redis.

The backend subscribes to PROGRESS_CHANNEL and fans the events out to the frontend over
SSE, so teachers see a live per-document, per-stage progress bar instead of only a
batch-level "completed count / total" percentage.

Message shape (JSON on PROGRESS_CHANNEL):
{
  "type": "submission_progress",
  "taskId": 12, "submissionId": 34,
  "stage": "scoring", "stageLabel": "AI 评分中",
  "percent": 70, "status": "RUNNING",
  "studentName": "张三", "message": null, "ts": 1730000000.0
}
"""
import json
import time

from config import PROGRESS_CHANNEL

# Ordered pipeline stages with their nominal completion percentage. The percentage is the
# value reported when the stage STARTS; "done" reports 100.
STAGES: list[tuple[str, str, int]] = [
    ("queued", "排队中", 3),
    ("parsing", "解析文档", 12),
    ("cover_recognize", "识别封面目标表", 22),
    ("evidence", "抽取证据", 38),
    ("scoring", "AI 评分中", 65),
    ("report", "生成批注报告", 90),
    ("done", "已完成", 100),
]
_STAGE_INDEX = {name: i for i, (name, _, _) in enumerate(STAGES)}
_STAGE_LABEL = {name: label for name, label, _ in STAGES}
_STAGE_PERCENT = {name: percent for name, _, percent in STAGES}


def stage_label(stage: str) -> str:
    return _STAGE_LABEL.get(stage, stage)


def stage_percent(stage: str) -> int:
    return _STAGE_PERCENT.get(stage, 0)


def publish_progress(redis_client, task_id, submission_id, stage,
                     status="RUNNING", message=None, student_name=None, percent=None):
    """Publish a single stage progress event. Never raises (progress is best-effort)."""
    if redis_client is None:
        return
    payload = {
        "type": "submission_progress",
        "taskId": task_id,
        "submissionId": submission_id,
        "stage": stage,
        "stageLabel": stage_label(stage),
        "percent": percent if percent is not None else stage_percent(stage),
        "status": status,
        "studentName": student_name,
        "message": message,
        "ts": time.time(),
    }
    try:
        redis_client.publish(PROGRESS_CHANNEL, json.dumps(payload, ensure_ascii=False))
    except Exception:
        pass
