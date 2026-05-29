package com.tap.backend.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.paper.PaperEntity;
import com.tap.backend.repo.PaperRepository;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DoiFetchService {
  private final PaperRepository paperRepository;
  private final ObjectMapper objectMapper;
  private final HttpClient http = HttpClient.newHttpClient();

  public DoiFetchService(PaperRepository paperRepository, ObjectMapper objectMapper) {
    this.paperRepository = paperRepository;
    this.objectMapper = objectMapper;
  }

  /** 通过 DOI 从 Crossref 获取元数据并存库，scopeKey = "doi:" + doi */
  public PaperEntity fetchByDoi(String doi) throws Exception {
    String url = "https://api.crossref.org/works/" + URLEncoder.encode(doi, StandardCharsets.UTF_8);
    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
        .header("User-Agent", "TAP/1.0 (mailto:tap@example.com)").GET().build();
    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) throw new IllegalArgumentException("DOI not found: " + doi);

    JsonNode work = objectMapper.readTree(resp.body()).path("message");
    String title = work.path("title").path(0).asText("");
    String abs   = work.path("abstract").asText("").replaceAll("<[^>]+>", "").trim();

    List<String> authors = new ArrayList<>();
    for (JsonNode a : work.path("author")) {
      String name = (a.path("given").asText("") + " " + a.path("family").asText("")).trim();
      if (!name.isBlank()) authors.add(name);
    }

    String scopeKey = "doi:" + doi;
    PaperEntity paper = paperRepository.findByArxivId(scopeKey).orElseGet(PaperEntity::new);
    paper.setArxivId(scopeKey);
    paper.setTitle(title.isBlank() ? doi : title);
    paper.setAbstractText(abs);
    paper.setPdfUrl(work.path("URL").asText(""));
    paper.setAuthors(authors);
    paper.setCategories(List.of());
    return paperRepository.save(paper);
  }
}
