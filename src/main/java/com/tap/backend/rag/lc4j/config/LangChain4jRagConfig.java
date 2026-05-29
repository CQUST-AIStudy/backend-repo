package com.tap.backend.rag.lc4j.config;

import com.tap.backend.ai.AiProperties;
import com.tap.backend.rag.RagProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LangChain4jRagConfig {

  private static final String DEFAULT_OPENAI_BASE_URL = "https://api.deepseek.com/v1";
  private static final String DEFAULT_DASHSCOPE_BASE_URL =
      "https://dashscope.aliyuncs.com/compatible-mode/v1";

  @Bean
  @ConditionalOnProperty(prefix = "tap.rag.langchain4j", name = "enabled", havingValue = "true")
  public ChatModel teacherRagChatModel(AiProperties aiProperties, RagProperties ragProperties) {
    EndpointConfig endpoint = resolveChatEndpoint(aiProperties, ragProperties);
    return OpenAiChatModel.builder()
        .baseUrl(endpoint.baseUrl())
        .apiKey(endpoint.apiKey())
        .modelName(endpoint.modelName())
        .timeout(Duration.ofSeconds(endpoint.timeoutSeconds()))
        .logRequests(false)
        .logResponses(false)
        .build();
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "tap.rag.langchain4j",
      name = {"enabled", "use-streaming"},
      havingValue = "true")
  public StreamingChatModel teacherRagStreamingChatModel(
      AiProperties aiProperties, RagProperties ragProperties) {
    EndpointConfig endpoint = resolveChatEndpoint(aiProperties, ragProperties);
    return OpenAiStreamingChatModel.builder()
        .baseUrl(endpoint.baseUrl())
        .apiKey(endpoint.apiKey())
        .modelName(endpoint.modelName())
        .timeout(Duration.ofSeconds(endpoint.timeoutSeconds()))
        .logRequests(false)
        .logResponses(false)
        .build();
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "tap.rag.langchain4j",
      name = {"enabled", "use-embedding-model"},
      havingValue = "true")
  public EmbeddingModel teacherRagEmbeddingModel(
      AiProperties aiProperties, RagProperties ragProperties) {
    EndpointConfig endpoint = resolveEmbeddingEndpoint(aiProperties, ragProperties);
    OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder builder =
        OpenAiEmbeddingModel.builder()
            .baseUrl(endpoint.baseUrl())
            .apiKey(endpoint.apiKey())
            .modelName(endpoint.modelName())
            .timeout(Duration.ofSeconds(endpoint.timeoutSeconds()))
            .logRequests(false)
            .logResponses(false);
    if (endpoint.dimensions() != null && endpoint.dimensions() > 0) {
      builder.dimensions(endpoint.dimensions());
    }
    return builder.build();
  }

  private EndpointConfig resolveChatEndpoint(AiProperties aiProperties, RagProperties ragProperties) {
    RagProperties.Chat cfg = ragProperties.langchain4j() == null ? null : ragProperties.langchain4j().chat();
    String provider =
        aiProperties.provider() == null
            ? "mock"
            : aiProperties.provider().trim().toLowerCase(Locale.ROOT);

    if ("dashscope".equals(provider) || "qwen".equals(provider)) {
      AiProperties.Dashscope ds = aiProperties.dashscope();
      return new EndpointConfig(
          firstNonBlank(cfg == null ? null : cfg.baseUrl(), ds == null ? null : ds.baseUrl(), DEFAULT_DASHSCOPE_BASE_URL),
          requireValue(firstNonBlank(cfg == null ? null : cfg.apiKey(), ds == null ? null : ds.apiKey()), "teacher rag chat api key"),
          firstNonBlank(cfg == null ? null : cfg.modelName(), ds == null ? null : ds.model(), "qwen-plus"),
          cfg == null ? 120 : cfg.timeoutSeconds(),
          null);
    }

    AiProperties.OpenAi oa = aiProperties.openai();
    return new EndpointConfig(
        firstNonBlank(cfg == null ? null : cfg.baseUrl(), oa == null ? null : oa.baseUrl(), DEFAULT_OPENAI_BASE_URL),
        requireValue(firstNonBlank(cfg == null ? null : cfg.apiKey(), oa == null ? null : oa.apiKey()), "teacher rag chat api key"),
        firstNonBlank(cfg == null ? null : cfg.modelName(), oa == null ? null : oa.model(), "deepseek-chat"),
        cfg == null ? 120 : cfg.timeoutSeconds(),
        null);
  }

  private EndpointConfig resolveEmbeddingEndpoint(
      AiProperties aiProperties, RagProperties ragProperties) {
    RagProperties.Embedding cfg =
        ragProperties.langchain4j() == null ? null : ragProperties.langchain4j().embedding();
    RagProperties.DashScope dashscope = ragProperties.dashscope();
    String fallbackBaseUrl = dashscope == null ? null : dashscope.baseUrl();
    if (fallbackBaseUrl == null || fallbackBaseUrl.isBlank()) {
      fallbackBaseUrl = DEFAULT_DASHSCOPE_BASE_URL;
    }
    Integer dimensions = cfg != null && cfg.dimensions() > 0
        ? cfg.dimensions()
        : dashscope != null && dashscope.embeddingDimensions() > 0
            ? dashscope.embeddingDimensions()
            : null;
    return new EndpointConfig(
        firstNonBlank(cfg == null ? null : cfg.baseUrl(), fallbackBaseUrl),
        requireValue(
            firstNonBlank(
                cfg == null ? null : cfg.apiKey(),
                dashscope == null ? null : dashscope.apiKey(),
                aiProperties.dashscope() == null ? null : aiProperties.dashscope().apiKey()),
            "teacher rag embedding api key"),
        firstNonBlank(
            cfg == null ? null : cfg.modelName(),
            dashscope == null ? null : dashscope.embeddingModel(),
            "text-embedding-v3"),
        cfg == null ? 30 : cfg.timeoutSeconds(),
        dimensions);
  }

  private String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return trimTrailingSlash(value);
      }
    }
    return null;
  }

  private String trimTrailingSlash(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private String requireValue(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is empty");
    }
    return value;
  }

  private record EndpointConfig(
      String baseUrl,
      String apiKey,
      String modelName,
      int timeoutSeconds,
      Integer dimensions) {}
}
