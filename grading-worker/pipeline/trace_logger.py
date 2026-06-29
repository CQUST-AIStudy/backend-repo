"""Trace logging for pipeline steps."""
import json
import time
from contextlib import contextmanager
from models.db_models import GradingTrace, get_session


@contextmanager
def trace_step(submission_id: int, step: str):
    """Context manager that records a pipeline step trace to the database."""
    start = time.time()
    info = {
        "status": "SUCCESS",
        "error_message": None,
        "model_used": None,
        "input_tokens": None,
        "output_tokens": None,
    }
    try:
        yield info
    except Exception as e:
        info["status"] = "FAILED"
        info["error_message"] = str(e)[:500]
        raise
    finally:
        duration_ms = int((time.time() - start) * 1000)
        try:
            session = get_session()
            trace = GradingTrace(
                submission_id=submission_id,
                step=step,
                status=info["status"],
                duration_ms=duration_ms,
                model_used=info.get("model_used"),
                input_tokens=info.get("input_tokens"),
                output_tokens=info.get("output_tokens"),
                error_message=info.get("error_message"),
                metadata_json=_build_metadata_json(info),
            )
            session.add(trace)
            session.commit()
        except Exception:
            pass  # Don't fail the pipeline for trace logging errors
        finally:
            try:
                session.close()
            except Exception:
                pass


def _build_metadata_json(info: dict) -> str | None:
    metadata = {
        key: value
        for key, value in info.items()
        if key not in {"status", "error_message", "model_used", "input_tokens", "output_tokens"}
        and value is not None
    }
    if not metadata:
        return None
    try:
        return json.dumps(metadata, ensure_ascii=False)
    except Exception:
        return None
