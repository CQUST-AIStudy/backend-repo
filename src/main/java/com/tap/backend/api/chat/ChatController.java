package com.tap.backend.api.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tap.backend.ai.AiProperties;
import com.tap.backend.quota.QuotaService;
import com.tap.backend.security.PrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.common.api.ApiResponse;
import com.tap.common.api.Maps;
import com.tap.common.api.ProblemResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/tap-chat")
public class ChatController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final String PAPERS_MARKER_PREFIX = "\n\n<!--PAPERS:";
    private static final String PAPERS_MARKER_SUFFIX = "-->";
    private static final int MAX_HISTORY = 10;
    private static final int MAX_PAPERS = 5;
    private static final int MAX_REWRITE_QUERIES = 3;
    private static final int MAX_REWRITE_HISTORY = 4;
    private static final int MAX_QUERY_LENGTH = 120;
    private static final int MAX_MESSAGE_LENGTH = 4000;
    private static final Duration ARXIV_REQUEST_TIMEOUT = Duration.ofSeconds(6);
    private static final Duration AI_CHAT_REQUEST_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration AI_STREAM_REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final String ANSWER_SYSTEM_PROMPT = """
            You are the teaching copilot for university instructors.

            ## Role
            - Support teaching Q&A, lesson planning, experiment design, and feedback drafting.
            - When paper search results are provided, treat them as the primary evidence.
            - Help the user produce concise, practical teaching or research outputs.

            ## Response Rules
            - Use Markdown.
            - Reply in the same language as the user's latest message unless the user asks otherwise.
            - Keep the answer directly usable for teaching or research planning.
            - If arXiv search results are provided, only cite paper titles and URLs that appear in those results.
            - If no paper results are provided, do not fabricate citations, links, or publication details.
            - When the user's request is underspecified, make the minimum reasonable assumption and state it briefly.
            """;
    private static final String ARXIV_REWRITE_SYSTEM_PROMPT = """
            You rewrite user requests into high-quality arXiv search queries.
            Return strict JSON only. No markdown. No explanation outside JSON.

            Output schema:
            {
              "shouldSearch": true,
              "queries": ["query 1", "query 2"],
              "reason": "short reason"
            }

            Rules:
            - Generate 1 to 3 concise English search queries for arXiv.
            - Preserve the core technical topic, task, method, domain, and time constraint.
            - Remove filler such as teaching phrasing, polite wording, and answer-format requests.
            - Prefer technical nouns, model names, tasks, benchmark terms, and domain keywords.
            - If the request does not truly need paper retrieval, return shouldSearch=false and queries=[].
            - Do not include quotation marks inside queries unless required by a literal phrase.
            """;
    private static final Set<String> RANKING_STOPWORDS = Set.of(
            "paper", "papers", "arxiv", "search", "latest", "recent", "about", "with",
            "from", "into", "that", "this", "have", "has", "using", "used", "use",
            "give", "find", "look", "related", "research", "study", "studies"
    );

    private final String aiBaseUrl;
    private final String aiApiKey;
    private final String provider;
    private final ObjectMapper objectMapper;
    private final String model;
    private final boolean arxivEnabled;
    private final String arxivSearchBaseUrl;
    private final String arxivApiKey;
    private final String arxivApiKeyHeader;
    private final int arxivMaxResults;
    private final Duration arxivRequestTimeout;
    private final QuotaService quotaService;
    private final PrincipalResolver principalResolver;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public ChatController(AiProperties props,
                          ObjectMapper objectMapper,
                          QuotaService quotaService,
                          PrincipalResolver principalResolver) {
        this.objectMapper = objectMapper;
        this.quotaService = quotaService;
        this.principalResolver = principalResolver;
        this.provider = props.provider() == null ? "mock" : props.provider().trim().toLowerCase();

        AiProperties.OpenAi openAi = props.openai();
        String apiKey = openAi == null ? null : openAi.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("OPENAI_API_KEY");
        }
        this.aiApiKey = apiKey == null ? "" : apiKey.trim();

        String baseUrl = openAi == null ? null : openAi.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
        }
        this.aiBaseUrl = baseUrl;
        this.model = openAi == null || openAi.model() == null ? "deepseek-chat" : openAi.model();

        // Temporary shared paper-search configuration for all teacher users.
        // Upgrade path: replace this static config with admin-managed settings.
        AiProperties.Arxiv arxiv = props.arxiv();
        this.arxivEnabled = arxiv == null || arxiv.enabled() == null ? true : arxiv.enabled();
        this.arxivSearchBaseUrl = normalizeArxivBaseUrl(
                arxiv == null || arxiv.searchBaseUrl() == null || arxiv.searchBaseUrl().isBlank()
                        ? "https://export.arxiv.org/api/query"
                        : arxiv.searchBaseUrl());
        this.arxivApiKey = arxiv == null || arxiv.apiKey() == null ? "" : arxiv.apiKey().trim();
        this.arxivApiKeyHeader =
                arxiv == null || arxiv.apiKeyHeader() == null ? "" : arxiv.apiKeyHeader().trim();
        this.arxivMaxResults = arxiv == null || arxiv.maxResults() == null || arxiv.maxResults() <= 0
                ? MAX_PAPERS
                : Math.min(arxiv.maxResults(), 10);
        this.arxivRequestTimeout =
                Duration.ofSeconds(arxiv == null || arxiv.timeoutSeconds() == null || arxiv.timeoutSeconds() <= 0
                        ? ARXIV_REQUEST_TIMEOUT.toSeconds()
                        : arxiv.timeoutSeconds());
    }

    public record ChatRequest(
            @NotBlank(message = "消息不能为空")
            @Size(max = MAX_MESSAGE_LENGTH, message = "消息不能超过 4000 个字符")
            String message,
            List<MessageItem> history
    ) {}

    public record MessageItem(String role, String content) {}

    private record ChatContext(List<ObjectNode> messages, List<Map<String, String>> papers) {}
    private record SearchPlan(boolean useArxiv, List<String> queries, String reason) {}

    @PostMapping
    public ApiResponse<Map<String, Object>> chat(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChatRequest req
    ) {
        var resolved = principalResolver.resolve(principal);
        quotaService.consumeAiRequests(resolved.userId(), 1);

        ChatContext context = buildChatContext(req, resolved.userId());
        String reply = callAi(context.messages());
        return ApiResponse.of(Maps.of(
                "reply", reply,
                "papers", context.papers(),
                "model", model
        ));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chatStream(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChatRequest req
    ) {
        var resolved = principalResolver.resolve(principal);
        quotaService.consumeAiRequests(resolved.userId(), 1);

        ChatContext context = buildChatContext(req, resolved.userId());
        StreamingResponseBody body = outputStream -> {
            streamAi(context.messages(), outputStream);
            writePapersMarker(outputStream, context.papers());
        };

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemResponse handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError("message");
        String message = fieldError != null && fieldError.getDefaultMessage() != null
                ? fieldError.getDefaultMessage()
                : "请求参数不合法，请检查消息内容后重试";
        return ProblemResponse.of("BAD_REQUEST", message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemResponse handleUnreadableBody(HttpMessageNotReadableException e) {
        return ProblemResponse.of("BAD_REQUEST", "请求体格式错误，请检查消息内容后重试");
    }

    private ChatContext buildChatContext(ChatRequest req, long userId) {
        String message = normalizeUserMessage(req.message());
        List<MessageItem> history = sanitizeHistory(req.history());
        SearchPlan searchPlan = buildSearchPlan(message, history, userId);
        List<Map<String, String>> papers = searchPlan.useArxiv()
                ? searchArxiv(searchPlan.queries(), message)
                : List.of();
        String systemPrompt = buildSystemPrompt(buildArxivContext(papers));

        List<ObjectNode> messages = new ArrayList<>();
        messages.add(msg("system", systemPrompt));
        for (MessageItem item : history) {
            messages.add(msg(item.role(), item.content()));
        }
        messages.add(msg("user", message));
        return new ChatContext(messages, papers);
    }

    private String buildSystemPrompt(String arxivContext) {
        return ANSWER_SYSTEM_PROMPT + arxivContext;
    }

    private String buildArxivContext(List<Map<String, String>> papers) {
        if (papers == null || papers.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("\n\n## arXiv 检索结果\n");
        for (int i = 0; i < papers.size(); i++) {
            Map<String, String> paper = papers.get(i);
            sb.append(i + 1)
                    .append(". **").append(paper.getOrDefault("title", "")).append("**\n")
                    .append("   作者: ").append(paper.getOrDefault("authors", "")).append("\n")
                    .append("   链接: ").append(paper.getOrDefault("link", "")).append("\n")
                    .append("   摘要: ").append(truncate(paper.getOrDefault("summary", ""), 200)).append("\n\n");
        }
        return sb.toString();
    }

    private SearchPlan buildSearchPlan(String message, List<MessageItem> history, long userId) {
        if (!looksLikeSearch(message)) {
            return new SearchPlan(false, List.of(), "local-direct");
        }

        SearchPlan rewritten = rewriteSearchPlan(message, history, userId);
        if (rewritten != null) {
            return rewritten;
        }
        return heuristicSearchPlan(message);
    }

    private SearchPlan rewriteSearchPlan(String message, List<MessageItem> history, long userId) {
        if ("mock".equals(provider) || aiApiKey.isBlank()) {
            return heuristicSearchPlan(message);
        }

        try {
            quotaService.consumeAiRequests(userId, 1);
            List<ObjectNode> rewriteMessages = List.of(
                    msg("system", ARXIV_REWRITE_SYSTEM_PROMPT),
                    msg("user", buildRewriteUserPrompt(message, history))
            );
            String raw = callAi(rewriteMessages);
            SearchPlan parsed = parseSearchPlan(raw);
            if (parsed != null) {
                return parsed;
            }
            log.warn("arXiv rewrite parse failed, fallback to heuristic. raw={}", truncate(raw, 400));
        } catch (Exception e) {
            log.warn("arXiv rewrite failed: {}", e.getMessage());
        }
        return null;
    }

    private String buildRewriteUserPrompt(String message, List<MessageItem> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("Latest user request:\n").append(message == null ? "" : message.trim()).append("\n\n");
        sb.append("Recent conversation:\n");
        if (history == null || history.isEmpty()) {
            sb.append("(none)\n");
        } else {
            int start = Math.max(0, history.size() - MAX_REWRITE_HISTORY);
            for (int i = start; i < history.size(); i++) {
                MessageItem item = history.get(i);
                if (item == null || item.role() == null || item.content() == null) {
                    continue;
                }
                sb.append("- ")
                        .append(item.role())
                        .append(": ")
                        .append(truncate(item.content().replaceAll("\\s+", " ").trim(), 240))
                        .append("\n");
            }
        }
        sb.append("\nDecide whether this request really needs arXiv retrieval, then rewrite the search query if needed.");
        return sb.toString();
    }

    private SearchPlan parseSearchPlan(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            String json = extractJsonObject(raw);
            if (json == null || json.isBlank()) {
                return null;
            }
            JsonNode root = objectMapper.readTree(json);
            List<String> queries = new ArrayList<>();
            JsonNode queriesNode = root.path("queries");
            if (queriesNode.isArray()) {
                for (JsonNode item : queriesNode) {
                    String normalized = normalizeQuery(item.asText(""));
                    if (!normalized.isBlank() && !queries.contains(normalized)) {
                        queries.add(normalized);
                    }
                    if (queries.size() >= MAX_REWRITE_QUERIES) {
                        break;
                    }
                }
            }
            String singleQuery = normalizeQuery(root.path("query").asText(""));
            if (!singleQuery.isBlank() && !queries.contains(singleQuery) && queries.size() < MAX_REWRITE_QUERIES) {
                queries.add(singleQuery);
            }

            boolean shouldSearch = root.path("shouldSearch").asBoolean(!queries.isEmpty());
            String reason = root.path("reason").asText("");
            if (!shouldSearch) {
                return new SearchPlan(false, List.of(), reason);
            }
            if (queries.isEmpty()) {
                return null;
            }
            return new SearchPlan(true, queries, reason);
        } catch (Exception e) {
            return null;
        }
    }

    private SearchPlan heuristicSearchPlan(String message) {
        List<String> queries = new ArrayList<>();
        String englishOnly = normalizeQuery(message == null ? "" : message
                .replaceAll("(?i)arxiv|arivx|paper|papers|latest|recent|search", " "));
        if (!englishOnly.isBlank()) {
            queries.add(englishOnly);
        }

        String fallback = normalizeQuery(message == null ? "" : message);
        if (!fallback.isBlank() && !queries.contains(fallback)) {
            queries.add(fallback);
        }

        if (queries.isEmpty()) {
            return new SearchPlan(false, List.of(), "heuristic-empty");
        }
        return new SearchPlan(true, queries.subList(0, Math.min(queries.size(), MAX_REWRITE_QUERIES)), "heuristic");
    }

    private List<Map<String, String>> searchArxiv(List<String> queries, String originalMessage) {
        if (queries == null || queries.isEmpty()) {
            return List.of();
        }

        List<Map<String, String>> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String query : queries) {
            for (Map<String, String> paper : searchArxiv(query)) {
                String key = (paper.getOrDefault("link", "") + "|" + paper.getOrDefault("title", "")).trim();
                if (!key.isBlank() && seen.add(key)) {
                    merged.add(paper);
                }
            }
        }

        merged.sort((left, right) -> Double.compare(
                scorePaper(right, originalMessage, queries),
                scorePaper(left, originalMessage, queries)
        ));
        if (merged.size() > arxivMaxResults) {
            return new ArrayList<>(merged.subList(0, arxivMaxResults));
        }
        return merged;
    }

    private boolean looksLikeSearch(String msg) {
        if (msg == null || msg.isBlank()) {
            return false;
        }
        String normalized = msg.toLowerCase();
        return normalized.contains("\u8bba\u6587")
                || normalized.contains("paper")
                || normalized.contains("literature")
                || normalized.contains("reference")
                || normalized.contains("references")
                || normalized.contains("citation")
                || normalized.contains("arxiv")
                || normalized.contains("arivx")
                || normalized.contains("search")
                || normalized.contains("\u6587\u732e")
                || normalized.contains("\u68c0\u7d22")
                || normalized.contains("\u53c2\u8003")
                || normalized.contains("related work")
                || normalized.contains("benchmark")
                || normalized.contains("survey")
                || normalized.contains("sota")
                || normalized.contains("state of the art")
                || normalized.contains("\u7efc\u8ff0")
                || normalized.contains("\u7814\u7a76\u73b0\u72b6");
    }

    private List<Map<String, String>> searchArxiv(String query) {
        if (!arxivEnabled) {
            return List.of();
        }
        try {
            String keywords = query.replaceAll("[锛屻€傦紒锛?.?!]", " ").trim();
            if (keywords.isBlank()) {
                return List.of();
            }
            String encoded = URLEncoder.encode(keywords, StandardCharsets.UTF_8);
            String url = arxivSearchBaseUrl + "?search_query=all:" + encoded
                    + "&start=0&max_results=" + arxivMaxResults
                    + "&sortBy=relevance&sortOrder=descending";

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "TAP/1.0")
                    .timeout(arxivRequestTimeout)
                    .GET();
            if (!arxivApiKey.isBlank() && !arxivApiKeyHeader.isBlank()) {
                builder.header(arxivApiKeyHeader, arxivApiKey);
            }
            HttpRequest req = builder.build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                return List.of();
            }

            var dbf = DocumentBuilderFactory.newInstance();
            var doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(resp.body()));
            var entries = doc.getElementsByTagName("entry");

            List<Map<String, String>> results = new ArrayList<>();
            for (int i = 0; i < entries.getLength() && i < arxivMaxResults; i++) {
                var entry = (org.w3c.dom.Element) entries.item(i);
                String title = xmlText(entry, "title").replaceAll("\\s+", " ").trim();
                if (title.isBlank()) {
                    continue;
                }

                String summary = xmlText(entry, "summary").replaceAll("\\s+", " ").trim();
                String id = xmlText(entry, "id").trim();
                var authorNodes = entry.getElementsByTagName("author");
                List<String> authors = new ArrayList<>();
                for (int j = 0; j < authorNodes.getLength() && j < arxivMaxResults; j++) {
                    authors.add(xmlText((org.w3c.dom.Element) authorNodes.item(j), "name"));
                }
                if (authorNodes.getLength() > arxivMaxResults) {
                    authors.add("et al.");
                }

                results.add(Map.of(
                        "title", title,
                        "authors", String.join(", ", authors),
                        "link", id.replace("http://", "https://"),
                        "summary", summary
                ));
            }
            return results;
        } catch (Exception e) {
            log.warn("arXiv search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private double scorePaper(Map<String, String> paper, String originalMessage, List<String> queries) {
        String title = paper.getOrDefault("title", "").toLowerCase(Locale.ROOT);
        String summary = paper.getOrDefault("summary", "").toLowerCase(Locale.ROOT);
        double score = 0.0;

        for (String query : queries) {
            String normalizedQuery = normalizeRankingText(query);
            if (!normalizedQuery.isBlank() && title.contains(normalizedQuery)) {
                score += 8.0;
            } else if (!normalizedQuery.isBlank() && summary.contains(normalizedQuery)) {
                score += 4.0;
            }
        }

        for (String term : collectRankingTerms(originalMessage, queries)) {
            if (title.contains(term)) {
                score += 3.0;
            } else if (summary.contains(term)) {
                score += 1.2;
            }
        }

        if (title.contains("survey") || title.contains("review")) {
            String normalizedMessage = normalizeRankingText(originalMessage);
            if (normalizedMessage.contains("survey")
                    || normalizedMessage.contains("review")
                    || normalizedMessage.contains("sota")) {
                score += 1.5;
            }
        }
        return score;
    }

    private Set<String> collectRankingTerms(String originalMessage, List<String> queries) {
        Set<String> terms = new LinkedHashSet<>();
        addRankingTerms(terms, originalMessage);
        if (queries != null) {
            for (String query : queries) {
                addRankingTerms(terms, query);
            }
        }
        return terms;
    }

    private void addRankingTerms(Set<String> terms, String text) {
        String normalized = normalizeRankingText(text);
        if (normalized.isBlank()) {
            return;
        }
        for (String token : normalized.split("\\s+")) {
            if (token.length() < 3 || RANKING_STOPWORDS.contains(token)) {
                continue;
            }
            terms.add(token);
        }
    }

    private String normalizeRankingText(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\-\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }
        String normalized = query
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized.substring(0, Math.min(MAX_QUERY_LENGTH, normalized.length()));
    }

    private String extractJsonObject(String raw) {
        if (raw == null) {
            return null;
        }
        int fencedStart = raw.indexOf("```");
        if (fencedStart >= 0) {
            int firstBraceInFence = raw.indexOf('{', fencedStart);
            int fencedEnd = raw.indexOf("```", fencedStart + 3);
            if (firstBraceInFence >= 0 && fencedEnd > firstBraceInFence) {
                raw = raw.substring(firstBraceInFence, fencedEnd);
            }
        }

        int firstBrace = raw.indexOf('{');
        int lastBrace = raw.lastIndexOf('}');
        if (firstBrace < 0 || lastBrace <= firstBrace) {
            return null;
        }
        return raw.substring(firstBrace, lastBrace + 1);
    }

    private String callAi(List<ObjectNode> messages) {
        String userPrompt = extractLastUserPrompt(messages);
        if ("mock".equals(provider) || aiApiKey.isBlank()) {
            return buildFallbackMockReply(userPrompt);
        }

        try {
            String jsonBody = objectMapper.writeValueAsString(buildRequestBody(messages, false));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(aiBaseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + aiApiKey)
                    .header("Content-Type", "application/json")
                    .timeout(AI_CHAT_REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                log.error("AI chat HTTP {}: {}", resp.statusCode(),
                        resp.body().substring(0, Math.min(500, resp.body().length())));
                return "抱歉，AI 服务返回错误状态：" + resp.statusCode();
            }

            JsonNode root = objectMapper.readTree(resp.body());
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            log.error("AI chat failed: {}", e.getMessage(), e);
            return "抱歉，AI 服务暂时不可用，请稍后重试。错误信息：" + e.getMessage();
        }
    }

    private void streamAi(List<ObjectNode> messages, OutputStream outputStream) {
        String userPrompt = extractLastUserPrompt(messages);
        if ("mock".equals(provider) || aiApiKey.isBlank()) {
            streamText(outputStream, buildFallbackMockReply(userPrompt));
            return;
        }

        try {
            String jsonBody = objectMapper.writeValueAsString(buildRequestBody(messages, true));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(aiBaseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + aiApiKey)
                    .header("Content-Type", "application/json")
                    .timeout(AI_STREAM_REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                String errBody;
                try (InputStream errStream = resp.body()) {
                    errBody = new String(errStream.readAllBytes(), StandardCharsets.UTF_8);
                }
                log.error("AI stream HTTP {}: {}", resp.statusCode(),
                        errBody.substring(0, Math.min(500, errBody.length())));
                streamText(outputStream, "AI service error: " + resp.statusCode());
                return;
            }

            boolean emittedAnyContent = false;
            try (InputStream body = resp.body();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(data)) {
                        break;
                    }

                    try {
                        JsonNode root = objectMapper.readTree(data);
                        String delta = extractDeltaContent(root);
                        if (!delta.isEmpty()) {
                            outputStream.write(delta.getBytes(StandardCharsets.UTF_8));
                            outputStream.flush();
                            emittedAnyContent = true;
                        }
                    } catch (Exception ignored) {
                        // Skip malformed chunks and keep the stream alive.
                    }
                }
            }

            // If stream chunks are parseable but carry no display text,
            // fall back to one non-stream request to avoid blank replies.
            if (!emittedAnyContent) {
                String fallbackReply = callAi(messages);
                if (fallbackReply != null && !fallbackReply.isBlank()) {
                    streamText(outputStream, fallbackReply);
                }
            }
        } catch (Exception e) {
            log.error("AI stream failed: {}", e.getMessage(), e);
            streamText(outputStream, "AI service is temporarily unavailable. Please try again later.");
        }
    }

    private ObjectNode buildRequestBody(List<ObjectNode> messages, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.set("messages", objectMapper.valueToTree(messages));
        body.put("temperature", 0.5);
        if (stream) {
            body.put("stream", true);
        }
        return body;
    }

    private String extractDeltaContent(JsonNode root) {
        JsonNode choice = root.path("choices").path(0);
        String text = extractTextFromNode(choice.path("delta"), false);
        if (!text.isEmpty()) {
            return text;
        }

        text = extractTextFromNode(choice.path("message"), false);
        if (!text.isEmpty()) {
            return text;
        }

        text = extractTextFromNode(choice, false);
        if (!text.isEmpty()) {
            return text;
        }

        text = extractTextFromNode(choice.path("delta"), true);
        if (!text.isEmpty()) {
            return text;
        }
        return extractTextFromNode(choice.path("message"), true);
    }

    private String extractTextFromNode(JsonNode node, boolean includeReasoning) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : node) {
                String part = extractTextFromNode(item, includeReasoning);
                if (!part.isEmpty()) {
                    sb.append(part);
                }
            }
            return sb.toString();
        }
        if (!node.isObject()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        appendTextField(sb, node, "content", includeReasoning);
        appendTextField(sb, node, "text", includeReasoning);
        appendTextField(sb, node, "delta", includeReasoning);
        appendTextField(sb, node, "output_text", includeReasoning);
        if (includeReasoning) {
            appendTextField(sb, node, "reasoning_content", true);
        }
        return sb.toString();
    }

    private void appendTextField(StringBuilder sb, JsonNode node, String field, boolean includeReasoning) {
        if (!node.has(field)) {
            return;
        }
        String part = extractTextFromNode(node.get(field), includeReasoning);
        if (!part.isEmpty()) {
            sb.append(part);
        }
    }

    private String extractLastUserPrompt(List<ObjectNode> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode item = messages.get(i);
            if ("user".equals(item.path("role").asText())) {
                return item.path("content").asText("");
            }
        }
        return "";
    }

    private String buildMockReply(String prompt) {
        String normalized = prompt == null ? "" : prompt.toLowerCase();

        if (normalized.contains("ppt") || normalized.contains("璇句欢")) {
            return """
                    ## 璇句欢寤鸿
                    1. 寮€鍦哄厛鏄庣‘鏁欏鐩爣銆佸厛淇煡璇嗗拰璇惧爞浜у嚭銆?                    2. 涓鐢?1 涓牳蹇冧緥棰樻媶瑙ｆ蹇点€佹祦绋嬪拰杈圭晫鏉′欢銆?                    3. 缁撳熬瀹夋帓 1 涓鍫傜粌涔犲拰 1 涓鍚庡欢浼镐换鍔°€?
                    ## 椤甸潰缁撴瀯
                    - 绗?1 椤碉細涓婚銆佺洰鏍囥€侀€傜敤鐝骇
                    - 绗?2 椤碉細鏍稿績姒傚康涓庢湳璇?                    - 绗?3 椤碉細绀轰緥鍒嗘瀽
                    - 绗?4 椤碉細甯歌閿欒
                    - 绗?5 椤碉細缁冧範涓庢€荤粨
                    """;
        }

        if (looksLikeSearch(normalized)) {
            return """
                    ## 妫€绱㈣鏄?                    宸叉寜浣犵殑闂琛ュ厖璁烘枃妫€绱㈢粨鏋溿€備綘鍙互缁х画鎸囧畾锛?                    - 鐮旂┒涓婚
                    - 鏃堕棿鑼冨洿
                    - 甯屾湜寰楀埌鐨勮緭鍑哄舰寮忥紝渚嬪缁艰堪銆佽鍫傚簲鐢ㄦ垨瀹為獙璁捐
                    """;
        }

        return """
                ## 鏁欏鍔╂墜鍥炲
                鎴戝彲浠ョ户缁府浣犲鐞嗚繖浜涗换鍔★細
                - 璁捐璇惧爞璁茶В鎻愮翰
                - 鐢熸垚瀹為獙鎴栦綔涓氳鏄?                - 娑﹁壊鏁欏鍙嶉
                - 鏁寸悊璁烘枃妫€绱㈢粨鏋?
                濡傛灉浣犲笇鏈涘洖绛旀洿璐磋繎鍦烘櫙锛岃琛ュ厖璇剧▼鍚嶇О銆佸鐢熷眰娆″拰鏈熸湜杈撳嚭褰㈠紡銆?                """;
    }

    private void streamText(OutputStream outputStream, String text) {
        try {
            outputStream.write(text.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } catch (Exception ignored) {
            // Client may already be disconnected.
        }
    }

    private void writePapersMarker(OutputStream outputStream, List<Map<String, String>> papers) {
        try {
            String marker = PAPERS_MARKER_PREFIX + objectMapper.writeValueAsString(papers) + PAPERS_MARKER_SUFFIX;
            outputStream.write(marker.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } catch (Exception e) {
            log.warn("write papers marker failed: {}", e.getMessage());
        }
    }

    private String normalizeArxivBaseUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String buildFallbackMockReply(String prompt) {
        String normalized = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);

        if (normalized.contains("ppt") || normalized.contains("课件")) {
            return """
                    ## 课件建议
                    1. 开场先明确教学目标、先修知识和课堂产出。
                    2. 中段用 1 个核心例题拆解概念、流程和边界条件。
                    3. 结尾安排 1 个课堂练习和 1 个课后延伸任务。

                    ## 页面结构
                    - 第 1 页：主题、目标、适用班级
                    - 第 2 页：核心概念与术语
                    - 第 3 页：示例分析
                    - 第 4 页：常见错误
                    - 第 5 页：练习与总结
                    """;
        }

        if (looksLikeSearch(normalized)) {
            return """
                    ## 检索说明
                    已根据你的问题补充论文检索结果。你可以继续指定：
                    - 研究主题
                    - 时间范围
                    - 期望输出形式，例如综述、课堂应用或实验设计
                    """;
        }

        return """
                ## 教学助手回复
                我可以继续帮你处理这些任务：
                - 设计课堂讲解提纲
                - 生成实验或作业说明
                - 润色教学反馈
                - 整理论文检索结果

                如果你希望回答更贴近场景，请补充课程名称、学生层次和期望输出形式。
                """;
    }

    private String normalizeUserMessage(String message) {
        String normalized = message == null ? "" : message.replace("\r\n", "\n").trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        if (normalized.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("消息不能超过 4000 个字符");
        }
        return normalized;
    }

    private List<MessageItem> sanitizeHistory(List<MessageItem> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<MessageItem> sanitized = new ArrayList<>();
        int start = Math.max(0, history.size() - MAX_HISTORY);
        for (int i = start; i < history.size(); i++) {
            MessageItem item = history.get(i);
            if (item == null) {
                continue;
            }
            String role = normalizeHistoryRole(item.role());
            String content = normalizeHistoryContent(item.content());
            if (role == null || content == null) {
                continue;
            }
            sanitized.add(new MessageItem(role, content));
        }
        return sanitized;
    }

    private String normalizeHistoryRole(String role) {
        if (role == null) {
            return null;
        }
        String normalized = role.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "system", "user", "assistant" -> normalized;
            default -> null;
        };
    }

    private String normalizeHistoryContent(String content) {
        if (content == null) {
            return null;
        }
        String normalized = content.replace("\r\n", "\n").trim();
        if (normalized.isBlank()) {
            return null;
        }
        return normalized.substring(0, Math.min(MAX_MESSAGE_LENGTH, normalized.length()));
    }

    private ObjectNode msg(String role, String content) {
        return objectMapper.createObjectNode().put("role", role).put("content", content);
    }

    private String xmlText(org.w3c.dom.Element el, String tag) {
        var nl = el.getElementsByTagName(tag);
        if (nl.getLength() == 0 || nl.item(0).getTextContent() == null) {
            return "";
        }
        return nl.item(0).getTextContent();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

