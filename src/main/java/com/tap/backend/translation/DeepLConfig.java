package com.tap.backend.translation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class DeepLConfig {
  @Bean
  public DeepLClient deepLClient(TranslationProperties translationProps, ObjectMapper objectMapper) {
    if ("mock".equalsIgnoreCase(translationProps.provider())) {
      return new MockDeepLClient();
    }
    if (!"deepl".equalsIgnoreCase(translationProps.provider())) {
      throw new IllegalStateException("Unsupported translation provider: " + translationProps.provider());
    }
    DeepLProperties props = translationProps.deepl();
    if (props == null) {
      throw new IllegalStateException("DeepL configuration is missing");
    }

    String key = props.apiKey();
    if (key == null || key.isBlank()) {
      key = System.getenv("DEEPL_API_KEY");
    }
    String baseUrl = props.baseUrl();
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = System.getenv().getOrDefault("DEEPL_BASE_URL", "https://api-free.deepl.com");
    }

    if (key == null || key.isBlank()) {
      throw new IllegalStateException("TRANS_PROVIDER=deepl but DEEPL_API_KEY is empty");
    }

    RestClient restClient = RestClient.builder().baseUrl(baseUrl).build();
    int maxBatch = props.maxBatchSize() <= 0 ? 40 : props.maxBatchSize();
    DeepLRateLimiter limiter = new DeepLRateLimiter(props.minIntervalMs());
    return new DeepLHttpClient(restClient, objectMapper, key, maxBatch, limiter);
  }
}
