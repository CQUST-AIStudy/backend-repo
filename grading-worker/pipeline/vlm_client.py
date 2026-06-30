"""VLM client with hash-based caching via Redis."""
import hashlib
import json
import base64
import re
import time
import redis
import httpx
from config import VLM_API_URL, VLM_API_KEY, VLM_MODEL, REDIS_HOST, REDIS_PORT, REDIS_USERNAME, REDIS_PASSWORD
from models.pipeline_models import VlmResult

_redis_client = None
MAX_HTTP_RETRIES = 4
HTTP_RETRY_BASE_DELAY = 1.2
RETRYABLE_STATUS_CODES = {408, 409, 425, 429, 500, 502, 503, 504}


def _get_redis():
    global _redis_client
    if _redis_client is None:
        _redis_client = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, db=0, decode_responses=True, username=REDIS_USERNAME or None, password=REDIS_PASSWORD or None)
    return _redis_client


def compute_image_hash(image_bytes: bytes) -> str:
    """Compute SHA256 hash of image bytes for cache key."""
    return hashlib.sha256(image_bytes).hexdigest()


def _normalize_content_to_text(content) -> str:
    if content is None:
        return ""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for item in content:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict):
                text = item.get("text") or item.get("content") or item.get("value")
                if text:
                    parts.append(str(text))
        return "\n".join(parts)
    return str(content)


def _extract_json_from_text(raw: str):
    text = (raw or "").strip()
    if not text:
        return None

    if text.startswith("```"):
        first_lf = text.find("\n")
        last_fence = text.rfind("```")
        if first_lf >= 0 and last_fence > first_lf:
            text = text[first_lf + 1:last_fence].strip()

    # Try full parse first.
    try:
        return json.loads(text)
    except Exception:
        pass

    # Extract the outermost JSON object if wrapped with extra text.
    start = text.find("{")
    end = text.rfind("}")
    if start >= 0 and end > start:
        candidate = text[start:end + 1]
        try:
            return json.loads(candidate)
        except Exception:
            pass

    return None


def _is_retryable_http_error(exc: httpx.HTTPError) -> bool:
    if isinstance(exc, httpx.HTTPStatusError):
        return exc.response is not None and exc.response.status_code in RETRYABLE_STATUS_CODES
    if isinstance(exc, (httpx.TimeoutException, httpx.NetworkError, httpx.ProtocolError)):
        return True
    message = str(exc).lower()
    return (
        "eof occurred in violation of protocol" in message
        or "connection reset" in message
        or "temporarily unavailable" in message
        or "server disconnected" in message
    )


def call_vlm(image_bytes: bytes, task: str = "describe") -> VlmResult:
    """Call VLM API for multimodal extraction/understanding, with Redis caching."""
    img_hash = compute_image_hash(image_bytes)
    cache_key = f"vlm:cache:{task}:{img_hash}"

    # Check cache
    r = _get_redis()
    cached = r.get(cache_key)
    if cached:
        try:
            return VlmResult(description_json=json.loads(cached), cached=True)
        except Exception:
            pass

    # No VLM API configured — return empty
    if not VLM_API_URL:
        return VlmResult(description_json={"note": "VLM not configured"}, cached=False)

    try:
        b64_image = base64.b64encode(image_bytes).decode("utf-8")
        if task == "extract_text":
            prompt = (
                "Read this screenshot or scanned page carefully and return strict JSON only. "
                "Schema: {\"recognized_text\":\"...\",\"summary\":\"...\",\"confidence\":0.0}. "
                "recognized_text should contain the main visible text content in Chinese or original language. "
                "If the image is not text-heavy, still summarize the useful content."
            )
        elif task == "analyze":
            prompt = (
                "Analyze this image carefully and return strict JSON only. "
                "Schema: {\"image_type\":\"code_screenshot|terminal_log|diagram|plot|photo|other\","
                "\"recognized_text\":\"...\",\"summary\":\"...\",\"confidence\":0.0}. "
                "Choose image_type based on the main content. "
                "For code screenshots or terminal logs, put the extracted visible text in recognized_text. "
                "For diagrams, plots, photos, or other non-text-heavy images, put a concise Chinese description in summary. "
                "If visible text exists even in diagrams, include it in recognized_text. "
                "confidence should reflect how certain you are about the content (0.0-1.0)."
            )
        elif task == "parse_rubric":
            prompt = (
                "You are a grading rubric parser. Look at this rubric/scoring sheet image carefully. "
                "Identify every scoring dimension (such as 目标1, 目标2, 目标3, or other named criteria). "
                "For each dimension, extract: name, full description/criteria, max_score, level ranges "
                "(e.g. 优/良/中/及格/不及格 with numeric ranges), and any already-filled teacher score/level. "
                "Also extract the final total score if present. "
                "Return strict JSON only with this schema:\n"
                "{\n"
                '  "rubric_name": "评分表标题或课程名",\n'
                '  "dimensions": [\n'
                "    {\n"
                '      "name": "目标1",\n'
                '      "description": "该维度的完整考核内容",\n'
                '      "max_score": 20,\n'
                '      "level_ranges": {"优": "18-20", "良": "16-17", "中": "14-15", "及格": "12-13", "不及格": "0-11"},\n'
                '      "teacher_score": 17,\n'
                '      "teacher_level": "良"\n'
                "    }\n"
                "  ],\n"
                '  "total_score": 29,\n'
                '  "confidence": 0.95\n'
                "}\n"
                "Rules:\n"
                "1. Use the exact text from the image for name and description.\n"
                "2. max_score must be the numeric full marks shown in the '分值' column.\n"
                "3. level_ranges keys must match the levels shown (e.g. 优/良/中/及格/不及格).\n"
                "4. If a teacher score is already written, include it as teacher_score; otherwise omit or use null.\n"
                "5. If total score is shown, include it as total_score; otherwise omit or use null.\n"
                "6. Return only the JSON object, no markdown, no explanation."
            )
        else:
            prompt = (
                "Describe this image as short strict JSON only. "
                "Schema: {\"image_type\":\"diagram|plot|screenshot|other\",\"recognized_text\":\"...\","
                "\"summary\":\"...\",\"confidence\":0.0}. "
                "For diagrams or plots, summarize the key relationship or trend. "
                "If visible text exists, include it in recognized_text."
            )

        max_tokens = 1200 if task == "parse_rubric" else 500
        payload = {
            "model": VLM_MODEL,
            "messages": [
                {"role": "system", "content": "Return valid JSON only."},
                {
                    "role": "user",
                    "content": [
                        {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{b64_image}"}},
                        {"type": "text", "text": prompt}
                    ]
                }
            ],
            "max_tokens": max_tokens,
            "response_format": {"type": "json_object"},
        }

        headers = {"Authorization": f"Bearer {VLM_API_KEY}", "Content-Type": "application/json"}
        last_error = None
        for attempt in range(MAX_HTTP_RETRIES):
            try:
                resp = httpx.post(VLM_API_URL, json=payload, headers=headers, timeout=30.0)
                resp.raise_for_status()
                break
            except httpx.HTTPError as exc:
                last_error = exc
                if attempt == MAX_HTTP_RETRIES - 1 or not _is_retryable_http_error(exc):
                    raise
                time.sleep(HTTP_RETRY_BASE_DELAY * (attempt + 1))
        else:
            raise last_error

        data = resp.json()
        message = data.get("choices", [{}])[0].get("message", {}) or {}
        content = _normalize_content_to_text(message.get("content", "{}"))

        desc = _extract_json_from_text(content)
        if desc is None:
            plain = re.sub(r"\s+", " ", (content or "")).strip()
            if plain:
                # Keep useful plain text instead of treating it as full failure.
                desc = {"summary": plain[:2000], "recognized_text": plain[:2000], "confidence": 0.62}
            else:
                desc = {"raw": content}

        # Cache result (TTL 7 days)
        r.setex(cache_key, 604800, json.dumps(desc))
        return VlmResult(description_json=desc, cached=False)

    except Exception as e:
        return VlmResult(description_json={"error": str(e)}, cached=False)
