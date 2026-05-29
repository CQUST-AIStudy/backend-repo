package com.tap.backend.academic.leetcode.execution;

import com.tap.backend.academic.entity.LeetCodeProblem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class LeetCodeAiEvaluationService {

    private static final Logger logger = LoggerFactory.getLogger(LeetCodeAiEvaluationService.class);
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 8000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 60000;
    private static final int DEFAULT_EVAL_TIMEOUT_SECONDS = 55;
    private static final ExecutorService AI_EVAL_EXECUTOR = Executors.newFixedThreadPool(2);

    private final ObjectMapper objectMapper;
    private final RestClient aiRestClient;
    private final String aiModel;
    private final int evalTimeoutSeconds;

    public LeetCodeAiEvaluationService(
            ObjectMapper objectMapper,
            @Value("${tap.ai.openai.base-url:https://api.deepseek.com/v1}") String aiBaseUrl,
            @Value("${tap.ai.openai.api-key:}") String aiApiKey,
            @Value("${tap.ai.openai.model:deepseek-chat}") String aiModel,
            @Value("${leetcode.ai.connect-timeout-ms:" + DEFAULT_CONNECT_TIMEOUT_MS + "}") int connectTimeoutMs,
            @Value("${leetcode.ai.read-timeout-ms:" + DEFAULT_READ_TIMEOUT_MS + "}") int readTimeoutMs,
            @Value("${leetcode.ai.eval-timeout-seconds:" + DEFAULT_EVAL_TIMEOUT_SECONDS + "}") int evalTimeoutSeconds) {
        this.objectMapper = objectMapper;
        this.aiModel = (aiModel == null || aiModel.isBlank()) ? "deepseek-chat" : aiModel.trim();
        this.evalTimeoutSeconds = evalTimeoutSeconds > 0 ? evalTimeoutSeconds : DEFAULT_EVAL_TIMEOUT_SECONDS;

        String effectiveApiKey = (aiApiKey == null || aiApiKey.isBlank()) ? System.getenv("OPENAI_API_KEY") : aiApiKey.trim();
        if (effectiveApiKey == null || effectiveApiKey.isBlank()) {
            this.aiRestClient = null;
        } else {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(connectTimeoutMs > 0 ? connectTimeoutMs : DEFAULT_CONNECT_TIMEOUT_MS);
            requestFactory.setReadTimeout(readTimeoutMs > 0 ? readTimeoutMs : DEFAULT_READ_TIMEOUT_MS);
            this.aiRestClient = RestClient.builder()
                    .baseUrl((aiBaseUrl == null || aiBaseUrl.isBlank()) ? "https://api.deepseek.com/v1" : aiBaseUrl.trim())
                    .defaultHeader("Authorization", "Bearer " + effectiveApiKey)
                    .requestFactory(requestFactory)
                    .build();
        }
    }

    public AiEvaluationResult evaluate(LeetCodeProblem problem, String code, String language) {
        if (aiRestClient == null) {
            return unavailable("AI service is not configured. Set tap.ai.openai.api-key first.");
        }

        Future<AiEvaluationResult> future = AI_EVAL_EXECUTOR.submit(() -> {
            String prompt = buildPrompt(problem, code, language);
            String content = callChatCompletions(prompt);
            return parse(content);
        });

        try {
            return future.get(evalTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            logger.warn("AI evaluation timed out after {}s", evalTimeoutSeconds);
            return unavailable("AI request timed out after " + evalTimeoutSeconds + " seconds");
        } catch (Exception e) {
            future.cancel(true);
            logger.warn("AI evaluation failed, fallback used: {}", e.getMessage());
            return unavailable("AI request failed: " + e.getMessage());
        }
    }

    private String buildPrompt(LeetCodeProblem problem, String code, String language) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a strict LeetCode reviewer. Do NOT execute code.\n");
        prompt.append("Use static reasoning only and return JSON only, no markdown.\n");
        prompt.append("Scoring rubric (0-100): correctness 60, edge-cases 20, complexity 10, code quality 10.\n");
        prompt.append("Output schema:\n");
        prompt.append("{");
        prompt.append("\"accepted\":true|false,");
        prompt.append("\"score\":0-100,");
        prompt.append("\"confidence\":0-1,");
        prompt.append("\"estimated_pass_rate\":0-1,");
        prompt.append("\"summary\":\"one sentence\",");
        prompt.append("\"feedback_markdown\":\"detailed markdown feedback in Chinese\",");
        prompt.append("\"strengths\":[\"...\"],");
        prompt.append("\"issues\":[\"...\"],");
        prompt.append("\"skill_suggestions\":[\"...\"],");
        prompt.append("\"risk_notes\":[\"...\"]");
        prompt.append("}\n\n");
        prompt.append("Problem Title: ").append(safeText(problem == null ? null : problem.getTitleMain(), 200)).append("\n");
        prompt.append("Difficulty: ").append(safeText(problem == null ? null : problem.getDifficulty(), 40)).append("\n");
        prompt.append("Language: ").append(language).append("\n\n");
        prompt.append("Problem Statement:\n").append(safeText(problem == null ? null : problem.getProblemText(), 7000)).append("\n\n");
        prompt.append("Official Solution Reference:\n").append(safeText(problem == null ? null : problem.getSolutionText(), 5000)).append("\n\n");
        prompt.append("Student Code:\n").append(safeText(code, 12000)).append("\n\n");
        prompt.append("Constraints:\n");
        prompt.append("- Prefer concrete bug findings and edge-cases.\n");
        prompt.append("- If confidence < 0.6, keep accepted=false unless code is clearly correct.\n");
        prompt.append("- Use concise Chinese in feedback_markdown.\n");
        return prompt.toString();
    }

    private String callChatCompletions(String prompt) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", aiModel);
        body.put("temperature", 0.2);
        body.put("max_tokens", 800);
        body.set("messages", objectMapper.valueToTree(List.of(
                messageNode("system", "You are a senior programming reviewer. Output valid JSON only."),
                messageNode("user", prompt)
        )));

        String raw = aiRestClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .body(String.class);

        JsonNode root = objectMapper.readTree(raw);
        return root.path("choices").path(0).path("message").path(0).asText("")
                .isEmpty()
                ? root.path("choices").path(0).path("message").path("content").asText("")
                : root.path("choices").path(0).path("message").path(0).asText("");
    }

    private ObjectNode messageNode(String role, String content) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }

    private AiEvaluationResult parse(String content) {
        if (content == null || content.isBlank()) {
            return unavailable("AI returned empty content.");
        }

        try {
            String normalized = extractJson(content.trim());
            JsonNode node = objectMapper.readTree(normalized);

            AiEvaluationResult result = new AiEvaluationResult();
            boolean accepted = node.path("accepted").asBoolean(false);
            result.setAccepted(accepted);
            result.setScore(clampScore(node.path("score").asInt(accepted ? 85 : 55)));
            result.setConfidence(clamp01(node.path("confidence").asDouble(accepted ? 0.8 : 0.6)));
            result.setEstimatedPassRate(clamp01(node.path("estimated_pass_rate").asDouble(accepted ? 0.85 : 0.5)));

            String summary = node.path("summary").asText("");
            String feedback = node.path("feedback_markdown").asText("");
            List<String> strengths = toStringList(node.path("strengths"));
            List<String> issues = toStringList(node.path("issues"));
            List<String> suggestions = toStringList(node.path("skill_suggestions"));
            List<String> risks = toStringList(node.path("risk_notes"));

            if (feedback.isBlank()) {
                feedback = buildAutoFeedback(result.accepted(), summary, strengths, issues, suggestions);
            }

            result.setFeedback(feedback);
            result.setSkillSuggestions(suggestions);
            result.setRiskNotes(risks);
            return result;
        } catch (Exception e) {
            return unavailable("AI JSON parse failed: " + e.getMessage());
        }
    }

    private String extractJson(String raw) {
        String normalized = raw;
        if (normalized.startsWith("```")) {
            int firstLine = normalized.indexOf('\n');
            int lastFence = normalized.lastIndexOf("```");
            if (firstLine > 0 && lastFence > firstLine) {
                normalized = normalized.substring(firstLine + 1, lastFence).trim();
            }
        }
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return normalized.substring(start, end + 1);
        }
        return normalized;
    }

    private String buildAutoFeedback(
            boolean accepted,
            String summary,
            List<String> strengths,
            List<String> issues,
            List<String> suggestions) {
        StringBuilder feedback = new StringBuilder();
        feedback.append("## AI Review Summary\n");
        feedback.append("- Verdict: ")
                .append(accepted ? "Likely accepted (static reasoning)" : "Not accepted (static reasoning)")
                .append("\n");
        if (!summary.isBlank()) {
            feedback.append("- Summary: ").append(summary).append("\n");
        }
        if (!strengths.isEmpty()) {
            feedback.append("\n### Strengths\n");
            strengths.forEach(item -> feedback.append("- ").append(item).append("\n"));
        }
        if (!issues.isEmpty()) {
            feedback.append("\n### Issues\n");
            issues.forEach(item -> feedback.append("- ").append(item).append("\n"));
        }
        if (!suggestions.isEmpty()) {
            feedback.append("\n### Improvement Suggestions\n");
            suggestions.forEach(item -> feedback.append("- ").append(item).append("\n"));
        }
        return feedback.toString();
    }

    private AiEvaluationResult unavailable(String reason) {
        AiEvaluationResult result = new AiEvaluationResult();
        result.setUnavailable(true);
        result.setAccepted(false);
        result.setScore(0);
        result.setConfidence(0.0);
        result.setEstimatedPassRate(0.0);
        result.setFeedback("## AI Review Unavailable\n"
                + "- Reason: " + reason + "\n"
                + "- This is not a final grading result.\n"
                + "- Please retry later or check AI API configuration.");
        result.setSkillSuggestions(List.of(
                "Please retry once AI service recovers",
                "Verify API key and outbound network policy",
                "Switch to a faster model if needed"
        ));
        result.setRiskNotes(List.of(reason));
        return result;
    }

    private List<String> toStringList(JsonNode node) {
        List<String> items = new ArrayList<>();
        if (node == null || node.isMissingNode()) {
            return items;
        }
        if (node.isArray()) {
            node.forEach(item -> {
                String text = item.asText("").trim();
                if (!text.isEmpty()) {
                    items.add(text);
                }
            });
            return items;
        }
        String text = node.asText("").trim();
        if (!text.isEmpty()) {
            items.add(text);
        }
        return items;
    }

    private int clampScore(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private double clamp01(double value) {
        return Math.max(0d, Math.min(1d, value));
    }

    private String safeText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
