package com.tap.backend.translation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

public class DeepLHttpClient implements DeepLClient {
  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final String apiKey;
  private final int maxBatchSize;
  private final DeepLRateLimiter limiter;

  public DeepLHttpClient(RestClient restClient, ObjectMapper objectMapper, String apiKey, int maxBatchSize, DeepLRateLimiter limiter) {
    this.restClient = restClient;
    this.objectMapper = objectMapper;
    this.apiKey = apiKey;
    this.maxBatchSize = Math.max(1, maxBatchSize);
    this.limiter = limiter;
  }

  @Override
  public String name() {
    return "deepl";
  }

  @Override
  public List<String> translateText(List<String> texts, String targetLang) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException("DEEPL_API_KEY is empty");
    }
    if (texts == null || texts.isEmpty()) return List.of();
    String tl = normalizeLang(targetLang);
    if (tl.isBlank()) throw new IllegalArgumentException("targetLang required");

    List<String> out = new ArrayList<>(texts.size());
    for (int i = 0; i < texts.size(); i += maxBatchSize) {
      int end = Math.min(texts.size(), i + maxBatchSize);
      List<String> batch = texts.subList(i, end);
      limiter.acquire();
      out.addAll(callTranslate(batch, tl));
    }
    return out;
  }

  private List<String> callTranslate(List<String> batch, String targetLang) {
    try {
      var requestBody = new java.util.LinkedHashMap<String, Object>();
      requestBody.put("text", batch);
      requestBody.put("target_lang", targetLang);
      requestBody.put("preserve_formatting", true);
      String jsonBody = objectMapper.writeValueAsString(requestBody);

      byte[] respBytes = restClient.post()
          .uri("/v2/translate")
          .header("Authorization", "DeepL-Auth-Key " + apiKey)
          .contentType(MediaType.APPLICATION_JSON)
          .header("Accept", "application/json")
          .body(jsonBody)
          .retrieve()
          .body(byte[].class);

      String resp = new String(respBytes, java.nio.charset.StandardCharsets.UTF_8);

      JsonNode root = objectMapper.readTree(resp);
      JsonNode arr = root.path("translations");
      if (!arr.isArray()) throw new IllegalStateException("DeepL response missing translations");
      List<String> out = new ArrayList<>();
      for (JsonNode item : arr) {
        out.add(item.path("text").asText(""));
      }
      if (out.size() != batch.size()) {
        throw new IllegalStateException("DeepL response size mismatch");
      }
      return out;
    } catch (Exception e) {
      throw new IllegalStateException("deepl translate failed: " + e.getMessage(), e);
    }
  }

  private static String normalizeLang(String lang) {
    return lang == null ? "" : lang.trim().toUpperCase();
  }
}
