package com.tap.backend.academic.service.impl;

import com.google.gson.*;
import com.tap.backend.academic.entity.Experiment;
import com.tap.backend.academic.entity.Submission;
import com.tap.backend.academic.service.AiReportGenerator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DeepSeekAiReportGenerator implements AiReportGenerator {
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build();

    public DeepSeekAiReportGenerator(
            @Value("${tap.ai.openai.api-key:}") String apiKey,
            @Value("${tap.ai.openai.base-url:https://api.deepseek.com/v1}") String baseUrl,
            @Value("${tap.ai.openai.model:deepseek-chat}") String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @Override
    public String generate(Experiment experiment, Submission submission, Map<String, Object> userData) throws Exception {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("AI service is not configured");
        String code = submission.getCode();
        if (code.length() > 6000) code = code.substring(0, 6000) + "\n... (代码过长，已截断)";
        String systemPrompt = "你是高校数据结构课程助教。输出中文Markdown实验报告，必须依次包含二级标题：实验目的、实验环境、实验内容、实验总结。结合代码分析，但不得伪造运行结果或成绩。";
        String userPrompt = "实验：" + experiment.getName() + "\n实验描述：" + value(experiment.getDescribe())
                + "\n学生：" + value(userData.get("studentName")) + "\n代码：\n```c\n" + code + "\n```";
        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("model", model);
        bodyJson.addProperty("stream", false);
        bodyJson.addProperty("max_tokens", 2500);
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        bodyJson.add("messages", messages);
        RequestBody body = RequestBody.create(bodyJson.toString(), MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder().url(trimSlash(baseUrl) + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey).post(body).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException("AI service request failed");
            JsonArray choices = JsonParser.parseString(response.body().string()).getAsJsonObject().getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) throw new IllegalStateException("AI service returned no report");
            return choices.get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString().trim();
        }
    }

    private JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private String value(Object value) { return value == null ? "" : value.toString(); }
    private String trimSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
}
