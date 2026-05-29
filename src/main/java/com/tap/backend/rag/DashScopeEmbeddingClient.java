package com.tap.backend.rag;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class DashScopeEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeEmbeddingClient.class);
    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final RagProperties props;
    private final OkHttpClient httpClient;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    public DashScopeEmbeddingClient(RagProperties props,
                                    ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.props = props;
        this.embeddingModelProvider = embeddingModelProvider;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    public List<Float> embedQuery(String text) {
        List<List<Float>> vectors = embedTexts(List.of(text));
        return vectors.isEmpty() ? List.of() : vectors.get(0);
    }

    public List<List<Float>> embedTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }
        if (shouldUseLangChain4jEmbedding()) {
            return embedTextsWithLangChain4j(texts);
        }
        if (props.dashscope() == null
                || props.dashscope().apiKey() == null
                || props.dashscope().apiKey().isBlank()) {
            throw new IllegalStateException("DASHSCOPE_API_KEY is empty");
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", props.dashscope().embeddingModel());
        body.addProperty("dimensions", props.dashscope().embeddingDimensions());

        JsonArray input = new JsonArray();
        for (String text : texts) {
            input.add(text == null ? "" : text);
        }
        body.add("input", input);

        RequestBody reqBody = RequestBody.create(body.toString(), JSON_MEDIA_TYPE);
        Request request = new Request.Builder()
                .url(resolveEndpoint())
                .addHeader("Authorization", "Bearer " + props.dashscope().apiKey())
                .addHeader("Content-Type", "application/json")
                .post(reqBody)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = response.body() != null ? response.body().string() : "unknown";
                log.error("[DashScope Embedding] API error: {} {}", response.code(), errBody);
                throw new RuntimeException("DashScope embedding API failed: " + response.code());
            }

            String respStr = response.body() == null ? "" : response.body().string();
            JsonObject respJson = JsonParser.parseString(respStr).getAsJsonObject();
            JsonArray data = respJson.getAsJsonArray("data");

            List<List<Float>> result = new ArrayList<>(data.size());
            for (int row = 0; row < data.size(); row++) {
                JsonArray embedding = data.get(row).getAsJsonObject().getAsJsonArray("embedding");
                List<Float> vector = new ArrayList<>(embedding.size());
                for (int i = 0; i < embedding.size(); i++) {
                    vector.add(embedding.get(i).getAsFloat());
                }
                result.add(vector);
            }
            return result;
        } catch (IOException e) {
            log.error("[DashScope Embedding] request failed", e);
            throw new RuntimeException("DashScope embedding request failed", e);
        }
    }

    private boolean shouldUseLangChain4jEmbedding() {
        return props.langchain4j() != null
                && props.langchain4j().useEmbeddingModel();
    }

    private List<List<Float>> embedTextsWithLangChain4j(List<String> texts) {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new IllegalStateException("LangChain4j embedding model is not configured");
        }

        List<TextSegment> segments = texts.stream()
                .map(text -> TextSegment.from(text == null ? "" : text))
                .toList();
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        List<List<Float>> result = new ArrayList<>(embeddings.size());
        for (Embedding embedding : embeddings) {
            result.add(embedding.vectorAsList());
        }
        return result;
    }

    private String resolveEndpoint() {
        String baseUrl = props.dashscope() == null ? null : props.dashscope().baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized.endsWith("/embeddings") ? normalized : normalized + "/embeddings";
    }
}
