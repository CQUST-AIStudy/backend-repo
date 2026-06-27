package com.tap.backend.service.grading.animation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.grading.EvidenceBlockEntity;
import com.tap.backend.domain.grading.ScoreItemEntity;
import com.tap.backend.service.animation.AnimationAiClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 当评分项没有结构化 annotations 时，从教师评语（comment / final review）中
 * 提取可供动画演示的代码问题。
 *
 * <p>评语往往是自然语言长文本，里面会提到“search 函数左右子树写反了”
 * “AVL 旋转缺少说明”等问题。让 LLM 把它们结构化出来，再映射到证据块上，
 * 就能为这些原本没有动画的问题生成 CODE_HIGHLIGHT。</p>
 */
@Component
public class CommentIssueExtractor {

    private static final Logger log = LoggerFactory.getLogger(CommentIssueExtractor.class);
    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final int MAX_COMMENT_LENGTH = 3000;
    private static final int MAX_EVIDENCE_PREVIEW_LENGTH = 500;

    private static final String SYSTEM_PROMPT = """
            你是一位教学批改助手。请从教师评语中抽取出适合用代码动画演示的问题点。

            ## 输出格式（严格 JSON）
            {
              "issues": [
                {
                  "evidence_id": "ev-1",
                  "anchor_text": "search(T->rchild, key)",
                  "note": "key 小于根节点时应查找左子树，但代码里递归到了右子树",
                  "error_type": "LOGIC_ERROR"
                }
              ]
            }

            ## 提取规则
            - 只提取与代码实现相关的问题（逻辑错误、缺失关键分析、边界错误、指针错误等）
            - anchor_text 尽量是代码中的真实片段，方便后续在证据块里定位
            - note 控制在 60 字以内，说明“问题是什么”和“应该怎么改”
            - evidence_id 从提供的证据块列表中选择最相关的一项；如果不确定，留空字符串
            - error_type 可选：ARRAY_BOUNDS / INVALID_POINTER / INFINITE_LOOP / MEMORY_LEAK / RECURSION / RUNTIME_ERROR / TYPE_ERROR / LOGIC_ERROR / RESULT_MISMATCH / CONCEPT。不确定时留空
            - 如果评语里没有可演示的代码问题，返回 { "issues": [] }
            """;

    private final AnimationAiClient aiClient;
    private final ObjectMapper objectMapper;

    public CommentIssueExtractor(AnimationAiClient aiClient, ObjectMapper objectMapper) {
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 从评分项评语和总评中提取演示问题。
     *
     * @param experimentTitle 实验标题
     * @param scoreItems      评分项列表
     * @param finalReview     总评评语
     * @param evidenceBlocks  所有证据块
     * @return 问题列表（不会为 null）
     */
    public List<CommentIssue> extractIssues(
            String experimentTitle,
            List<ScoreItemEntity> scoreItems,
            String finalReview,
            List<EvidenceBlockEntity> evidenceBlocks) {

        if (aiClient == null || !aiClient.isChatAvailable()) {
            return List.of();
        }

        String userPrompt = buildPrompt(experimentTitle, scoreItems, finalReview, evidenceBlocks);
        try {
            String raw = aiClient.chat(SYSTEM_PROMPT, userPrompt, 0.3);
            return parseResponse(raw);
        } catch (Exception e) {
            log.warn("评语问题提取失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildPrompt(
            String experimentTitle,
            List<ScoreItemEntity> scoreItems,
            String finalReview,
            List<EvidenceBlockEntity> evidenceBlocks) {

        StringBuilder sb = new StringBuilder();
        if (experimentTitle != null && !experimentTitle.isBlank()) {
            sb.append("【实验标题】").append(experimentTitle).append("\n\n");
        }

        if (scoreItems != null) {
            int idx = 1;
            for (ScoreItemEntity item : scoreItems) {
                String comment = item.getComment();
                if (comment != null && !comment.isBlank()) {
                    sb.append("【评分项 ").append(idx).append(" 评语】\n");
                    sb.append(truncate(comment, MAX_COMMENT_LENGTH)).append("\n\n");
                    idx++;
                }
            }
        }

        if (finalReview != null && !finalReview.isBlank()) {
            sb.append("【总评】\n");
            sb.append(truncate(finalReview, MAX_COMMENT_LENGTH)).append("\n\n");
        }

        sb.append("【可选证据块】\n");
        if (evidenceBlocks != null) {
            for (EvidenceBlockEntity block : evidenceBlocks) {
                String id = block.getEvidenceId();
                String content = block.getContent();
                if (id == null) {
                    continue;
                }
                sb.append("- ").append(id).append(": ");
                sb.append(truncate(content == null ? "" : content, MAX_EVIDENCE_PREVIEW_LENGTH)).append("\n");
            }
        }
        sb.append("\n请输出 JSON：{ \"issues\": [...] }");
        return sb.toString();
    }

    private List<CommentIssue> parseResponse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<CommentIssue> result = new ArrayList<>();
        try {
            String json = extractJsonBlock(raw);
            JsonNode root = objectMapper.readTree(json);
            JsonNode issues = root.path("issues");
            if (issues.isArray()) {
                for (JsonNode node : issues) {
                    String evidenceId = text(node, "evidence_id");
                    String anchor = text(node, "anchor_text");
                    String note = text(node, "note");
                    String errorType = text(node, "error_type");
                    if (note.isBlank() && anchor.isBlank()) {
                        continue;
                    }
                    result.add(new CommentIssue(evidenceId, anchor, note, errorType));
                }
            }
        } catch (Exception e) {
            log.debug("评语问题 JSON 解析失败: {}", e.getMessage());
        }
        return result;
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

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "…";
    }

    public record CommentIssue(
            String evidenceId,
            String anchorText,
            String note,
            String errorTypeHint
    ) {
    }
}
