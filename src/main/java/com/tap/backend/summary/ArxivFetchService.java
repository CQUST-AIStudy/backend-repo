package com.tap.backend.summary;

import com.tap.backend.domain.paper.PaperEntity;
import com.tap.backend.infra.text.FileTextExtractor;
import com.tap.backend.repo.PaperRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

@Service
public class ArxivFetchService {
  private final PaperRepository paperRepository;
  private final FileTextExtractor fileTextExtractor;
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(30))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

  public ArxivFetchService(PaperRepository paperRepository, FileTextExtractor fileTextExtractor) {
    this.paperRepository = paperRepository;
    this.fileTextExtractor = fileTextExtractor;
  }

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ArxivFetchService.class);

  public PaperEntity fetchAndSave(String arxivId) throws Exception {
    String url = "https://export.arxiv.org/api/query?id_list=" + arxivId;
    HttpResponse<byte[]> resp = fetchWithRetry(url, 3);
    if (resp.statusCode() == 429) throw new IllegalStateException("arXiv rate limit (429), please retry later");
    if (resp.statusCode() != 200) throw new IllegalStateException("arXiv API error: HTTP " + resp.statusCode());

    Document xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(new java.io.ByteArrayInputStream(resp.body()));
    NodeList entries = xml.getElementsByTagName("entry");
    if (entries.getLength() == 0) throw new IllegalArgumentException("paper not found on arXiv: " + arxivId);

    Element entry = (Element) entries.item(0);
    String title     = text(entry, "title").replaceAll("\\s+", " ").trim();
    String abs       = text(entry, "summary").replaceAll("\\s+", " ").trim();
    String published = text(entry, "published");
    String updated   = text(entry, "updated");

    List<String> authors = new ArrayList<>();
    NodeList authorNodes = entry.getElementsByTagName("author");
    for (int i = 0; i < authorNodes.getLength(); i++)
      authors.add(text((Element) authorNodes.item(i), "name"));

    List<String> categories = new ArrayList<>();
    NodeList catNodes = entry.getElementsByTagName("category");
    for (int i = 0; i < catNodes.getLength(); i++) {
      String term = ((Element) catNodes.item(i)).getAttribute("term");
      if (!term.isBlank()) categories.add(term);
    }

    // 下载 PDF 全文
    String fullText = fetchPdfText(arxivId);

    PaperEntity paper = paperRepository.findByArxivId(arxivId).orElseGet(PaperEntity::new);
    paper.setArxivId(arxivId);
    paper.setTitle(title.isBlank() ? arxivId : title);
    paper.setAbstractText(fullText.isBlank() ? abs : fullText);
    paper.setPdfUrl("https://arxiv.org/pdf/" + arxivId);
    paper.setAuthors(authors);
    paper.setCategories(categories);
    if (!published.isBlank()) paper.setPublishedAt(Instant.parse(published));
    if (!updated.isBlank())   paper.setUpdatedAt(Instant.parse(updated));
    return paperRepository.save(paper);
  }

  private HttpResponse<byte[]> fetchWithRetry(String url, int maxRetries) throws Exception {
    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
        .timeout(java.time.Duration.ofSeconds(60))
        .header("User-Agent", "TAP/1.0 (teacher-assistant; mailto:admin@example.com)").GET().build();
    for (int i = 0; i < maxRetries; i++) {
      try {
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 429) return resp;
        long wait = (long) Math.pow(2, i + 1) * 1000;
        log.warn("arXiv 429, retry {}/{} after {}ms", i + 1, maxRetries, wait);
        Thread.sleep(wait);
      } catch (java.net.http.HttpTimeoutException e) {
        log.warn("arXiv timeout on attempt {}/{}: {}", i + 1, maxRetries, e.getMessage());
        if (i == maxRetries - 1) throw e;
        Thread.sleep((long) Math.pow(2, i) * 1000);
      }
    }
    return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
  }

  private String fetchPdfText(String arxivId) {
    try {
      String pdfUrl = "https://arxiv.org/pdf/" + arxivId;
      HttpResponse<byte[]> resp = fetchWithRetry(pdfUrl, 3);
      if (resp.statusCode() != 200) {
        log.warn("arXiv PDF download failed for {}: HTTP {}", arxivId, resp.statusCode());
        return "";
      }
      try (org.apache.pdfbox.pdmodel.PDDocument doc =
          org.apache.pdfbox.pdmodel.PDDocument.load(resp.body())) {
        org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
        stripper.setStartPage(1);
        stripper.setEndPage(Math.min(8, doc.getNumberOfPages()));
        String text = stripper.getText(doc);
        return text.length() > 8000 ? text.substring(0, 8000) : text;
      }
    } catch (Exception e) {
      log.warn("arXiv PDF text extraction failed for {}: {}", arxivId, e.getMessage());
      return "";
    }
  }

  private String text(Element el, String tag) {
    NodeList nl = el.getElementsByTagName(tag);
    return nl.getLength() == 0 ? "" : nl.item(0).getTextContent() == null ? "" : nl.item(0).getTextContent();
  }
}
