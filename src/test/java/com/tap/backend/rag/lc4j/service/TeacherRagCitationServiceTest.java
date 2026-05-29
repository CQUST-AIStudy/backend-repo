package com.tap.backend.rag.lc4j.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tap.backend.rag.lc4j.dto.TeacherRagCitation;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeacherRagCitationServiceTest {

  @Test
  void shouldSerializeAndWriteCitationTrailer() {
    TeacherRagCitationService service = new TeacherRagCitationService();
    List<TeacherRagCitation> citations =
        List.of(new TeacherRagCitation(1, "讲义.pdf", "第一章", "1-2", 0.92, "local"));

    String json = service.toJson(citations);
    assertTrue(json.contains("\"docName\":\"讲义.pdf\""));
    assertTrue(json.contains("\"source\":\"local\""));

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    service.writeTrailer(outputStream, citations);
    String trailer = outputStream.toString(StandardCharsets.UTF_8);
    assertEquals("\n\n<!--CITATIONS:" + json + "-->", trailer);
  }
}
