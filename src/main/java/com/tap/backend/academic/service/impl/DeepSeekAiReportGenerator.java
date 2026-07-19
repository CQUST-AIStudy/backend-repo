package com.tap.backend.academic.service.impl;

import com.google.gson.*;
import com.tap.backend.academic.service.AiReportException;
import com.tap.backend.academic.service.AiReportGenerator;
import com.tap.backend.academic.teacherexperiment.AiReportContext;
import java.net.SocketTimeoutException;
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
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build();

    public DeepSeekAiReportGenerator(
            @Value("${tap.ai.openai.api-key:}") String apiKey,
            @Value("${tap.ai.openai.base-url:https://api.deepseek.com/v1}") String baseUrl,
            @Value("${tap.ai.openai.model:deepseek-chat}") String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @Override
    public String generate(AiReportContext context, String code, Map<String, Object> userData) throws Exception {
        if (apiKey == null || apiKey.isBlank()) throw AiReportException.configMissing();
        if (code.length() > 6000) code = code.substring(0, 6000) + "\n... (代码过长，已截断)";
        String systemPrompt = "你是数据结构课程助教。输出中文Markdown实验报告，必须依次包含二级标题：实验目的、实验环境、实验内容、实验总结。结合代码分析，但不得伪造运行结果或成绩。除非输入明确提供学校名称，否则不得猜测、虚构或提及任何学校名称。";
        String userPrompt = "实验：" + context.getName() + "\n实验描述：" + value(context.getDescription())
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
            if (!response.isSuccessful()) {
                String detail = response.body() != null ? response.body().string() : "";
                throw AiReportException.upstream(response.code(), detail);
            }
            if (response.body() == null) throw AiReportException.emptyResponse();
            JsonObject parsed = JsonParser.parseString(response.body().string()).getAsJsonObject();
            JsonArray choices = parsed.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) throw AiReportException.badResponse("no choices");
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null || !message.has("content")) throw AiReportException.badResponse("no content");
            return message.get("content").getAsString().trim();
        } catch (SocketTimeoutException e) {
            throw AiReportException.timeout(e);
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
