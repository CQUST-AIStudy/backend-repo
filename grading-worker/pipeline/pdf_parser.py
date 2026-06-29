"""PDF parsing using PyMuPDF (fitz) to extract text and images."""
import fitz  # PyMuPDF
from models.pipeline_models import ParsedDocument, ParsedPage, ImageInfo

SCAN_RENDER_DPI = 260
LOW_TEXT_THRESHOLD = 40
FULL_PAGE_IMAGE_COVERAGE = 0.6
MIN_IMAGE_COVERAGE = 0.002  # Skip tiny decorative images (<0.2% of page)


def parse_pdf(pdf_bytes: bytes) -> ParsedDocument:
    """Parse a PDF and extract text + images per page."""
    try:
        doc = fitz.open(stream=pdf_bytes, filetype="pdf")
    except Exception as e:
        return ParsedDocument(error=f"PDF_PARSE_ERROR: {str(e)}")

    pages = []
    try:
        for page_num in range(len(doc)):
            page = doc[page_num]
            text = page.get_text("text") or ""
            page_area = max(float(page.rect.width * page.rect.height), 1.0)

            images = []
            max_image_coverage = 0.0
            seen_xrefs = set()
            for img_index, img in enumerate(page.get_images(full=True)):
                xref = img[0]
                if xref in seen_xrefs:
                    continue
                seen_xrefs.add(xref)
                try:
                    base_image = doc.extract_image(xref)
                    if base_image and base_image.get("image"):
                        # Get image bbox from page
                        img_rects = page.get_image_rects(xref)
                        bbox = []
                        if img_rects:
                            r = img_rects[0]
                            bbox = [r.x0, r.y0, r.x1, r.y1]
                            coverage = abs((r.x1 - r.x0) * (r.y1 - r.y0)) / page_area
                            if coverage < MIN_IMAGE_COVERAGE:
                                continue
                            max_image_coverage = max(max_image_coverage, coverage)

                        images.append(ImageInfo(
                            page=page_num + 1,
                            bbox=bbox,
                            image_bytes=base_image["image"],
                        ))
                except Exception:
                    continue

            # Fallback for scanned/image-heavy pages:
            # render whole page to PNG so OCR can still run on unified page content.
            if len(text.strip()) < LOW_TEXT_THRESHOLD and max_image_coverage < FULL_PAGE_IMAGE_COVERAGE:
                try:
                    pix = page.get_pixmap(dpi=SCAN_RENDER_DPI, alpha=False)
                    page_png = pix.tobytes("png")
                    images.append(ImageInfo(
                        page=page_num + 1,
                        bbox=[0.0, 0.0, float(page.rect.width), float(page.rect.height)],
                        image_bytes=page_png,
                    ))
                except Exception:
                    pass

            pages.append(ParsedPage(
                page_num=page_num + 1,
                text=text,
                images=images,
            ))
    except Exception as e:
        return ParsedDocument(error=f"PDF_PARSE_ERROR: {str(e)}")
    finally:
        doc.close()

    return ParsedDocument(pages=pages)
