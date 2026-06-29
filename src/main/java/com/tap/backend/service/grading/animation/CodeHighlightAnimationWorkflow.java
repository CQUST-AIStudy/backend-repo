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
 * 2) correctedCode：修正后的完整代码片段；
 * 3) popupHtml：一段独立的 D3.js 动画 HTML，点击标注后弹出播放。
 */
@Component
public class CodeHighlightAnimationWorkflow {

    private static final Logger log = LoggerFactory.getLogger(CodeHighlightAnimationWorkflow.class);
    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private static final String SYSTEM_PROMPT = """
            你是一位编程教学动画工程师。请根据学生的代码错误，生成错误标注、修正代码和动画。

            ## 输出格式（严格 JSON）
            {
              "errorRanges": [
                {"startLine": 5, "endLine": 5, "label": "错误简短描述"}
              ],
              "correctedCode": "修正后的完整代码片段（纯文本，不要 markdown 标记）",
              "explanation": "用一句话解释为什么这段代码是错的，以及修正后的行为",
              "popupHtml": "完整 HTML 文档字符串"
            }

            ## errorRanges 要求
            - startLine / endLine 使用 1-based 行号，闭区间
            - 只标注真正出错的代码行（通常是 1-3 行），绝不要标注整段代码
            - label 控制在 8 个字以内

            ## correctedCode 要求
            - 提供修正后的完整代码片段（包含上下文，不要只写改了的那一行）
            - 用注释 // 修正标记改动的行
            - 纯文本格式，不要用 markdown 代码块包裹

            ## explanation 要求
            - 一句话说明错误原因和修正后的效果
            - 不要复述教师批注，要用自己的话解释

            ## popupHtml 要求
            - 必须是完整可独立运行的 HTML（含 <!DOCTYPE html>），内联 CSS
            - 必须引入 D3.js v7：<script src="https://cdn.jsdelivr.net/npm/d3@7"></script>
            - 宽度 100%%，高度 100%%，不要出现横向滚动条
            - 动画要动态：节点高亮、箭头/路径移动、状态变化、步骤说明
            - 必须包含「步骤说明」区域和「重播」按钮
            - 左侧显示错误代码（红色高亮出错行），右侧显示修正后的代码（绿色高亮修正行）
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
                ? defaultErrorRanges(anchorLine, ctx)
                : output.errorRanges;
        String popupHtml = output.popupHtml.isBlank()
                ? fallbackPopupHtml(candidate, sourceCode, anchorLine, output.correctedCode, output.explanation)
                : output.popupHtml;

        // Use AI-generated correctedCode and explanation if available, otherwise fall back
        String correctedCode = output.correctedCode.isBlank()
                ? buildFallbackCorrectedCode(candidate, sourceCode, anchorLine)
                : output.correctedCode;
        String explanation = output.explanation.isBlank()
                ? candidate.note()
                : output.explanation;

        return new AnimationResult(
                AnimationWorkflow.CODE_HIGHLIGHT.name(),
                buildTitle(candidate),
                explanation,
                List.of(),
                Map.of(
                        "errorType", candidate.detectedErrorType() == null ? "CODE_ERROR" : candidate.detectedErrorType().name(),
                        "sourceCode", sourceCode,
                        "correctedCode", correctedCode,
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
        sb.append("请输出 JSON：{ \"errorRanges\": [...], \"correctedCode\": \"...\", \"explanation\": \"...\", \"popupHtml\": \"...\" }");
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
            String correctedCode = node.path("correctedCode").asText("").trim();
            String explanation = node.path("explanation").asText("").trim();
            return new GeneratedOutput(ranges, html, correctedCode, explanation);
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

    /**
     * Fallback: only highlight a narrow range (anchor line ± 2) instead of the entire file.
     */
    private List<Map<String, Object>> defaultErrorRanges(int anchorLine, CodeContext ctx) {
        List<Map<String, Object>> ranges = new ArrayList<>();
        int totalLines = ctx == null ? anchorLine : ctx.fullLines().size();
        int start = Math.max(1, anchorLine - 1);
        int end = Math.min(totalLines, anchorLine + 1);
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("startLine", start);
        range.put("endLine", end);
        range.put("label", "错误位置");
        ranges.add(range);
        return ranges;
    }

    /**
     * Build a simple corrected code fallback: show the teacher's note as a comment
     * appended after the error line.
     */
    private String buildFallbackCorrectedCode(AnimationCandidate candidate, String sourceCode, int anchorLine) {
        String note = candidate.note();
        if (note == null || note.isBlank()) {
            return sourceCode;
        }
        String[] lines = sourceCode.split("\n");
        if (anchorLine < 1 || anchorLine > lines.length) {
            return sourceCode;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i]);
            if (i + 1 == anchorLine) {
                sb.append("  // ⚠ ").append(note);
            }
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Fallback popup: show a side-by-side code diff (wrong vs corrected) with
     * the error line highlighted in red and the correction in green.
     */
    private String fallbackPopupHtml(AnimationCandidate candidate, String sourceCode, int anchorLine, String correctedCode, String explanation) {
        String errorLineText = "";
        String correctedLineText = "";
        String[] srcLines = sourceCode.split("\n");
        if (anchorLine >= 1 && anchorLine <= srcLines.length) {
            errorLineText = escapeHtml(srcLines[anchorLine - 1]);
        }
        if (correctedCode != null && !correctedCode.isBlank()) {
            String[] correctedLines = correctedCode.split("\n");
            if (anchorLine >= 1 && anchorLine <= correctedLines.length) {
                correctedLineText = escapeHtml(correctedLines[anchorLine - 1]);
            }
        }
        String noteEscaped = escapeHtml(explanation != null && !explanation.isBlank() ? explanation : candidate.note());
        String titleEscaped = escapeHtml(buildTitle(candidate));

        return """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="UTF-8">
                  <script src="https://cdn.jsdelivr.net/npm/d3@7"></script>
                  <style>
                    * { box-sizing: border-box; }
                    body { font-family: system-ui, -apple-body, sans-serif; margin: 0; padding: 20px; background: #f8fafc; }
                    h2 { font-size: 16px; margin: 0 0 16px; color: #1f2937; }
                    .diff-container { display: flex; gap: 12px; }
                    .diff-panel { flex: 1; border-radius: 10px; overflow: hidden; }
                    .diff-panel.wrong { border: 2px solid #fecaca; }
                    .diff-panel.right { border: 2px solid #bbf7d0; }
                    .diff-header { padding: 8px 14px; font-size: 13px; font-weight: 700; }
                    .diff-panel.wrong .diff-header { background: #fef2f2; color: #dc2626; }
                    .diff-panel.right .diff-header { background: #f0fdf4; color: #16a34a; }
                    .diff-body { background: #fff; padding: 12px 14px; font-family: 'Fira Code', 'Cascadia Code', monospace; font-size: 13px; line-height: 1.8; }
                    .code-line { display: flex; gap: 8px; padding: 2px 0; }
                    .line-num { color: #94a3b8; width: 24px; text-align: right; flex-shrink: 0; }
                    .line-content { white-space: pre-wrap; word-break: break-all; }
                    .highlight-error { background: #fef2f2; border-left: 3px solid #ef4444; padding-left: 6px; margin-left: -6px; }
                    .highlight-fix { background: #f0fdf4; border-left: 3px solid #22c55e; padding-left: 6px; margin-left: -6px; }
                    .note-box { margin-top: 16px; padding: 12px 16px; border-radius: 10px; background: #fffbeb; border: 1px solid #fde68a; color: #92400e; font-size: 14px; line-height: 1.6; }
                    .step-bar { margin-top: 16px; display: flex; align-items: center; gap: 8px; }
                    .step-dot { width: 10px; height: 10px; border-radius: 50%; background: #cbd5e1; transition: all 0.3s; }
                    .step-dot.active { background: #2563eb; transform: scale(1.3); }
                    .step-label { font-size: 13px; color: #475569; }
                    .replay-btn { margin-top: 12px; padding: 8px 20px; border: none; border-radius: 8px; background: #2563eb; color: #fff; font-size: 14px; cursor: pointer; }
                    .replay-btn:hover { background: #1d4ed8; }
                  </style>
                </head>
                <body>
                  <h2>%s</h2>
                  <div class="diff-container">
                    <div class="diff-panel wrong">
                      <div class="diff-header">✗ 错误代码</div>
                      <div class="diff-body">
                        <div class="code-line highlight-error">
                          <span class="line-num">%d</span>
                          <span class="line-content">%s</span>
                        </div>
                      </div>
                    </div>
                    <div class="diff-panel right">
                      <div class="diff-header">✓ 修正写法</div>
                      <div class="diff-body">
                        <div class="code-line highlight-fix">
                          <span class="line-num">%d</span>
                          <span class="line-content">%s</span>
                        </div>
                      </div>
                    </div>
                  </div>
                  <div class="note-box">%s</div>
                  <div class="step-bar">
                    <div class="step-dot active" id="dot1"></div>
                    <div class="step-dot" id="dot2"></div>
                    <span class="step-label" id="stepLabel">第 1 步：定位错误行</span>
                  </div>
                  <button class="replay-btn" onclick="replay()">重播</button>
                  <script>
                    let step = 0;
                    const steps = [
                      { label: "第 1 步：定位错误行", dot: "dot1" },
                      { label: "第 2 步：查看修正方案", dot: "dot2" }
                    ];
                    function showStep(i) {
                      step = i;
                      steps.forEach((s, idx) => {
                        const dot = document.getElementById(s.dot);
                        if (dot) dot.classList.toggle("active", idx === i);
                      });
                      const label = document.getElementById("stepLabel");
                      if (label) label.textContent = s_label(i);
                    }
                    function s_label(i) { return steps[i] ? steps[i].label : ""; }
                    function replay() {
                      showStep(0);
                      setTimeout(() => showStep(1), 1500);
                    }
                    // auto-play
                    setTimeout(() => showStep(1), 1500);
                  </script>
                </body>
                </html>
                """.formatted(titleEscaped, anchorLine, errorLineText, anchorLine, correctedLineText, noteEscaped);
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

    private record GeneratedOutput(
            List<Map<String, Object>> errorRanges,
            String popupHtml,
            String correctedCode,
            String explanation
    ) {
        static GeneratedOutput empty() {
            return new GeneratedOutput(List.of(), "", "", "");
        }
    }
}
