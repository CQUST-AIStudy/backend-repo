package com.tap.backend.infra.text;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FileTextExtractor {
  private static final Logger log = LoggerFactory.getLogger(FileTextExtractor.class);
  private final PdfFallbackTextExtractor pdfFallbackTextExtractor;

  public FileTextExtractor(PdfFallbackTextExtractor pdfFallbackTextExtractor) {
    this.pdfFallbackTextExtractor = pdfFallbackTextExtractor;
  }

  public String extract(String filename, String contentType, byte[] bytes) {
    String lower = (filename == null ? "" : filename).toLowerCase(Locale.ROOT);
    try {
      if (lower.endsWith(".pdf") || "application/pdf".equalsIgnoreCase(contentType)) {
        try (PDDocument doc = PDDocument.load(bytes)) {
          String text = extractPdfTextWithPageBreaks(doc);
          log.debug("PDF extracted {} chars from {}", text == null ? 0 : text.length(), filename);
          if (isUsablePdfText(text)) {
            return text;
          }
          log.info("PDF text extraction quality is low for {}, switching to OCR/VLM fallback", filename);
        }
        String fallback = pdfFallbackTextExtractor.extract(bytes, filename);
        if (fallback != null && !fallback.isBlank()) {
          log.info("PDF fallback extractor produced {} chars for {}", fallback.length(), filename);
          return fallback;
        }
        return "";
      }
      if (lower.endsWith(".doc") && !lower.endsWith(".docx")
          || "application/msword".equalsIgnoreCase(contentType)) {
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(bytes));
             WordExtractor extractor = new WordExtractor(doc)) {
          return extractor.getText();
        }
      }
      if (lower.endsWith(".docx")
          || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equalsIgnoreCase(contentType)) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
          StringBuilder sb = new StringBuilder();
          doc.getParagraphs().forEach(p -> sb.append(p.getText()).append('\n'));
          return sb.toString();
        }
      }
      if (lower.endsWith(".pptx")
          || "application/vnd.openxmlformats-officedocument.presentationml.presentation".equalsIgnoreCase(contentType)) {
        try (XMLSlideShow pptx = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
          StringBuilder sb = new StringBuilder();
          int slideNum = 0;
          for (XSLFSlide slide : pptx.getSlides()) {
            slideNum++;
            sb.append("[Slide ").append(slideNum).append("]\n");
            for (var shape : slide.getShapes()) {
              if (shape instanceof XSLFTextShape ts) {
                String t = ts.getText();
                if (t != null && !t.isBlank()) {
                  sb.append(t).append('\n');
                }
              }
            }
            sb.append('\n');
          }
          log.debug("PPTX extracted {} chars from {}", sb.length(), filename);
          return sb.toString();
        }
      }
      if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv")
          || lower.endsWith(".json") || lower.endsWith(".xml")
          || (contentType != null && contentType.startsWith("text/"))) {
        return new String(bytes, StandardCharsets.UTF_8);
      }
      log.warn("Unsupported file type: filename={}, contentType={}", filename, contentType);
      return "";
    } catch (Exception e) {
      log.error("Text extraction failed for {}: {}", filename, e.getMessage());
      return "";
    }
  }

  private boolean isUsablePdfText(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    String visible = text.replaceAll("\\s+", "");
    if (visible.length() < 120) {
      return false;
    }
    long textLike = visible.codePoints()
        .filter(cp -> Character.isLetterOrDigit(cp) || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
        .count();
    double density = visible.isEmpty() ? 0.0 : (double) textLike / visible.length();
    long informativeLines = text.lines()
        .map(String::trim)
        .filter(line -> line.length() >= 8)
        .filter(line -> line.codePoints().filter(cp ->
            Character.isLetterOrDigit(cp) || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
        ).count() >= 6)
        .count();
    return density >= 0.45 && informativeLines >= 3;
  }

  private String extractPdfTextWithPageBreaks(PDDocument doc) throws Exception {
    PDFTextStripper stripper = new PDFTextStripper();
    StringBuilder sb = new StringBuilder();
    int pageCount = doc.getNumberOfPages();
    for (int pageNo = 1; pageNo <= pageCount; pageNo++) {
      stripper.setStartPage(pageNo);
      stripper.setEndPage(pageNo);
      String pageText = stripper.getText(doc);
      if (pageText != null) {
        sb.append(pageText.trim());
      }
      if (pageNo < pageCount) {
        sb.append("\n\f\n");
      }
    }
    return sb.toString();
  }
}
