package com.tap.backend.service.grading.animation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.service.animation.AnimationAiClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 代码高亮 + 弹窗动画工作流。
 * 让大模型根据错误上下文生成：
 * 1) errorRanges：代码中需要红色标注的行号区间；
 * 2) popupHtml：一段独立的 D3.js 动画 HTML，点击标注后弹出播放。
 */
@Component
public class CodeHighlightAnimationWorkflow {

    private static final Logger log = LoggerFactory.getLogger(CodeHighlightAnimationWorkflow.class);
    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private static final String SYSTEM_PROMPT = """
            你是一位编程教学动画工程师。请根据学生的代码错误，生成一段可独立运行在 iframe 弹窗中的 D3.js 动画。

            ## 输出格式（严格 JSON）
            {
              "errorRanges": [
                {"startLine": 5, "endLine": 5, "label": "错误简短描述"}
              ],
              "popupHtml": "完整 HTML 文档字符串"
            }

            ## errorRanges 要求
            - startLine / endLine 使用 1-based 行号，闭区间
            - 只标注真正出错的代码行，不要标注整段无关代码
            - label 控制在 8 个字以内

            ## popupHtml 要求
            - 必须是完整可独立运行的 HTML（含 <!DOCTYPE html>），内联 CSS
            - 必须引入 D3.js v7：<script src="https://cdn.jsdelivr.net/npm/d3@7"></script>
            - 宽度 100%%，高度 100%%，不要出现横向滚动条
            - 动画要动态：节点高亮、箭头/路径移动、状态变化、步骤说明
            - 包含「步骤说明」区域和「重播」按钮
            - 主色：错误 #dc2626，正确 #16a34a，强调 #2563eb
            - 不要自动播放声音，不要 alert/prompt/confirm，不要依赖外部图片/字体
            """;

    private final AnimationAiClient aiClient;
    private final ObjectMapper objectMapper;

    public CodeHighlightAnimationWorkflow(AnimationAiClient aiClient, ObjectMapper objectMapper) {
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    public AnimationResult generate(AnimationCandidate candidate, int index) {
        CodeContext ctx = candidate.codeContext();
        String sourceCode = ctx == null ? candidate.anchor() : ctx.fullCode();
        int anchorLine = ctx == null ? 1 : ctx.relativeAnchorLine();

        GeneratedOutput output = generateWithAi(candidate, sourceCode, anchorLine);
        List<Map<String, Object>> errorRanges = output.errorRanges.isEmpty()
                ? defaultErrorRanges(ctx, candidate.anchor())
                : output.errorRanges;
        String popupHtml = output.popupHtml.isBlank()
                ? fallbackPopupHtml(candidate, sourceCode, anchorLine)
                : output.popupHtml;

        return new AnimationResult(
                AnimationWorkflow.CODE_HIGHLIGHT.name(),
                buildTitle(candidate),
                candidate.note(),
                List.of(),
                Map.of(
                        "errorType", candidate.detectedErrorType() == null ? "CODE_ERROR" : candidate.detectedErrorType().name(),
                        "sourceCode", sourceCode,
                        "correctedCode", candidate.note(),
                        "errorLine", anchorLine,
                        "errorRanges", errorRanges,
                        "popupHtml", popupHtml
                )
        );
    }

    private GeneratedOutput generateWithAi(AnimationCandidate candidate, String sourceCode, int anchorLine) {
        String userPrompt = buildUserPrompt(candidate, sourceCode, anchorLine);
        try {
            String raw = aiClient.chat(SYSTEM_PROMPT, userPrompt, 0.7);
            return extractOutput(raw);
        } catch (Exception e) {
            log.warn("CODE_HIGHLIGHT AI 生成失败，回退兜底: {}", e.getMessage());
            return GeneratedOutput.empty();
        }
    }

    private String buildUserPrompt(AnimationCandidate candidate, String sourceCode, int anchorLine) {
        ProblemContext problem = candidate.problemContext();
        StringBuilder sb = new StringBuilder();
        sb.append("【错误类型】").append(candidate.detectedErrorType()).append("\n");
        if (problem != null && problem.experimentTitle() != null) {
            sb.append("【实验标题】").append(problem.experimentTitle()).append("\n");
        }
        sb.append("【教师批注】\n").append(candidate.note()).append("\n\n");
        sb.append("【完整代码片段】\n").append(sourceCode).append("\n\n");
        sb.append("【错误所在行号（1-based）】").append(anchorLine).append("\n");
        sb.append("【学生出错位置原文】").append(candidate.anchor()).append("\n\n");
        sb.append("请输出 JSON：{ \"errorRanges\": [...], \"popupHtml\": \"...\" }");
        return sb.toString();
    }

    private GeneratedOutput extractOutput(String raw) {
        if (raw == null || raw.isBlank()) {
            return GeneratedOutput.empty();
        }
        try {
            String json = extractJsonBlock(raw);
            JsonNode node = objectMapper.readTree(json);
            List<Map<String, Object>> ranges = parseErrorRanges(node.path("errorRanges"));
            String html = extractPopupHtml(node.path("popupHtml").asText(""));
            return new GeneratedOutput(ranges, html);
        } catch (Exception e) {
            log.debug("CODE_HIGHLIGHT JSON 解析失败: {}", e.getMessage());
            return GeneratedOutput.empty();
        }
    }

    private String extractJsonBlock(String raw) {
        String trimmed = raw.trim();
        Matcher matcher = JSON_BLOCK.matcher(trimmed);
        if (matcher.find()) {
            trimmed = matcher.group(1).trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            trimmed = trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private List<Map<String, Object>> parseErrorRanges(JsonNode rangesNode) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!rangesNode.isArray()) {
            return result;
        }
        for (JsonNode node : rangesNode) {
            Map<String, Object> range = new LinkedHashMap<>();
            range.put("startLine", Math.max(1, node.path("startLine").asInt(1)));
            range.put("endLine", Math.max(1, node.path("endLine").asInt(1)));
            range.put("label", node.path("label").asText("错误位置"));
            result.add(range);
        }
        return result;
    }

    private String extractPopupHtml(String rawHtml) {
        if (rawHtml == null || rawHtml.isBlank()) {
            return "";
        }
        String html = rawHtml.trim();
        // 允许 D3 CDN，但去掉其它外部资源
        html = html.replaceAll("(?i)<link[^>]+rel=[\"']stylesheet[\"'][^>]*>", "");
        html = html.replaceAll("(?i)<img[^>]+src=[\"']https?://[^\"']*[\"'][^>]*>", "");
        html = html.replaceAll("(?i)<base[^>]*>", "");
        // 保留 D3 script
        return html;
    }

    private List<Map<String, Object>> defaultErrorRanges(CodeContext ctx, String anchor) {
        List<Map<String, Object>> ranges = new ArrayList<>();
        int start = ctx == null ? 1 : ctx.highlightStartLine();
        int end = ctx == null ? 1 : ctx.highlightEndLine();
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("startLine", start);
        range.put("endLine", end);
        range.put("label", "错误位置");
        ranges.add(range);
        return ranges;
    }

    private String fallbackPopupHtml(AnimationCandidate candidate, String sourceCode, int anchorLine) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="UTF-8">
                  <script src="https://cdn.jsdelivr.net/npm/d3@7"></script>
                  <style>
                    body { font-family: system-ui, sans-serif; margin: 0; padding: 24px; background: #f8fafc; }
                    h2 { font-size: 16px; margin: 0 0 12px; color: #1f2937; }
                    .stage { width: 100%%; height: 220px; background: #fff; border: 1px solid #e5e7eb; border-radius: 12px; }
                    .note { margin-top: 12px; color: #4b5563; font-size: 14px; }
                  </style>
                </head>
                <body>
                  <h2>%s</h2>
                  <div class="stage" id="stage"></div>
                  <div class="note">%s</div>
                  <script>
                    const svg = d3.select("#stage").append("svg").attr("width", "100%%").attr("height", "100%%");
                    svg.append("text").attr("x", "50%%").attr("y", "50%%").attr("text-anchor", "middle")
                       .attr("fill", "#dc2626").attr("font-size", 16).attr("font-weight", 700)
                       .text("错误位置：第 %d 行");
                  </script>
                </body>
                </html>
                """.formatted(escapeHtml(buildTitle(candidate)), escapeHtml(candidate.note()), anchorLine);
    }

    private String buildTitle(AnimationCandidate candidate) {
        ErrorPatternDetector.ErrorType type = candidate.detectedErrorType();
        String name = type == null ? "代码错误" : switch (type) {
            case ARRAY_BOUNDS -> "数组越界";
            case INVALID_POINTER -> "指针错误";
            case INFINITE_LOOP -> "死循环";
            case MEMORY_LEAK -> "内存泄漏";
            case RECURSION -> "递归错误";
            case RUNTIME_ERROR -> "运行时错误";
            case TYPE_ERROR -> "类型错误";
            case LOGIC_ERROR -> "逻辑错误";
            default -> "代码错误";
        };
        return name + "演示";
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private record GeneratedOutput(List<Map<String, Object>> errorRanges, String popupHtml) {
        static GeneratedOutput empty() {
            return new GeneratedOutput(List.of(), "");
        }
    }
}
