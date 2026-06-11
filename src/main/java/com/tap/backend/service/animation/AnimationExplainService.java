package com.tap.backend.service.animation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.animation.AnimationExplainEntity;
import com.tap.backend.domain.animation.AnimationFrameEntity;
import com.tap.backend.infra.storage.ObjectStorageService;
import com.tap.backend.repo.AnimationExplainRepository;
import com.tap.backend.repo.AnimationFrameRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class AnimationExplainService {

    private static final Logger log = LoggerFactory.getLogger(AnimationExplainService.class);
    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private static final String OUTLINE_SYSTEM = """
            你是一位专业的教学动画脚本专家。根据学生输入的知识点/主题，生成一份 HTML 动画讲解大纲。
            
            ## 任务
            1. 提炼主题，写 60-120 秒逐字旁白稿（中文，口语化）
            2. 拆成 4-8 个分镜，每镜 8-20 秒旁白
            3. 为每镜写「网页动画描述」，供后续 AI 生成 HTML/CSS/JS 动画
            
            ## 输出（严格 JSON，无其他文字）
            {
              "title": "讲解标题（不超过20字）",
              "frames": [
                {
                  "index": 1,
                  "title": "分镜标题",
                  "narration": "旁白文本",
                  "htmlPrompt": "动画描述：展示什么元素、做什么动效、配色倾向"
                }
              ]
            }
            """;

    private static final String HTML_SYSTEM = """
            你是网页动画设计工程师，为教学讲解视频的单个分镜生成独立 HTML 动画。
            
            ## 输出要求
            - 严格 JSON：{ "html": "完整 HTML 文档" }
            - 完整可独立运行（含 <!DOCTYPE html>），内联 CSS/JS，可用 SVG/Canvas
            - 尺寸 1280x720（16:9），禁止外部 CDN/图片/字体
            - 标题 ≥ 48px，正文 ≥ 24px，主色对比强
            - 动画 4-8 秒，自动循环或结束时静止
            - 画面中严禁显示旁白文字（旁白由字幕层处理），只展示可视化元素
            - 不要使用 alert/prompt/confirm
            """;

    private final AnimationExplainRepository explainRepo;
    private final AnimationFrameRepository frameRepo;
    private final AnimationAiClient aiClient;
    private final ObjectStorageService storageService;
    private final ObjectMapper objectMapper;
    private final AnimationExplainRunner runner;

    public AnimationExplainService(AnimationExplainRepository explainRepo,
                                   AnimationFrameRepository frameRepo,
                                   AnimationAiClient aiClient,
                                   ObjectStorageService storageService,
                                   ObjectMapper objectMapper,
                                   @Lazy AnimationExplainRunner runner) {
        this.explainRepo = explainRepo;
        this.frameRepo = frameRepo;
        this.aiClient = aiClient;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.runner = runner;
    }

    @Transactional
    public Map<String, Object> create(Long userId, String topic, String style) {
        if (topic == null || topic.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "主题不能为空");
        }
        if (!aiClient.isChatAvailable()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 服务未配置");
        }

        AnimationExplainEntity entity = new AnimationExplainEntity();
        entity.setUserId(userId);
        entity.setTopic(topic.trim());
        entity.setStyle(style == null || style.isBlank() ? "cyber-clean" : style.trim());
        entity.setStatus("PENDING");
        entity.setProgress(0);
        entity.setCurrentStep("排队中");
        explainRepo.save(entity);

        // 事务提交后再启动异步任务，避免竞态条件
        Long explainId = entity.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runner.runAsync(explainId);
            }
        });
        return toSummaryDto(entity);
    }

    public void runPipeline(Long explainId) throws Exception {
        AnimationExplainEntity explain = explainRepo.findById(explainId)
                .orElseThrow(() -> new IllegalStateException("任务不存在"));
        explain.setStatus("PROCESSING");
        explain.setProgress(5);
        explain.setCurrentStep("生成大纲");
        explainRepo.save(explain);

        String stylePrompt = AnimationStylePresets.resolvePrompt(explain.getStyle());
        String outlineJson = aiClient.chat(OUTLINE_SYSTEM,
                "【主题】\n" + explain.getTopic() + "\n\n【视觉风格】\n" + stylePrompt,
                0.7);
        JsonNode outline = parseJson(outlineJson);
        String title = outline.path("title").asText("知识点讲解");
        JsonNode framesNode = outline.path("frames");
        if (!framesNode.isArray() || framesNode.isEmpty()) {
            throw new IllegalStateException("AI 未返回有效分镜大纲");
        }

        explain.setTitle(title);
        explain.setFrameCount(framesNode.size());
        explain.setProgress(15);
        explain.setCurrentStep("大纲完成，开始生成分镜");
        explainRepo.save(explain);
        frameRepo.deleteAllByExplainId(explainId);

        String previousHtml = null;
        int total = framesNode.size();
        for (int i = 0; i < total; i++) {
            JsonNode frameNode = framesNode.get(i);
            int index = frameNode.path("index").asInt(i + 1);
            String frameTitle = frameNode.path("title").asText("分镜 " + index);
            String narration = frameNode.path("narration").asText("");
            String htmlPrompt = frameNode.path("htmlPrompt").asText("");

            AnimationFrameEntity frame = new AnimationFrameEntity();
            frame.setExplainId(explainId);
            frame.setFrameIndex(index);
            frame.setTitle(frameTitle);
            frame.setNarration(narration);
            frame.setVisualHint(htmlPrompt);
            frame.setStatus("PENDING");
            frameRepo.save(frame);

            int baseProgress = 15 + (int) ((i / (double) total) * 75);
            explain.setProgress(baseProgress);
            explain.setCurrentStep("生成分镜 " + (i + 1) + "/" + total);
            explainRepo.save(explain);

            String html = generateFrameHtml(explain.getTopic(), stylePrompt, frameTitle, narration, htmlPrompt, previousHtml);
            previousHtml = html;

            String htmlKey = "animation/" + explainId + "/frames/" + index + "/scene.html";
            storageService.putBytes(htmlKey, html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
            frame.setHtmlObjectKey(htmlKey);

            if (aiClient.isTtsAvailable() && narration != null && !narration.isBlank()) {
                try {
                    byte[] audio = aiClient.tts(narration);
                    String audioKey = "animation/" + explainId + "/frames/" + index + "/narration.mp3";
                    storageService.putBytes(audioKey, audio, "audio/mpeg");
                    frame.setAudioObjectKey(audioKey);
                } catch (Exception ttsError) {
                    log.warn("TTS failed for frame {}: {}", index, ttsError.getMessage());
                }
            }

            frame.setStatus("COMPLETED");
            frameRepo.save(frame);
        }

        explain.setStatus("COMPLETED");
        explain.setProgress(100);
        explain.setCurrentStep("完成");
        explainRepo.save(explain);
    }

    private String generateFrameHtml(String topic,
                                     String stylePrompt,
                                     String title,
                                     String narration,
                                     String htmlPrompt,
                                     String previousHtml) {
        String userPrompt = """
                【主题】%s
                【视觉风格】%s
                【当前分镜】
                标题：%s
                旁白：%s
                画面提示：%s
                【上一分镜 HTML（无则填"无"）】
                %s
                
                请输出 JSON：{ "html": "..." }
                """.formatted(
                topic, stylePrompt, title, narration, htmlPrompt,
                previousHtml == null ? "无" : previousHtml.substring(0, Math.min(previousHtml.length(), 2000))
        );
        String raw = aiClient.chat(HTML_SYSTEM, userPrompt, 0.8);
        String html = extractHtml(raw);
        if (html.isBlank()) {
            throw new IllegalStateException("AI 未返回 HTML 内容");
        }
        return sanitizeHtml(html);
    }

    private String extractHtml(String raw) {
        try {
            JsonNode node = parseJson(raw);
            String html = node.path("html").asText("");
            if (!html.isBlank()) {
                return html;
            }
        } catch (Exception ignored) {
            // Some models occasionally put raw HTML into a fenced block or emit
            // JSON-looking text with unescaped HTML. Fall through to HTML scan.
        }
        String value = raw == null ? "" : raw.trim();
        Matcher htmlFence = Pattern.compile("```(?:html)?\\s*([\\s\\S]*?</html>)\\s*```", Pattern.CASE_INSENSITIVE)
                .matcher(value);
        if (htmlFence.find()) {
            return htmlFence.group(1).trim();
        }
        String lower = value.toLowerCase();
        int start = lower.indexOf("<!doctype html");
        if (start < 0) {
            start = lower.indexOf("<html");
        }
        int end = lower.lastIndexOf("</html>");
        if (start >= 0 && end > start) {
            return value.substring(start, end + "</html>".length()).trim();
        }
        return "";
    }

    private String sanitizeHtml(String html) {
        String cleaned = html;
        cleaned = cleaned.replaceAll("(?i)<script[^>]+src=[\"'][^\"']*[\"'][^>]*>\\s*</script>", "");
        cleaned = cleaned.replaceAll("(?i)<link[^>]+rel=[\"']stylesheet[\"'][^>]*>", "");
        cleaned = cleaned.replaceAll("(?i)<img[^>]+src=[\"']https?://[^\"']*[\"'][^>]*>", "");
        cleaned = cleaned.replaceAll("(?i)<base[^>]*>", "");
        return cleaned;
    }

    public List<Map<String, Object>> listByUser(Long userId) {
        return explainRepo.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toSummaryDto)
                .toList();
    }

    public Map<String, Object> detail(Long userId, Long explainId) {
        AnimationExplainEntity explain = requireOwned(explainId, userId);
        Map<String, Object> dto = toSummaryDto(explain);
        List<Map<String, Object>> frames = frameRepo.findAllByExplainIdOrderByFrameIndexAsc(explainId).stream()
                .map(this::toFrameDto)
                .toList();
        dto.put("frames", frames);
        return dto;
    }

    @Transactional
    public void markFailed(Long explainId, String message) {
        explainRepo.findById(explainId).ifPresent(entity -> {
            entity.setStatus("FAILED");
            entity.setErrorMessage(truncate(message, 500));
            entity.setCurrentStep("生成失败");
            explainRepo.save(entity);
        });
    }

    private AnimationExplainEntity requireOwned(Long explainId, Long userId) {
        AnimationExplainEntity entity = explainRepo.findById(explainId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "讲解不存在"));
        if (!entity.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问");
        }
        return entity;
    }

    private Map<String, Object> toSummaryDto(AnimationExplainEntity entity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", entity.getId());
        m.put("topic", entity.getTopic());
        m.put("title", entity.getTitle());
        m.put("style", entity.getStyle());
        m.put("status", entity.getStatus());
        m.put("progress", entity.getProgress());
        m.put("currentStep", entity.getCurrentStep());
        m.put("errorMessage", entity.getErrorMessage());
        m.put("frameCount", entity.getFrameCount());
        m.put("createdAt", entity.getCreatedAt());
        return m;
    }

    private Map<String, Object> toFrameDto(AnimationFrameEntity frame) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", frame.getId());
        m.put("index", frame.getFrameIndex());
        m.put("title", frame.getTitle());
        m.put("narration", frame.getNarration());
        m.put("status", frame.getStatus());
        if (frame.getHtmlObjectKey() != null) {
            m.put("htmlUrl", storageService.getPresignedUrl(frame.getHtmlObjectKey(), 3600));
            try {
                m.put("htmlCode", new String(storageService.getBytes(frame.getHtmlObjectKey()), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // Preview URL is still useful even if inline code cannot be loaded.
            }
        }
        if (frame.getAudioObjectKey() != null) {
            m.put("audioUrl", storageService.getPresignedUrl(frame.getAudioObjectKey(), 3600));
        }
        if (frame.getNarration() != null && !frame.getNarration().isBlank()) {
            // 前端加载音频后用真实时长；此处给预估供初始字幕布局
            double estimated = Math.max(3.0, frame.getNarration().length() / 3.5);
            m.put("estimatedDuration", estimated);
            m.put("subtitles", AnimationSubtitleUtil.toDto(
                    AnimationSubtitleUtil.splitSubtitles(frame.getNarration(), estimated)));
        }
        return m;
    }

    private JsonNode parseJson(String raw) {
        try {
            String trimmed = raw == null ? "" : raw.trim();
            Matcher matcher = JSON_BLOCK.matcher(trimmed);
            if (matcher.find()) {
                trimmed = matcher.group(1).trim();
            }
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                trimmed = trimmed.substring(start, end + 1);
            }
            return objectMapper.readTree(trimmed);
        } catch (Exception e) {
            throw new IllegalStateException("AI 返回非 JSON: " + truncate(raw, 200), e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
