package com.tap.backend.rag;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public class CrossEncoderRerankClient {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderRerankClient.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final RagProperties ragProps;

    public CrossEncoderRerankClient(RagProperties ragProps) {
        this.ragProps = ragProps;
    }

    public record Candidate(long id, String text) {}

    public Map<Long, Double> rerank(String query, List<Candidate> candidates, int topN) {
        Map<Long, Double> result = new HashMap<>();
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) return result;
        RagProperties.Rerank cfg = ragProps.rerank();
        if (cfg == null || !"cross_encoder_http".equalsIgnoreCase(cfg.provider())) return result;
        if (cfg.endpoint() == null || cfg.endpoint().isBlank()) return result;

        try {
            JsonObject req = new JsonObject();
            req.addProperty("query", query);
            req.addProperty("top_n", Math.max(1, topN));
            JsonArray docs = new JsonArray();
            for (Candidate c : candidates) {
                JsonObject d = new JsonObject();
                d.addProperty("id", String.valueOf(c.id()));
                String txt = c.text() == null ? "" : c.text();
                if (txt.length() > 2000) {
                    txt = txt.substring(0, 2000);
                }
                d.addProperty("text", txt);
                docs.add(d);
            }
            req.add("documents", docs);

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(cfg.timeoutMs(), TimeUnit.MILLISECONDS)
                    .readTimeout(cfg.timeoutMs(), TimeUnit.MILLISECONDS)
                    .writeTimeout(cfg.timeoutMs(), TimeUnit.MILLISECONDS)
                    .build();

            Request request = new Request.Builder()
                    .url(cfg.endpoint())
                    .post(RequestBody.create(req.toString(), JSON))
                    .build();

            try (Response resp = client.newCall(request).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.warn("[RAG] cross-encoder rerank failed: http {}", resp.code());
                    return result;
                }
                String body = resp.body().string();
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();

                // Supported response formats:
                // 1) { "results": [ {"id":"123", "score":0.9}, ... ] }
                // 2) { "scores": [ {"id":"123", "score":0.9}, ... ] }
                // 3) { "scores": [0.9, 0.8, ...] } (same order as input docs)
                JsonArray arr = null;
                if (json.has("results") && json.get("results").isJsonArray()) {
                    arr = json.getAsJsonArray("results");
                } else if (json.has("scores") && json.get("scores").isJsonArray()) {
                    arr = json.getAsJsonArray("scores");
                }
                if (arr == null) return result;

                if (!arr.isEmpty() && arr.get(0).isJsonObject()) {
                    for (JsonElement e : arr) {
                        JsonObject o = e.getAsJsonObject();
                        if (!o.has("id") || !o.has("score")) continue;
                        long id = Long.parseLong(o.get("id").getAsString());
                        double score = o.get("score").getAsDouble();
                        result.put(id, score);
                    }
                } else {
                    for (int i = 0; i < arr.size() && i < candidates.size(); i++) {
                        double score = arr.get(i).getAsDouble();
                        result.put(candidates.get(i).id(), score);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[RAG] cross-encoder rerank unavailable: {}", e.getMessage());
        }
        return result;
    }
}

