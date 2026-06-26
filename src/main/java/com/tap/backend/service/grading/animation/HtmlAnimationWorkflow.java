package com.tap.backend.service.grading.animation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.service.animation.AnimationAiClient;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AI 生成 HTML 动画工作流：适合概念/原理类错误。
 * 同步调用 AI 生成一段可独立运行的 HTML 动画，避免进入异步 pipeline。
 */
@Component
public class HtmlAnimationWorkflow {

    private static final Logger log = LoggerFactory.getLogger(HtmlAnimationWorkflow.class);
    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_BLOCK = Pattern.compile("```(?:html)?\\s*([\\s\\S]*?</html>)\\s*```", Pattern.CASE_INSENSITIVE);

    private static final String SYSTEM_PROMPT = """
            你是一位教学动画设计师。请根据学生的错题上下文，生成一段可独立运行的 HTML 动画，帮助学生理解错误原因。
            
            ## 输出要求
            - 严格 JSON：{ "html": "完整 HTML 文档字符串" }
            - HTML 完整可独立运行（含 <!DOCTYPE html>），内联 CSS/JS，可使用 SVG/Canvas
            - 宽度 100%%，高度自适应，主色对比明显，文字清晰
            - 动画简洁，4-8 秒，不要自动播放声音，不要依赖外部 CDN/字体/图片
            - 不要出现 alert/prompt/confirm
            """;

    private final AnimationAiClient aiClient;
    private final ObjectMapper objectMapper;

    public HtmlAnimationWorkflow(AnimationAiClient aiClient, ObjectMapper objectMapper) {
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    public AnimationResult generate(AnimationCandidate candidate, int index) {
        String topic = buildTopic(candidate);
        String html = null;
        if (aiClient.isChatAvailable()) {
            try {
                html = generateWithAi(candidate, topic);
            } catch (Exception e) {
                log.warn("概念动画 AI 生成失败，回退静态卡片: {}", e.getMessage());
            }
        }
        if (html == null || html.isBlank()) {
            html = generateStaticHtml(topic, candidate.note(), candidate.anchor());
        }

        Map<String, Object> frame = Map.of(
                "order", 1,
                "type", "html",
                "title", topic,
                "narration", candidate.note(),
                "html", html
        );

        return new AnimationResult(
                AnimationWorkflow.HTML_ANIMATION.name(),
                topic,
                candidate.note(),
                List.of(frame),
                Map.of(
                        "errorType", "CONCEPT",
                        "topic", topic,
                        "concept", extractConcept(candidate)
                )
        );
    }

    private String generateWithAi(AnimationCandidate candidate, String topic) {
        String userPrompt = buildUserPrompt(candidate, topic);
        String raw = aiClient.chat(SYSTEM_PROMPT, userPrompt, 0.7);
        return extractHtml(raw);
    }

    private String buildUserPrompt(AnimationCandidate candidate, String topic) {
        ProblemContext problem = candidate.problemContext();
        StringBuilder sb = new StringBuilder();
        sb.append("【主题】").append(topic).append("\n");
        if (problem != null && problem.experimentTitle() != null) {
            sb.append("【实验标题】").append(problem.experimentTitle()).append("\n");
        }
        if (problem != null && problem.experimentRequirements() != null && !problem.experimentRequirements().isEmpty()) {
            sb.append("【实验要求】\n").append(problem.experimentRequirements()).append("\n");
        }
        sb.append("【学生错误代码/位置】\n").append(candidate.anchor()).append("\n");
        sb.append("【教师批注】\n").append(candidate.note()).append("\n");
        sb.append("\n请输出 JSON：{ \"html\": \"...\" }");
        return sb.toString();
    }

    private String extractHtml(String raw) {
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
            JsonNode node = objectMapper.readTree(trimmed);
            String html = node.path("html").asText("");
            if (!html.isBlank()) {
                return sanitizeHtml(html);
            }
        } catch (Exception e) {
            log.debug("尝试 JSON 解析 HTML 失败，回退 HTML 扫描");
        }

        String value = raw == null ? "" : raw.trim();
        Matcher htmlFence = HTML_BLOCK.matcher(value);
        if (htmlFence.find()) {
            return sanitizeHtml(htmlFence.group(1).trim());
        }
        String lower = value.toLowerCase(Locale.ROOT);
        int s = lower.indexOf("<!doctype html");
        if (s < 0) {
            s = lower.indexOf("<html");
        }
        int e = lower.lastIndexOf("</html>");
        if (s >= 0 && e > s) {
            return sanitizeHtml(value.substring(s, e + "</html>".length()).trim());
        }
        return "";
    }

    private String sanitizeHtml(String html) {
        if (html == null) return "";
        String cleaned = html;
        cleaned = cleaned.replaceAll("(?i)<script[^>]+src=[\"'][^\"']*[\"'][^>]*>\\s*</script>", "");
        cleaned = cleaned.replaceAll("(?i)<link[^>]+rel=[\"']stylesheet[\"'][^>]*>", "");
        cleaned = cleaned.replaceAll("(?i)<img[^>]+src=[\"']https?://[^\"']*[\"'][^>]*>", "");
        cleaned = cleaned.replaceAll("(?i)<base[^>]*>", "");
        return cleaned;
    }

    private String buildTopic(AnimationCandidate candidate) {
        String note = candidate.note();
        String problemTitle = candidate.problemContext() != null
                ? candidate.problemContext().experimentTitle()
                : "";
        String concept = extractConcept(candidate);

        if (!problemTitle.isBlank() && !concept.isBlank()) {
            return problemTitle + "：" + concept;
        }
        if (!concept.isBlank()) {
            return concept;
        }
        if (!problemTitle.isBlank()) {
            return problemTitle + "：概念讲解";
        }
        return "知识点讲解";
    }

    private String extractConcept(AnimationCandidate candidate) {
        String combined = (candidate.anchor() + " " + candidate.note()).toLowerCase(Locale.ROOT);
        if (combined.contains("递归")) return "递归执行过程";
        if (combined.contains("指针")) return "指针与内存地址";
        if (combined.contains("链表")) return "链表结构";
        if (combined.contains("树")) return "树形结构";
        if (combined.contains("图")) return "图结构";
        if (combined.contains("复杂度")) return "时间复杂度分析";
        if (combined.contains("原理")) return "算法原理";
        if (combined.contains("内存")) return "内存管理";
        return "核心概念";
    }

    private String generateStaticHtml(String topic, String note, String anchor) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="UTF-8">
                  <style>
                    body { font-family: system-ui, sans-serif; padding: 24px; background: #f8f9fa; }
                    .card { background: white; border-radius: 12px; padding: 24px; box-shadow: 0 2px 8px rgba(0,0,0,0.08); }
                    h2 { color: #1a1a1a; margin-top: 0; }
                    .concept { color: #4a4a4a; line-height: 1.6; margin: 16px 0; }
                    .anchor { background: #fff3cd; padding: 12px; border-left: 4px solid #ffc107; border-radius: 4px; font-family: monospace; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h2>%s</h2>
                    <div class="concept">%s</div>
                    <div class="anchor">%s</div>
                  </div>
                </body>
                </html>
                """.formatted(escapeHtml(topic), escapeHtml(note), escapeHtml(anchor));
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
