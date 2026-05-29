package com.tap.backend.rag.lc4j.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tap.backend.ai.AiProperties;
import com.tap.backend.domain.rag.CourseSpaceEntity;
import com.tap.backend.rag.CoverageCalculator;
import com.tap.backend.rag.IntentClassifyService;
import com.tap.backend.rag.ModeDecisionService;
import com.tap.backend.rag.RagProperties;
import com.tap.backend.rag.WebFallbackService;
import com.tap.backend.rag.lc4j.dto.TeacherRagCitation;
import com.tap.backend.rag.lc4j.dto.TeacherRagExecutionContext;
import com.tap.backend.rag.lc4j.dto.TeacherRagExecutionResult;
import com.tap.backend.rag.lc4j.prompt.TeacherRagPromptService;
import com.tap.backend.rag.lc4j.retriever.TeacherHybridRetriever;
import com.tap.backend.service.CourseSpaceService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TeacherRagFacade {

  private static final Logger log = LoggerFactory.getLogger(TeacherRagFacade.class);
  private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
  private static final String ACADEMIC_INTEGRITY_MESSAGE =
      "检测到你的问题可能涉及代写或直接给答案的请求。系统只支持学习辅导，请换一种提问方式。";
  private static final String LLM_ERROR_MESSAGE = "抱歉，AI 生成回答时出现错误，请稍后重试。";

  private final CoverageCalculator coverageCalculator;
  private final ModeDecisionService modeDecisionService;
  private final IntentClassifyService intentClassifyService;
  private final WebFallbackService webFallbackService;
  private final TeacherHybridRetriever teacherHybridRetriever;
  private final RagProperties ragProperties;
  private final AiProperties aiProperties;
  private final TeacherRagAnswerService teacherRagAnswerService;
  private final TeacherRagPromptService teacherRagPromptService;
  private final TeacherRagCitationService teacherRagCitationService;

  private final OkHttpClient httpClient =
      new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build();

  public TeacherRagFacade(
      CoverageCalculator coverageCalculator,
      ModeDecisionService modeDecisionService,
      IntentClassifyService intentClassifyService,
      WebFallbackService webFallbackService,
      TeacherHybridRetriever teacherHybridRetriever,
      RagProperties ragProperties,
      AiProperties aiProperties,
      TeacherRagAnswerService teacherRagAnswerService,
      TeacherRagPromptService teacherRagPromptService,
      TeacherRagCitationService teacherRagCitationService) {
    this.coverageCalculator = coverageCalculator;
    this.modeDecisionService = modeDecisionService;
    this.intentClassifyService = intentClassifyService;
    this.webFallbackService = webFallbackService;
    this.teacherHybridRetriever = teacherHybridRetriever;
    this.ragProperties = ragProperties;
    this.aiProperties = aiProperties;
    this.teacherRagAnswerService = teacherRagAnswerService;
    this.teacherRagPromptService = teacherRagPromptService;
    this.teacherRagCitationService = teacherRagCitationService;
  }

  public TeacherRagExecutionResult execute(
      long courseSpaceId,
      String query,
      String requestedMode,
      CourseSpaceEntity courseSpace,
      CourseSpaceService.RagChatScope chatScope,
      OutputStream outputStream) {
    IntentClassifyService.IntentResult intent = classifyIntent(query);
    if (intent.academicIntegrityViolation()) {
      writeToStream(outputStream, ACADEMIC_INTEGRITY_MESSAGE);
      teacherRagCitationService.writeTrailer(outputStream, Collections.emptyList());
      return new TeacherRagExecutionResult(
          ACADEMIC_INTEGRITY_MESSAGE,
          Collections.emptyList(),
          Collections.emptyList(),
          0.0,
          0.0,
          intent.intentType(),
          "strict",
          false);
    }

    TeacherHybridRetriever.RetrievalResult retrieval = teacherHybridRetriever.retrieve(courseSpaceId, query);
    double querySupportRatio =
        retrieval.evidenceTexts().isEmpty()
            ? 0.0
            : Math.min(
                1.0,
                retrieval.evidenceTexts().stream().mapToInt(String::length).sum()
                    / (double) (query.length() * 10));
    double coverageScore =
        coverageCalculator.calculate(
            retrieval.top1Score(),
            retrieval.evidenceTexts().size(),
            retrieval.hitFaq(),
            retrieval.hitAnnotation(),
            querySupportRatio);

    double coverageThreshold =
        ragProperties.coverage() != null ? ragProperties.coverage().threshold() : 0.4;
    ModeDecisionService.ModeDecision modeDecision =
        modeDecisionService.decide(
            requestedMode,
            courseSpace.getDefaultMode(),
            Boolean.TRUE.equals(courseSpace.getAllowWebSearch()),
            coverageScore,
            coverageThreshold);

    boolean usedWeb =
        maybeAppendWebFallback(query, intent.intentType(), modeDecision, retrieval.evidenceTexts(), retrieval.citations());
    List<TeacherRagCitation> citations = teacherRagCitationService.normalize(retrieval.citations());

    TeacherRagExecutionContext prePromptContext =
        new TeacherRagExecutionContext(
            courseSpaceId,
            query,
            requestedMode,
            courseSpace,
            chatScope,
            intent,
            retrieval,
            coverageScore,
            modeDecision,
            usedWeb,
            null,
            null);

    String contextBlock = teacherRagPromptService.buildContextBlock(retrieval.evidenceTexts(), citations);
    String systemPrompt = teacherRagPromptService.buildSystemPrompt(prePromptContext);

    TeacherRagExecutionContext executionContext =
        new TeacherRagExecutionContext(
            courseSpaceId,
            query,
            requestedMode,
            courseSpace,
            chatScope,
            intent,
            retrieval,
            coverageScore,
            modeDecision,
            usedWeb,
            contextBlock,
            systemPrompt);

    StringBuilder fullAnswer = new StringBuilder();
    try {
      streamAnswer(executionContext, outputStream, fullAnswer);
    } catch (Exception e) {
      log.error("[RAG] LLM streaming failed: {}", e.getMessage(), e);
      writeToStream(outputStream, LLM_ERROR_MESSAGE);
      fullAnswer.append(LLM_ERROR_MESSAGE);
    }

    teacherRagCitationService.writeTrailer(outputStream, citations);

    return new TeacherRagExecutionResult(
        fullAnswer.toString(),
        citations,
        retrieval.retrievedChunkIds(),
        retrieval.top1Score(),
        coverageScore,
        intent.intentType(),
        modeDecision.effectiveMode(),
        usedWeb);
  }

  private IntentClassifyService.IntentResult classifyIntent(String query) {
    try {
      return intentClassifyService.classify(query);
    } catch (Exception e) {
      log.warn("[RAG] intent classify failed, defaulting to concept: {}", e.getMessage());
      return new IntentClassifyService.IntentResult("concept", false);
    }
  }

  private boolean maybeAppendWebFallback(
      String query,
      String intentType,
      ModeDecisionService.ModeDecision modeDecision,
      List<String> evidenceTexts,
      List<TeacherRagCitation> citations) {
    if (!modeDecision.shouldFallbackToWeb()) {
      return false;
    }
    try {
      List<WebFallbackService.WebResult> webResults = webFallbackService.search(query, intentType, 5);
      if (webResults.isEmpty()) {
        return false;
      }
      for (WebFallbackService.WebResult result : webResults) {
        evidenceTexts.add("[Web] " + result.title() + ": " + result.snippet());
        citations.add(
            new TeacherRagCitation(
                citations.size() + 1,
                result.title(),
                "",
                "",
                result.relevanceScore(),
                "web:" + result.url()));
      }
      return true;
    } catch (Exception e) {
      log.warn("[RAG] web fallback failed: {}", e.getMessage());
      return false;
    }
  }

  private void streamAnswer(
      TeacherRagExecutionContext context, OutputStream outputStream, StringBuilder fullAnswer)
      throws IOException {
    String provider =
        aiProperties.provider() == null
            ? "mock"
            : aiProperties.provider().trim().toLowerCase(Locale.ROOT);
    if ("mock".equals(provider)) {
      String mockAnswer = "根据当前检索到的课程资料，先给出可用上下文：\n\n" + context.contextBlock();
      writeToStream(outputStream, mockAnswer);
      fullAnswer.append(mockAnswer);
      return;
    }
    if (teacherRagAnswerService.isEnabled()) {
      fullAnswer.append(
          teacherRagAnswerService.streamAnswer(
              context.systemPrompt(), context.contextBlock(), context.query(), outputStream));
      return;
    }

    LlmConfig config = resolveLlmConfig(provider);
    if (config == null) {
      String fallback = "AI 服务未配置可用的模型或 API Key，暂时无法生成回答。";
      writeToStream(outputStream, fallback);
      fullAnswer.append(fallback);
      return;
    }

    JsonObject reqBody = new JsonObject();
    reqBody.addProperty("model", config.model());
    reqBody.addProperty("temperature", 0.5);
    reqBody.addProperty("stream", true);

    JsonArray messages = new JsonArray();
    JsonObject sysMsg = new JsonObject();
    sysMsg.addProperty("role", "system");
    sysMsg.addProperty("content", context.systemPrompt());
    messages.add(sysMsg);

    JsonObject userMsg = new JsonObject();
    userMsg.addProperty("role", "user");
    userMsg.addProperty(
        "content", "以下是检索到的课程资料：\n\n" + context.contextBlock() + "\n\n学生问题：\n" + context.query());
    messages.add(userMsg);
    reqBody.add("messages", messages);

    RequestBody okBody = RequestBody.create(reqBody.toString(), JSON);
    Request okReq =
        new Request.Builder()
            .url(config.baseUrl() + "/chat/completions")
            .addHeader("Authorization", "Bearer " + config.apiKey())
            .post(okBody)
            .build();

    try (Response resp = httpClient.newCall(okReq).execute()) {
      if (!resp.isSuccessful()) {
        String errBody = resp.body() == null ? "" : resp.body().string();
        String errMsg = "AI 服务返回错误码: " + resp.code();
        log.error(
            "[RAG] LLM API error: {} body={}",
            resp.code(),
            errBody.substring(0, Math.min(500, errBody.length())));
        writeToStream(outputStream, errMsg);
        fullAnswer.append(errMsg);
        return;
      }
      if (resp.body() == null) {
        writeToStream(outputStream, LLM_ERROR_MESSAGE);
        fullAnswer.append(LLM_ERROR_MESSAGE);
        return;
      }

      BufferedReader buffered = new BufferedReader(resp.body().charStream());
      String line;
      while ((line = buffered.readLine()) != null) {
        if (!line.startsWith("data: ")) {
          continue;
        }
        String data = line.substring(6).trim();
        if ("[DONE]".equals(data)) {
          break;
        }
        try {
          JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
          JsonArray choices = chunk.getAsJsonArray("choices");
          if (choices == null || choices.isEmpty()) {
            continue;
          }
          JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
          if (delta == null || !delta.has("content")) {
            continue;
          }
          String content = delta.get("content").getAsString();
          if (content != null && !content.isEmpty()) {
            outputStream.write(content.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            fullAnswer.append(content);
          }
        } catch (Exception e) {
          log.trace("[RAG] skipping unparseable SSE chunk: {}", data);
        }
      }
    }
  }

  private LlmConfig resolveLlmConfig(String provider) {
    if ("dashscope".equals(provider) || "qwen".equals(provider)) {
      AiProperties.Dashscope ds = aiProperties.dashscope();
      if (ds == null || ds.apiKey() == null || ds.apiKey().isBlank()) {
        return null;
      }
      String baseUrl =
          ds.baseUrl() == null || ds.baseUrl().isBlank()
              ? "https://dashscope.aliyuncs.com/compatible-mode/v1"
              : ds.baseUrl();
      String model = ds.model() == null || ds.model().isBlank() ? "qwen-plus" : ds.model();
      return new LlmConfig(trimTrailingSlash(baseUrl), ds.apiKey(), model);
    }

    AiProperties.OpenAi oa = aiProperties.openai();
    if (oa == null || oa.apiKey() == null || oa.apiKey().isBlank()) {
      return null;
    }
    String baseUrl =
        oa.baseUrl() == null || oa.baseUrl().isBlank()
            ? "https://api.deepseek.com/v1"
            : oa.baseUrl();
    String model = oa.model() == null || oa.model().isBlank() ? "deepseek-chat" : oa.model();
    return new LlmConfig(trimTrailingSlash(baseUrl), oa.apiKey(), model);
  }

  private String trimTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private void writeToStream(OutputStream outputStream, String text) {
    try {
      outputStream.write(text.getBytes(StandardCharsets.UTF_8));
      outputStream.flush();
    } catch (IOException e) {
      log.warn("[RAG] failed to write to output stream: {}", e.getMessage());
    }
  }

  private record LlmConfig(String baseUrl, String apiKey, String model) {}
}
