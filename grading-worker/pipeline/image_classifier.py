"""Rule-based image classifier for grading pipeline."""
import io
import numpy as np
from PIL import Image
from models.pipeline_models import ImageKind

VALID_KINDS = {ImageKind.CODE_SCREENSHOT, ImageKind.TERMINAL_LOG,
               ImageKind.DIAGRAM, ImageKind.PLOT, ImageKind.OTHER}


def classify_image(image_bytes: bytes) -> ImageKind:
    """Classify an image into one of the predefined categories.
    
    Conservative classification: when in doubt, classify as CODE_SCREENSHOT
    so the image goes through OCR rather than being skipped.
    """
    try:
        img = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    except Exception:
        return ImageKind.OTHER

    w, h = img.size
    if w == 0 or h == 0:
        return ImageKind.OTHER

    # Skip tiny images (icons, bullets, decorations)
    if w < 50 or h < 50:
        return ImageKind.OTHER

    aspect = w / h
    arr = np.array(img)

    # Compute basic color statistics
    mean_rgb = arr.mean(axis=(0, 1))
    std_rgb = arr.std(axis=(0, 1))
    brightness = mean_rgb.mean()
    color_variance = std_rgb.mean()

    # Dark background + wide aspect → terminal log
    if brightness < 60 and aspect > 1.2:
        return ImageKind.TERMINAL_LOG

    # Very colorful with high variance → likely a plot or diagram
    if color_variance > 60:
        gray = np.mean(arr, axis=2)
        edge_density = np.mean(np.abs(np.diff(gray, axis=1))) + np.mean(np.abs(np.diff(gray, axis=0)))
        if edge_density > 20:
            return ImageKind.DIAGRAM
        else:
            return ImageKind.PLOT

    # Default: treat as code screenshot so it goes through OCR
    return ImageKind.CODE_SCREENSHOT
