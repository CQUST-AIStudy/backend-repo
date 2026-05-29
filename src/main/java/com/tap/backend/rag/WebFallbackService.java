package com.tap.backend.rag;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class WebFallbackService {

    private static final Logger log = LoggerFactory.getLogger(WebFallbackService.class);
    private static final String TAVILY_URL = "https://api.tavily.com/search";

    private final RagProperties ragProps;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    public WebFallbackService(RagProperties ragProps) {
        this.ragProps = ragProps;
    }

    public record WebResult(String title, String url, String snippet,
                             double relevanceScore, String source) {}

    public List<WebResult> search(String query, String intentType, int maxResults) {
        if (!ragProps.web().enabled() || ragProps.web().tavilyApiKey() == null
                || ragProps.web().tavilyApiKey().isBlank()) {
            log.debug("[WebFallback] disabled or no API key");
            return Collections.emptyList();
        }

        try {
            String searchQuery = query;
            if ("debug".equals(intentType)) {
                searchQuery = query + " site:stackoverflow.com";
            }

            JsonObject body = new JsonObject();
            body.addProperty("api_key", ragProps.web().tavilyApiKey());
            body.addProperty("query", searchQuery);
            body.addProperty("max_results", Math.max(maxResults, 8));
            body.addProperty("search_depth", "basic");

            RequestBody reqBody = RequestBody.create(
                    body.toString(), MediaType.parse("application/json"));
            Request req = new Request.Builder().url(TAVILY_URL).post(reqBody).build();

            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    log.warn("[WebFallback] Tavily API error: {}", resp.code());
                    return Collections.emptyList();
                }
                String respStr = resp.body().string();
                JsonObject json = JsonParser.parseString(respStr).getAsJsonObject();
                JsonArray results = json.getAsJsonArray("results");
                if (results == null) return Collections.emptyList();

                List<WebResult> webResults = new ArrayList<>();
                for (int i = 0; i < results.size(); i++) {
                    JsonObject r = results.get(i).getAsJsonObject();
                    webResults.add(new WebResult(
                            r.has("title") ? r.get("title").getAsString() : "",
                            r.has("url") ? r.get("url").getAsString() : "",
                            r.has("content") ? r.get("content").getAsString() : "",
                            r.has("score") ? r.get("score").getAsDouble() : 0.0,
                            "web"
                    ));
                }
                return denoise(webResults, query, maxResults);
            }
        } catch (Exception e) {
            log.error("[WebFallback] search failed", e);
            return Collections.emptyList();
        }
    }

    public List<WebResult> denoise(List<WebResult> raw, String query, int keep) {
        if (raw.size() <= keep) return raw;
        // Sort by relevance score descending, keep top 3-5
        raw.sort(Comparator.comparingDouble(WebResult::relevanceScore).reversed());
        int limit = Math.max(3, Math.min(keep, 5));
        return raw.subList(0, Math.min(limit, raw.size()));
    }
}
