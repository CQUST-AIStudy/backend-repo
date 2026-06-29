"""OCR processing using PaddleOCR with multi-pass image enhancement fallback."""
import io
import threading

import numpy as np
from PIL import Image, ImageEnhance, ImageFilter, ImageOps

from models.pipeline_models import OcrLine, OcrResult

_thread_local = threading.local()


def _get_ocr():
    engine = getattr(_thread_local, "ocr_engine", None)
    if engine is None:
        from paddleocr import PaddleOCR

        engine = PaddleOCR(use_angle_cls=True, lang="ch", show_log=False)
        _thread_local.ocr_engine = engine
    return engine


def fullwidth_to_halfwidth(text: str) -> str:
    """Convert fullwidth ASCII characters (U+FF01-U+FF5E) to halfwidth (U+0021-U+007E)."""
    result = []
    for ch in text:
        code = ord(ch)
        if 0xFF01 <= code <= 0xFF5E:
            result.append(chr(code - 0xFEE0))
        elif code == 0x3000:  # fullwidth space
            result.append(" ")
        else:
            result.append(ch)
    return "".join(result)


def post_process(text: str) -> str:
    """Normalize OCR text for scoring."""
    return fullwidth_to_halfwidth(text or "").strip()


def _prepare_image(image_bytes: bytes) -> Image.Image | None:
    """Open image bytes and flatten alpha channels onto a white background."""
    try:
        img = Image.open(io.BytesIO(image_bytes))
    except Exception:
        return None

    if img.mode in ("RGBA", "LA") or (img.mode == "P" and "transparency" in img.info):
        base = Image.new("RGB", img.size, "white")
        base.paste(img.convert("RGBA"), mask=img.convert("RGBA").getchannel("A"))
        return base
    return img.convert("RGB")


def _resize_for_ocr(img: Image.Image, min_side: int = 1600, max_long_side: int = 3200) -> Image.Image:
    """Upscale small screenshots and cap very large images to a reasonable OCR size."""
    w, h = img.size
    if w <= 0 or h <= 0:
        return img

    scale = 1.0
    short_side = min(w, h)
    long_side = max(w, h)

    if short_side < min_side:
        scale = max(scale, min_side / short_side)
    if long_side * scale > max_long_side:
        scale = min(scale, max_long_side / long_side)

    if abs(scale - 1.0) < 0.05:
        return img

    new_size = (max(1, int(w * scale)), max(1, int(h * scale)))
    return img.resize(new_size, Image.Resampling.LANCZOS)


def _otsu_threshold(gray: Image.Image) -> Image.Image:
    """Convert grayscale image to binary using Otsu threshold."""
    arr = np.array(gray)
    if arr.size == 0:
        return gray

    hist, _ = np.histogram(arr.flatten(), bins=256, range=(0, 256))
    total = arr.size
    sum_total = np.dot(np.arange(256), hist)

    sum_bg = 0.0
    weight_bg = 0.0
    max_var = -1.0
    threshold = 127

    for idx in range(256):
        weight_bg += hist[idx]
        if weight_bg == 0:
            continue
        weight_fg = total - weight_bg
        if weight_fg == 0:
            break
        sum_bg += idx * hist[idx]
        mean_bg = sum_bg / weight_bg
        mean_fg = (sum_total - sum_bg) / weight_fg
        between_var = weight_bg * weight_fg * (mean_bg - mean_fg) ** 2
        if between_var > max_var:
            max_var = between_var
            threshold = idx

    binary = np.where(arr > threshold, 255, 0).astype(np.uint8)
    return Image.fromarray(binary, mode="L")


def _looks_dark_ui(img: Image.Image) -> bool:
    """Heuristic for terminal/code screenshots with dark background."""
    gray = np.array(img.convert("L"))
    if gray.size == 0:
        return False
    mean_val = float(gray.mean())
    dark_ratio = float((gray < 80).mean())
    return mean_val < 110 and dark_ratio > 0.35


def _build_candidates(original: Image.Image) -> list[tuple[str, Image.Image]]:
    """Prepare OCR candidate images with different enhancement strategies."""
    candidates: list[tuple[str, Image.Image]] = []

    base = _resize_for_ocr(original)
    gray = ImageOps.autocontrast(base.convert("L"))
    gray = gray.filter(ImageFilter.MedianFilter(size=3))
    gray = ImageEnhance.Contrast(gray).enhance(2.0)
    gray = ImageEnhance.Sharpness(gray).enhance(1.8)
    gray = gray.filter(ImageFilter.UnsharpMask(radius=1.2, percent=180, threshold=2))

    binary = _otsu_threshold(gray)
    binary = ImageOps.autocontrast(binary)

    candidates.append(("base", base))
    candidates.append(("gray_enhanced", gray.convert("RGB")))
    candidates.append(("binary", binary.convert("RGB")))

    if _looks_dark_ui(base):
        inv_gray = ImageOps.invert(gray)
        inv_binary = ImageOps.invert(binary)
        candidates.append(("dark_invert_gray", inv_gray.convert("RGB")))
        candidates.append(("dark_invert_binary", inv_binary.convert("RGB")))

    return candidates


def _ocr_once(img_array: np.ndarray) -> OcrResult:
    """Run one OCR pass against a prepared numpy image."""
    try:
        ocr = _get_ocr()
        results = ocr.ocr(img_array, cls=True)
    except Exception:
        return OcrResult(text="", confidence=0.0, lines=[])

    if not results or not results[0]:
        return OcrResult(text="", confidence=0.0, lines=[])

    lines = []
    total_conf = 0.0
    text_parts = []
    for line_data in results[0]:
        try:
            bbox_points = line_data[0]
            text_info = line_data[1]
            text = post_process(text_info[0])
            conf = float(text_info[1])
            bbox = [
                float(bbox_points[0][0]),
                float(bbox_points[0][1]),
                float(bbox_points[2][0]),
                float(bbox_points[2][1]),
            ]
            if not text:
                continue
            lines.append(OcrLine(text=text, bbox=bbox, confidence=conf))
            text_parts.append(text)
            total_conf += conf
        except Exception:
            continue

    if not lines:
        return OcrResult(text="", confidence=0.0, lines=[])

    lines.sort(key=lambda l: l.bbox[1] if l.bbox else 0)
    full_text = "\n".join(text_parts)
    avg_conf = total_conf / len(lines)
    return OcrResult(text=full_text, confidence=avg_conf, lines=lines)


def _score_result(result: OcrResult) -> float:
    txt_len = len((result.text or "").strip())
    line_bonus = len(result.lines) * 8
    return (txt_len + line_bonus) * max(result.confidence, 0.05)


def _is_good_enough(result: OcrResult) -> bool:
    txt = (result.text or "").strip()
    return len(txt) >= 40 or (len(txt) >= 20 and result.confidence >= 0.72)


def run_ocr(image_bytes: bytes) -> OcrResult:
    """Run PaddleOCR on image bytes with enhancement fallback for low-quality scans."""
    original = _prepare_image(image_bytes)
    if original is None:
        return OcrResult(text="", confidence=0.0, lines=[])

    candidates = []
    for _, prepared in _build_candidates(original):
        result = _ocr_once(np.array(prepared))
        candidates.append(result)
        if _is_good_enough(result):
            return result

    return max(candidates, key=_score_result, default=OcrResult(text="", confidence=0.0, lines=[]))
