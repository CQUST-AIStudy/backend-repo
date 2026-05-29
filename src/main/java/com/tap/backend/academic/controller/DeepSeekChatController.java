package com.tap.backend.academic.controller;

import com.tap.backend.academic.security.StudentSessionResolver;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api")
public class DeepSeekChatController {

    private static final String SYSTEM_PROMPT =
            "你是一个专业的数据结构与算法学习助手，专注于帮助大学生解决数据结构课程中的学习问题。"
                    + "你擅长解释数据结构概念、分析算法复杂度、帮助调试 C/C++ 代码，并提供解题思路。"
                    + "回答要简洁、准确、有针对性，并使用中文。";

    @Value("${tap.ai.openai.api-key:}")
    private String apiKey;

    @Value("${tap.ai.openai.base-url:https://api.deepseek.com/v1}")
    private String baseUrl;

    @Value("${tap.ai.openai.model:deepseek-chat}")
    private String model;

    private final StudentSessionResolver studentSessionResolver;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    public DeepSeekChatController(StudentSessionResolver studentSessionResolver) {
        this.studentSessionResolver = studentSessionResolver;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chat(
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest
    ) {
        studentSessionResolver.requireStudentId(httpRequest);
        String userInput = request.get("userInput");
        System.out.println("[DeepSeek] request: " + userInput);

        if (userInput == null || userInput.trim().isEmpty()) {
            return ResponseEntity.ok().body(out -> {
                out.write("请输入有效的问题".getBytes(StandardCharsets.UTF_8));
                out.flush();
            });
        }

        StreamingResponseBody body = outputStream -> {
            try {
                if (apiKey == null || apiKey.isBlank()) {
                    outputStream.write(
                            "AI service is not configured. Set OPENAI_API_KEY in local.env.ps1 or the process environment."
                                    .getBytes(StandardCharsets.UTF_8)
                    );
                    outputStream.flush();
                    return;
                }

                JsonObject reqBody = new JsonObject();
                reqBody.addProperty("model", model);
                reqBody.addProperty("stream", true);

                JsonArray messages = new JsonArray();
                JsonObject systemMessage = new JsonObject();
                systemMessage.addProperty("role", "system");
                systemMessage.addProperty("content", SYSTEM_PROMPT);
                messages.add(systemMessage);

                JsonObject userMessage = new JsonObject();
                userMessage.addProperty("role", "user");
                userMessage.addProperty("content", userInput);
                messages.add(userMessage);

                reqBody.add("messages", messages);

                okhttp3.RequestBody okBody = okhttp3.RequestBody.create(
                        reqBody.toString(),
                        okhttp3.MediaType.parse("application/json; charset=utf-8")
                );

                Request okReq = new Request.Builder()
                        .url(baseUrl + "/chat/completions")
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .addHeader("Content-Type", "application/json")
                        .post(okBody)
                        .build();

                try (Response response = httpClient.newCall(okReq).execute()) {
                    if (!response.isSuccessful()) {
                        String errBody = response.body() != null ? response.body().string() : "unknown";
                        System.err.println("[DeepSeek] API error: " + response.code() + " " + errBody);
                        outputStream.write(("AI 服务暂时不可用，错误码: " + response.code()).getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                        return;
                    }

                    ResponseBody respBody = response.body();
                    if (respBody == null) {
                        return;
                    }

                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(respBody.byteStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (!line.startsWith("data: ")) {
                                continue;
                            }
                            String data = line.substring(6).trim();
                            if ("[DONE]".equals(data)) {
                                break;
                            }

                            try {
                                JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                                JsonArray choices = chunk.getAsJsonArray("choices");
                                if (choices == null || choices.isEmpty()) {
                                    continue;
                                }
                                JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                                if (delta == null) {
                                    continue;
                                }

                                if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                                    continue;
                                }

                                if (delta.has("content") && !delta.get("content").isJsonNull()) {
                                    String content = delta.get("content").getAsString();
                                    outputStream.write(content.getBytes(StandardCharsets.UTF_8));
                                    outputStream.flush();
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[DeepSeek] exception: " + e.getMessage());
                try {
                    outputStream.write("抱歉，AI 助手暂时无法回答，请稍后再试。".getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                } catch (Exception ignored) {
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }
}
