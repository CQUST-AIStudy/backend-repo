package com.tap.backend.service.animation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tap.backend.ai.AiProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 动画讲解模块的 AI 客户端：
 * - chat：调用 OpenAI 兼容 /chat/completions 生成大纲与 HTML 动画
 * - tts：调用 OpenAI 兼容 /audio/speech 生成旁白音频（默认 qwen-tts）
 * <p>
 * 凭据复用 tap.ai 配置：优先 dashscope（TTS 必须走 dashscope 兼容网关），
 * 文本生成在 openai 配置可用时优先 openai（如 DeepSeek）。
 */
@Component
public class AnimationAiClient {

    private static final Logger log = LoggerFactory.getLogger(AnimationAiClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private final String chatBaseUrl;
    private final String chatApiKey;
    private final String chatModel;

    private final String ttsBaseUrl;
    private final String ttsApiKey;
    private final String ttsModel;
    private final String ttsVoice;

    public AnimationAiClient(AiProperties aiProperties,
                             ObjectMapper objectMapper,
                             @Value("${tap.animation.chat-model:}") String configuredChatModel,
                             @Value("${tap.animation.tts-model:qwen-tts}") String configuredTtsModel,
                             @Value("${tap.animation.tts-voice:Cherry}") String configuredTtsVoice) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        AiProperties.OpenAi oa = aiProperties.openai();
        AiProperties.Dashscope ds = aiProperties.dashscope();
        String openaiKey = firstNonBlank(oa == null ? null : oa.apiKey(), System.getenv("OPENAI_API_KEY"));
        String dashscopeKey = firstNonBlank(ds == null ? null : ds.apiKey(), System.getenv("DASHSCOPE_API_KEY"));

        // 文本模型：openai(DeepSeek) 优先，缺省回落 dashscope
        if (!isBlank(openaiKey)) {
            this.chatBaseUrl = trimUrl(firstNonBlank(oa == null ? null : oa.baseUrl(), "https://api.deepseek.com/v1"));
            this.chatApiKey = openaiKey.trim();
            this.chatModel = firstNonBlank(configuredChatModel, oa == null ? null : oa.model(), "deepseek-chat");
        } else if (!isBlank(dashscopeKey)) {
            this.chatBaseUrl = trimUrl(firstNonBlank(ds == null ? null : ds.baseUrl(),
                    "https://dashscope.aliyuncs.com/compatible-mode/v1"));
            this.chatApiKey = dashscopeKey.trim();
            this.chatModel = firstNonBlank(configuredChatModel, "qwen-plus");
        } else {
            this.chatBaseUrl = null;
            this.chatApiKey = null;
            this.chatModel = null;
        }

        // TTS 固定走 dashscope 兼容接口（qwen-tts），openai 网关支持时也可用
        if (!isBlank(dashscopeKey)) {
            this.ttsBaseUrl = trimUrl(firstNonBlank(ds == null ? null : ds.baseUrl(),
                    "https://dashscope.aliyuncs.com/compatible-mode/v1"));
            this.ttsApiKey = dashscopeKey.trim();
        } else if (!isBlank(openaiKey)) {
            this.ttsBaseUrl = trimUrl(firstNonBlank(oa == null ? null : oa.baseUrl(), ""));
            this.ttsApiKey = openaiKey.trim();
        } else {
            this.ttsBaseUrl = null;
            this.ttsApiKey = null;
        }
        this.ttsModel = configuredTtsModel;
        this.ttsVoice = configuredTtsVoice;
    }

    public boolean isChatAvailable() {
        return chatBaseUrl != null && !isBlank(chatApiKey);
    }

    public boolean isTtsAvailable() {
        return ttsBaseUrl != null && !isBlank(ttsApiKey);
    }

    /** 调用 chat/completions，返回助手文本内容。 */
    public String chat(String systemPrompt, String userPrompt, double temperature) {
        if (!isChatAvailable()) {
            throw new IllegalStateException("未配置 AI 接口（OPENAI_API_KEY / DASHSCOPE_API_KEY 均为空）");
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", chatModel);
            body.set("messages", objectMapper.valueToTree(List.of(
                    message("system", systemPrompt),
                    message("user", userPrompt)
            )));
            body.put("temperature", temperature);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(chatBaseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + chatApiKey)
                    .timeout(Duration.ofSeconds(180))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("AI 接口返回 " + response.statusCode() + ": " + truncate(response.body(), 300));
            }
            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new IllegalStateException("AI 返回内容为空");
            }
            return content;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AI 调用失败: " + e.getMessage(), e);
        }
    }

    /** 调用 /audio/speech 生成旁白音频，返回音频字节（mp3/wav，由网关决定）。 */
    public byte[] tts(String text) {
        if (!isTtsAvailable()) {
            throw new IllegalStateException("未配置 TTS 接口");
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", ttsModel);
            body.put("voice", ttsVoice);
            body.put("input", text);
            body.put("response_format", "mp3");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ttsBaseUrl + "/audio/speech"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + ttsApiKey)
                    // 部分网关严格校验 Accept，多类型一起发以兼容
                    .header("Accept", "application/json, audio/mpeg;q=0.9, */*;q=0.5")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("TTS 接口返回 " + response.statusCode() + ": "
                        + truncate(new String(response.body()), 300));
            }
            byte[] audio = response.body();
            if (audio == null || audio.length < 64) {
                throw new IllegalStateException("TTS 返回音频为空");
            }
            return audio;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("TTS 调用失败: " + e.getMessage(), e);
        }
    }

    private ObjectNode message(String role, String content) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimUrl(String url) {
        return url == null ? null : url.replaceAll("/+$", "");
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
