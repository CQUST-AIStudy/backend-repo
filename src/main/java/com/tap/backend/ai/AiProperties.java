package com.tap.backend.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tap.ai")
public record AiProperties(
    String provider,
    OpenAi openai,
    Dashscope dashscope,
    Arxiv arxiv
) {
  public record OpenAi(
      String baseUrl,
      String apiKey,
      String model
  ) {}

  public record Dashscope(
      String baseUrl,
      String apiKey,
      String model
  ) {}

  public record Arxiv(
      Boolean enabled,
      String searchBaseUrl,
      String apiKey,
      String apiKeyHeader,
      Integer maxResults,
      Integer timeoutSeconds
  ) {}
}
