package com.tap.backend.service.grading.animation;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 生成 HTML 动画工作流：适合概念/原理类错误。
 * 复用 AnimationExplainService 的能力，但同步返回一个简化版 HTML。
 */
@Component
public class HtmlAnimationWorkflow {

    public AnimationResult generate(AnimationCandidate candidate, int index) {
        String topic = buildTopic(candidate);
        String html = generateSimpleHtml(topic, candidate.note(), candidate.anchor());

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
        if (combined.contains("复杂度")) return "时间复杂度分析";
        if (combined.contains("原理")) return "算法原理";
        if (combined.contains("内存")) return "内存管理";
        return "核心概念";
    }

    private String generateSimpleHtml(String topic, String note, String anchor) {
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
