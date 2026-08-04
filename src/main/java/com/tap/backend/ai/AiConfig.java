package com.tap.backend.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AiConfig {

    @Bean
    public AiProvider aiProvider(AiProperties props, ObjectMapper objectMapper) {
        String provider = props.provider() == null ? "mock" : props.provider().trim().toLowerCase();
        if ("openai".equals(provider)) {
            AiProperties.OpenAi oa = props.openai();
            String apiKey = oa == null ? null : oa.apiKey();
            if (apiKey == null || apiKey.isBlank()) apiKey = System.getenv("OPENAI_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("tap.ai.provider=openai but OPENAI_API_KEY is empty");
            }
            String baseUrl = oa == null ? null : oa.baseUrl();
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://api.openai.com/v1";
            String model = oa == null ? null : oa.model();
            if (model == null || model.isBlank()) model = "gpt-4o-mini";

            RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey.trim())
                .requestFactory(pooledRequestFactory())
                .build();
            return new OpenAiProvider(restClient, objectMapper, model);
        }
        if ("dashscope".equals(provider) || "qwen".equals(provider)) {
            AiProperties.Dashscope ds = props.dashscope();
            String apiKey = ds == null ? null : ds.apiKey();
            if (apiKey == null || apiKey.isBlank()) apiKey = System.getenv("DASHSCOPE_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("tap.ai.provider=dashscope but DASHSCOPE_API_KEY is empty");
            }
            String baseUrl = ds == null ? null : ds.baseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
            }
            String model = ds == null ? null : ds.model();
            if (model == null || model.isBlank()) model = "qwen-vl-max-latest";

            RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey.trim())
                .requestFactory(pooledRequestFactory())
                .build();
            return new OpenAiProvider(restClient, objectMapper, model);
        }
        if ("mock".equals(provider)) {
            return new MockAiProvider();
        }
        throw new IllegalStateException("Unsupported tap.ai.provider: " + provider);
    }

    /**
     * 连接池化的 HTTP 请求工厂 — 复用 TCP 连接，减少握手开销
     */
    private HttpComponentsClientHttpRequestFactory pooledRequestFactory() {
        ConnectionConfig connConfig = ConnectionConfig.custom()
            .setConnectTimeout(Timeout.of(15, TimeUnit.SECONDS))
            .setSocketTimeout(Timeout.of(120, TimeUnit.SECONDS))
            .build();

        PoolingHttpClientConnectionManager connManager = PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnTotal(30)
            .setMaxConnPerRoute(20)
            .setDefaultConnectionConfig(connConfig)
            .build();

        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.of(5, TimeUnit.SECONDS))
            .build();

        CloseableHttpClient httpClient = HttpClients.custom()
            .setConnectionManager(connManager)
            .setDefaultRequestConfig(requestConfig)
            .evictIdleConnections(org.apache.hc.core5.util.TimeValue.of(30, TimeUnit.SECONDS))
            .build();

        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }
}
