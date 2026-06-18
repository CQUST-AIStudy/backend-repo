package com.tap.backend.infra.text;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tap.backend.ai.AiProperties;
import com.tap.backend.service.DocumentIngestProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PdfFallbackTextExtractor {
  private static final Logger log = LoggerFactory.getLogger(PdfFallbackTextExtractor.class);
  private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

  private final DocumentIngestProperties props;
  private final AiProperties aiProperties;
  private final OkHttpClient httpClient =
      new OkHttpClient.Builder()
          .connectTimeout(20, TimeUnit.SECONDS)
          .readTimeout(120, TimeUnit.SECONDS)
          .writeTimeout(30, TimeUnit.SECONDS)
          .build();

  public PdfFallbackTextExtractor(DocumentIngestProperties props, AiProperties aiProperties) {
    this.props = props;
    this.aiProperties = aiProperties;
  }

  public boolean isOcrAvailable() {
    String command = safe(props.ocrCommand(), "tesseract");
    try {
      ProcessBuilder pb = new ProcessBuilder(command, "--version");
      pb.redirectErrorStream(true);
      Process process = pb.start();
      boolean finished = process.waitFor(10, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        return false;
      }
      return process.exitValue() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  public String extract(byte[] pdfBytes, String filename) {
    String ocrText = extractByOcr(pdfBytes, filename);
    if (isUsableText(ocrText)) {
      return ocrText;
    }
    String vlmText = extractByVlm(pdfBytes, filename);
    return isUsableText(vlmText) ? vlmText : "";
  }

  private String extractByOcr(byte[] pdfBytes, String filename) {
    if (!props.ocrEnabled()) {
      return "";
    }
    String command = safe(props.ocrCommand(), "tesseract");
    int maxPages = positiveOrDefault(props.ocrMaxPages(), 24);
    List<String> pages = new ArrayList<>();
    try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
      PDFRenderer renderer = new PDFRenderer(document);
      int pageCount = Math.min(document.getNumberOfPages(), maxPages);
      for (int i = 0; i < pageCount; i++) {
        BufferedImage image = renderer.renderImageWithDPI(i, 220, ImageType.RGB);
        String pageText = runTesseract(command, image, i + 1);
        pages.add(pageText == null ? "" : pageText.trim());
      }
    } catch (Exception e) {
      log.warn("[DOC] OCR fallback failed for {}: {}", filename, e.getMessage());
    }
    return joinPages(pages);
  }

  private String runTesseract(String command, BufferedImage image, int pageNo)
      throws IOException, InterruptedException {
    Path tempImage = Files.createTempFile("tap-ocr-page-" + pageNo + "-", ".png");
    try {
      ImageIO.write(image, "png", tempImage.toFile());
      ProcessBuilder pb =
          new ProcessBuilder(
              command,
              tempImage.toAbsolutePath().toString(),
              "stdout",
              "-l",
              safe(props.ocrLanguage(), "chi_sim+eng"),
              "--psm",
              "6");
      pb.redirectErrorStream(true);
      Process process = pb.start();
      byte[] outputBytes = process.getInputStream().readAllBytes();
      boolean finished = process.waitFor(120, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        log.warn("[DOC] OCR timeout on page {}", pageNo);
        return "";
      }
      if (process.exitValue() != 0) {
        log.warn(
            "[DOC] OCR non-zero exit on page {}: {}",
            pageNo,
            new String(outputBytes, StandardCharsets.UTF_8));
        return "";
      }
      return new String(outputBytes, StandardCharsets.UTF_8);
    } finally {
      Files.deleteIfExists(tempImage);
    }
  }

  private String extractByVlm(byte[] pdfBytes, String filename) {
    if (!props.vlmFallbackEnabled()) {
      return "";
    }
    AiEndpoint endpoint = resolveDashscopeEndpoint();
    if (endpoint == null) {
      return "";
    }

    int maxPages = positiveOrDefault(props.vlmMaxPages(), 8);
    List<String> pages = new ArrayList<>();
    try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
      PDFRenderer renderer = new PDFRenderer(document);
      int pageCount = Math.min(document.getNumberOfPages(), maxPages);
      for (int i = 0; i < pageCount; i++) {
        BufferedImage image = renderer.renderImageWithDPI(i, 144, ImageType.RGB);
        String pageText = callVlm(endpoint, image, i + 1, filename);
        pages.add(pageText == null ? "" : pageText.trim());
      }
    } catch (Exception e) {
      log.warn("[DOC] VLM fallback failed for {}: {}", filename, e.getMessage());
    }
    return joinPages(pages);
  }

  private String callVlm(AiEndpoint endpoint, BufferedImage image, int pageNo, String filename)
      throws IOException {
    String dataUrl = toDataUrl(image);

    JsonObject reqBody = new JsonObject();
    reqBody.addProperty("model", endpoint.model());

    JsonArray messages = new JsonArray();
    JsonObject userMsg = new JsonObject();
    userMsg.addProperty("role", "user");
    JsonArray content = new JsonArray();

    JsonObject textItem = new JsonObject();
    textItem.addProperty("type", "text");
    textItem.addProperty("text", vlmPrompt(filename, pageNo));
    content.add(textItem);

    JsonObject imageItem = new JsonObject();
    imageItem.addProperty("type", "image_url");
    JsonObject imageUrl = new JsonObject();
    imageUrl.addProperty("url", dataUrl);
    imageItem.add("image_url", imageUrl);
    content.add(imageItem);

    userMsg.add("content", content);
    messages.add(userMsg);
    reqBody.add("messages", messages);

    Request request =
        new Request.Builder()
            .url(endpoint.baseUrl() + "/chat/completions")
            .addHeader("Authorization", "Bearer " + endpoint.apiKey())
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create(reqBody.toString(), JSON_MEDIA_TYPE))
            .build();

    try (Response response = httpClient.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        String errBody = response.body() != null ? response.body().string() : "unknown";
        log.warn("[DOC] VLM API error on page {}: {} {}", pageNo, response.code(), errBody);
        return "";
      }
      String resp = response.body() == null ? "" : response.body().string();
      JsonObject root = JsonParser.parseString(resp).getAsJsonObject();
      return root
          .getAsJsonArray("choices")
          .get(0)
          .getAsJsonObject()
          .getAsJsonObject("message")
          .get("content")
          .getAsString();
    }
  }

  private String toDataUrl(BufferedImage image) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    String base64 = Base64.getEncoder().encodeToString(out.toByteArray());
    return "data:image/png;base64," + base64;
  }

  private String vlmPrompt(String filename, int pageNo) {
    return "Extract the visible body text from this PDF page image. "
        + "Return plain text only. "
        + "Do not summarize, translate, explain, or add markdown. "
        + "If the page contains almost no readable text, return an empty string. "
        + "Filename: "
        + safe(filename, "unknown")
        + ". Page: "
        + pageNo
        + ".";
  }

  private AiEndpoint resolveDashscopeEndpoint() {
    AiProperties.Dashscope ds = aiProperties.dashscope();
    String apiKey = ds == null ? null : ds.apiKey();
    String baseUrl = ds == null ? null : ds.baseUrl();
    String model = ds == null ? null : ds.model();
    if (apiKey == null || apiKey.isBlank()) {
      apiKey = System.getenv("DASHSCOPE_API_KEY");
    }
    if (apiKey == null || apiKey.isBlank()) {
      return null;
    }
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    }
    if (model == null || model.isBlank()) {
      model = "qwen-vl-max-latest";
    }
    return new AiEndpoint(baseUrl.trim(), apiKey.trim(), model.trim());
  }

  private boolean isUsableText(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    String visible = text.replaceAll("\\s+", "");
    if (visible.length() < 80) {
      return false;
    }
    long alnumOrHan =
        visible.codePoints()
            .filter(
                cp ->
                    Character.isLetterOrDigit(cp)
                        || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
            .count();
    return alnumOrHan >= visible.length() * 0.45;
  }

  private int positiveOrDefault(int value, int fallback) {
    return value > 0 ? value : fallback;
  }

  private String safe(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private String joinPages(List<String> pages) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < pages.size(); i++) {
      sb.append(pages.get(i) == null ? "" : pages.get(i));
      if (i < pages.size() - 1) {
        sb.append("\n\f\n");
      }
    }
    return sb.toString().trim();
  }

  private record AiEndpoint(String baseUrl, String apiKey, String model) {}
}
