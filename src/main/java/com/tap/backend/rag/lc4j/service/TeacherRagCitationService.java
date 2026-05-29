package com.tap.backend.rag.lc4j.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tap.backend.rag.lc4j.dto.TeacherRagCitation;
import com.tap.backend.rag.lc4j.dto.TeacherRagCitationView;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TeacherRagCitationService {

  private static final Logger log = LoggerFactory.getLogger(TeacherRagCitationService.class);

  public List<TeacherRagCitation> normalize(List<? extends TeacherRagCitationView> citations) {
    if (citations == null || citations.isEmpty()) {
      return Collections.emptyList();
    }
    return citations.stream()
        .map(
            citation ->
                new TeacherRagCitation(
                    citation.index(),
                    citation.docName(),
                    citation.chapterPath(),
                    citation.pageRange(),
                    citation.score(),
                    citation.source()))
        .toList();
  }

  public String toJson(List<? extends TeacherRagCitationView> citations) {
    JsonArray arr = new JsonArray();
    if (citations != null) {
      for (TeacherRagCitationView citation : citations) {
        JsonObject obj = new JsonObject();
        obj.addProperty("index", citation.index());
        obj.addProperty("docName", citation.docName());
        obj.addProperty("chapterPath", citation.chapterPath());
        obj.addProperty("pageRange", citation.pageRange());
        obj.addProperty("score", citation.score());
        obj.addProperty("source", citation.source());
        arr.add(obj);
      }
    }
    return arr.toString();
  }

  public void writeTrailer(OutputStream outputStream, List<? extends TeacherRagCitationView> citations) {
    try {
      String trailer = "\n\n<!--CITATIONS:" + toJson(citations) + "-->";
      outputStream.write(trailer.getBytes(StandardCharsets.UTF_8));
      outputStream.flush();
    } catch (IOException e) {
      log.warn("[RAG] failed to write citations trailer: {}", e.getMessage());
    }
  }
}
