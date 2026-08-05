package com.tap.backend.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

public class OpenAiProvider implements AiProvider {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OpenAiProvider.class);
  private final RestClient restClient;
  private final ObjectMapper om;
  private final String model;

  public OpenAiProvider(RestClient restClient, ObjectMapper om, String model) {
    this.restClient = restClient;
    this.om = om;
    this.model = model;
  }

  @Override public String name() { return "openai"; }
  @Override public String model() { return model == null ? "" : model; }

  @Override
  public FileClassifyResult classifyFile(FileClassifyInput input) {
    String prompt = "你是教师教辅平台的文档整理助手。\n"
        + "请根据给定文档文本输出严格 JSON（不要 markdown 代码块），格式：\n"
        + "{\"docKind\":\"paper|teaching|data|code|admin|other\","
        + "\"topic\":\"方向/课程/项目\","
        + "\"subjectTags\":[\"...\"],\"keywords\":[\"...\"],"
        + "\"summaryZh\":\"150-250字中文摘要\","
        + "\"year\":\"如能推断则填年份字符串，否则null\","
        + "\"confidence\":0.0到1.0的置信度,"
        + "\"reason\":\"引用文本中的证据解释分类依据\"}\n\n"
        + "文件路径：" + safe(input.path(), 200) + "\n"
        + "文本：\n" + safe(input.text(), 6000);
    return parseJson(chat(prompt), FileClassifyResult.class);
  }

  @Override
  public FolderOrganizeResult organizeFolder(FolderOrganizeInput input) {
    String prompt = "你是教师教辅平台的文件夹整理助手。\n"
        + "基于以下文档的分类结果，输出可执行的组织策略 JSON（不要 markdown 代码块），格式：\n"
        + "{\"folderTopic\":\"...\",\"folderTags\":[\"...\"],"
        + "\"groupingStrategy\":\"按什么分组，如 topic > year > docKind\","
        + "\"folderSchema\":[\"目录1/子目录\",\"目录2/子目录\",...],"
        + "\"placementRules\":[{\"condition\":\"docKind==paper && topic==NLP\",\"targetFolder\":\"论文/NLP\"},...],"
        + "\"namingRule\":\"{year}_{topic}_{shortTitle}\","
        + "\"reviewThreshold\":0.5}\n\n"
        + "文档列表：\n" + toJson(input.documents());
    return parseJson(chat(prompt), FolderOrganizeResult.class);
  }

  @Override
  public StructuredSummary structuredSummary(StructuredSummaryInput input) {
    String prompt = "你是学术论文精读助手。请严格基于下方论文原文内容，用中文撰写精读卡片，禁止编造或套用通用模板。\n"
        + "总中文字数控制在 " + input.minZhChars() + "-" + input.maxZhChars() + " 字。\n"
        + "只输出 JSON，不要 markdown 代码块，格式：\n"
        + "{\"researchProblemMotivation\":\"...\",\"methods\":[\"...\"],"
        + "\"experimentsData\":[\"...\"],\"conclusions\":\"...\","
        + "\"limitationsInsights\":[\"...\"]}\n\n"
        + "论文原文：\n" + safe(input.text(), 12000);
    return parseJson(chat(prompt), StructuredSummary.class);
  }

  @Override
  public String chat(String userPrompt, String modelOverride) {
    return chatInternal(userPrompt, modelOverride, null, false);
  }

  @Override
  public String chatJson(String userPrompt, String modelOverride, int maxTokens) {
    return chatInternal(userPrompt, modelOverride, Math.max(1, maxTokens), true);
  }

  private String chatInternal(
      String userPrompt,
      String modelOverride,
      Integer maxTokens,
      boolean jsonMode) {
    try {
      ObjectNode body = om.createObjectNode();
      body.put("model", modelOverride == null || modelOverride.isBlank() ? model : modelOverride);
      body.set("messages", om.valueToTree(List.of(
          msg("system", "You are a helpful assistant. Always respond with valid JSON only."),
          msg("user", userPrompt)
      )));
      body.put("temperature", 0.3);
      if (maxTokens != null) body.put("max_tokens", maxTokens);
      if (jsonMode) {
        body.set("response_format", om.createObjectNode().put("type", "json_object"));
      }
      log.info("[AI] model={} prompt_len={}", body.get("model").asText(), userPrompt.length());

      String resp = restClient.post()
          .uri("/chat/completions")
          .contentType(MediaType.APPLICATION_JSON)
          .body(om.writeValueAsString(body))
          .retrieve()
          .body(String.class);

      JsonNode root = om.readTree(resp);
      String finishReason = root.path("choices").path(0).path("finish_reason").asText();
      if ("length".equalsIgnoreCase(finishReason)) {
        log.warn("[AI] model={} reached output token limit; caller will validate whether JSON is complete",
            body.get("model").asText());
      }
      return root.path("choices").path(0).path("message").path("content").asText();
    } catch (Exception e) {
      throw new IllegalStateException(
          "openai call failed: " + e.getClass().getSimpleName() + ": " + safeErrorMessage(e), e);
    }
  }

  private String safeErrorMessage(Exception error) {
    String message = error.getMessage();
    if (message == null || message.isBlank()) return "no detail";
    return message.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._\\-]+", "Bearer [REDACTED]");
  }

  private String chat(String userPrompt) {
    return chat(userPrompt, null);
  }

  private ObjectNode msg(String role, String content) {
    return om.createObjectNode().put("role", role).put("content", content);
  }

  private <T> T parseJson(String content, Class<T> type) {
    try {
      String s = content == null ? "" : content.trim();
      // Strip markdown code fences if present
      if (s.startsWith("```")) {
        int first = s.indexOf('\n');
        int last = s.lastIndexOf("```");
        if (first > 0 && last > first) s = s.substring(first + 1, last).trim();
      }
      int start = s.indexOf('{');
      int end = s.lastIndexOf('}');
      if (start >= 0 && end > start) s = s.substring(start, end + 1);
      return om.readValue(s, type);
    } catch (Exception e) {
      throw new IllegalArgumentException("model output is not valid json: " + content, e);
    }
  }

  private String toJson(Object o) {
    try { return om.writeValueAsString(o); } catch (Exception e) { return "[]"; }
  }

  private static String safe(String s, int max) {
    String t = s == null ? "" : s.trim();
    return t.length() <= max ? t : t.substring(0, max);
  }
}
