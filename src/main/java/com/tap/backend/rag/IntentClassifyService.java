package com.tap.backend.rag;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tap.backend.ai.AiProperties;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class IntentClassifyService {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifyService.class);
    private static final Set<String> VALID_INTENTS = Set.of("debug", "procedure", "concept", "summary", "paper");
    private static final Set<String> INTEGRITY_KEYWORDS = Set.of(
            "帮我写完整代码",
            "帮我写代码",
            "帮我完成作业",
            "帮我写实验报告",
            "直接给我答案",
            "帮我做",
            "替我写",
            "帮我交作业"
    );

    private final AiProperties aiProps;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    public IntentClassifyService(AiProperties aiProps) {
        this.aiProps = aiProps;
    }

    public record IntentResult(String intentType, boolean academicIntegrityViolation) {}

    public IntentResult classify(String query) {
        if (query == null || query.isBlank()) {
            return new IntentResult("concept", false);
        }

        boolean integrityViolation = INTEGRITY_KEYWORDS.stream().anyMatch(query::contains);
        LlmConfig llmConfig = resolveLlmConfig();
        if (llmConfig == null) {
            return new IntentResult(classifyByKeywords(query), integrityViolation);
        }

        try {
            JsonObject reqBody = new JsonObject();
            reqBody.addProperty("model", llmConfig.model());
            reqBody.addProperty("temperature", 0.1);

            JsonArray messages = new JsonArray();
            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", buildClassifyPrompt());
            messages.add(sysMsg);

            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", query);
            messages.add(userMsg);
            reqBody.add("messages", messages);

            RequestBody okBody = RequestBody.create(
                    reqBody.toString(), MediaType.parse("application/json; charset=utf-8"));
            Request okReq = new Request.Builder()
                    .url(llmConfig.baseUrl() + "/chat/completions")
                    .addHeader("Authorization", "Bearer " + llmConfig.apiKey())
                    .post(okBody)
                    .build();

            try (Response resp = httpClient.newCall(okReq).execute()) {
                if (!resp.isSuccessful()) {
                    log.warn("[IntentClassify] API error: {}", resp.code());
                    return new IntentResult(classifyByKeywords(query), integrityViolation);
                }

                String respStr = resp.body() == null ? "" : resp.body().string();
                JsonObject json = JsonParser.parseString(respStr).getAsJsonObject();
                String content = json.getAsJsonArray("choices").get(0).getAsJsonObject()
                        .getAsJsonObject("message").get("content").getAsString().trim().toLowerCase(Locale.ROOT);

                for (String intent : VALID_INTENTS) {
                    if (content.contains(intent)) {
                        if (content.contains("academic_integrity") || content.contains("代写")) {
                            integrityViolation = true;
                        }
                        return new IntentResult(intent, integrityViolation);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[IntentClassify] failed, fallback to keyword classifier: {}", e.getMessage());
        }

        return new IntentResult(classifyByKeywords(query), integrityViolation);
    }

    private String buildClassifyPrompt() {
        return """
                你是一个意图分类器。请将学生问题分类为以下之一，并且只输出标签本身：
                - debug
                - procedure
                - concept
                - summary
                - paper

                如果问题包含代写、替做、直接要答案等学术不端请求，请在标签后追加 academic_integrity。

                示例：
                “链表和数组有什么区别？” -> concept
                “我的代码空指针报错了” -> debug
                “实验三的步骤是什么？” -> procedure
                “帮我总结第五章内容” -> summary
                “这篇论文的核心贡献是什么？” -> paper
                “帮我写完整实验代码” -> debug academic_integrity
                """;
    }

    private String classifyByKeywords(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        if (lower.contains("报错") || lower.contains("异常") || lower.contains("bug")
                || lower.contains("debug") || lower.contains("错误")) {
            return "debug";
        }
        if (lower.contains("步骤") || lower.contains("流程") || lower.contains("如何")
                || lower.contains("怎么做") || lower.contains("怎么实现")) {
            return "procedure";
        }
        if (lower.contains("总结") || lower.contains("概括") || lower.contains("复习")
                || lower.contains("梳理")) {
            return "summary";
        }
        if (lower.contains("论文") || lower.contains("参考文献")
                || lower.contains("reference") || lower.contains("citation")) {
            return "paper";
        }
        return "concept";
    }

    private LlmConfig resolveLlmConfig() {
        String provider = aiProps.provider() == null ? "" : aiProps.provider().trim().toLowerCase(Locale.ROOT);
        if ("dashscope".equals(provider) || "qwen".equals(provider)) {
            AiProperties.Dashscope ds = aiProps.dashscope();
            if (ds == null || ds.apiKey() == null || ds.apiKey().isBlank()) {
                return null;
            }
            String baseUrl = ds.baseUrl() == null || ds.baseUrl().isBlank()
                    ? "https://dashscope.aliyuncs.com/compatible-mode/v1"
                    : ds.baseUrl();
            String model = ds.model() == null || ds.model().isBlank() ? "qwen-plus" : ds.model();
            return new LlmConfig(trimTrailingSlash(baseUrl), ds.apiKey(), model);
        }

        AiProperties.OpenAi oa = aiProps.openai();
        if (oa == null || oa.apiKey() == null || oa.apiKey().isBlank()) {
            return null;
        }
        String baseUrl = oa.baseUrl() == null || oa.baseUrl().isBlank()
                ? "https://api.deepseek.com/v1"
                : oa.baseUrl();
        String model = oa.model() == null || oa.model().isBlank() ? "deepseek-chat" : oa.model();
        return new LlmConfig(trimTrailingSlash(baseUrl), oa.apiKey(), model);
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record LlmConfig(String baseUrl, String apiKey, String model) {}
}
