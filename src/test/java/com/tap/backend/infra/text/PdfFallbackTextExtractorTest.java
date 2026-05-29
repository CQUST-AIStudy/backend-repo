package com.tap.backend.infra.text;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tap.backend.ai.AiProperties;
import com.tap.backend.service.DocumentIngestProperties;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

class PdfFallbackTextExtractorTest {

  @Test
  void ocrFallbackExtractsTextFromImageOnlyPdf() throws Exception {
    DocumentIngestProperties props =
        new DocumentIngestProperties(20_000, true, true, "tesseract", "eng", 4, false, 0);
    AiProperties aiProperties = new AiProperties("dashscope", null, null, null);
    PdfFallbackTextExtractor extractor = new PdfFallbackTextExtractor(props, aiProperties);

    String extracted = extractor.extract(buildImageOnlyPdf(), "ocr-smoke.pdf");
    String normalized = extracted == null ? "" : extracted.replaceAll("\\s+", " ").toLowerCase();

    assertFalse(normalized.isBlank(), "OCR should return non-empty text for image-only PDF");
    assertTrue(
        normalized.contains("rag") || normalized.contains("ocr") || normalized.contains("12345"),
        "OCR output should contain the rendered marker text, actual: " + normalized);
  }

  private byte[] buildImageOnlyPdf() throws Exception {
    BufferedImage image = new BufferedImage(1400, 900, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
      graphics.setColor(Color.BLACK);
      graphics.setFont(new Font("Arial", Font.BOLD, 86));
      graphics.drawString("RAG OCR 12345", 130, 250);
      graphics.setFont(new Font("Arial", Font.PLAIN, 48));
      graphics.drawString("Teacher knowledge base smoke test", 130, 380);
    } finally {
      graphics.dispose();
    }

    ByteArrayOutputStream imageOut = new ByteArrayOutputStream();
    ImageIO.write(image, "png", imageOut);

    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream pdfOut = new ByteArrayOutputStream()) {
      PDPage page = new PDPage(PDRectangle.A4);
      document.addPage(page);
      PDImageXObject pdImage = PDImageXObject.createFromByteArray(document, imageOut.toByteArray(), "ocr-page");
      try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
        contentStream.drawImage(pdImage, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
      }
      document.save(pdfOut);
      return pdfOut.toByteArray();
    }
  }
}
