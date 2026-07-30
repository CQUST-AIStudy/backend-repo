package com.tap.backend.service.grading.animation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.grading.EvidenceBlockEntity;
import com.tap.backend.service.animation.AnimationAiClient;
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
 * 使用大模型从批改证据块中提取每道题的完整代码。
 *
 * <p>实验报告里的代码常常被 OCR、分页、证据切片拆成多段，规则提取只能拿到碎片。
 * 让 LLM 根据 evidence_id 聚合并补全，能返回更接近学生原文的完整函数/代码块。</p>
 */
@Component
public class LLMCodeExtractor {

    private static final Logger log = LoggerFactory.getLogger(LLMCodeExtractor.class);
    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private static final String SYSTEM_PROMPT = """
            你是一位代码提取助手。请根据学生实验报告的批改证据块，提取每道题对应的完整代码片段。

            ## 输出格式（严格 JSON）
            {
              "evidence_code": {
                "evidence_id_1": "完整代码片段",
                "evidence_id_2": "完整代码片段"
              }
            }

            ## 提取规则
            - 只输出代码，不要解释、不要 markdown 代码块
            - 如果某个 evidence_id 对应的内容不是代码或无法提取，对应的值留空字符串 ""
            - 尽量保留完整的函数、结构体、主函数，不要只截取一行
            - 代码保持原有缩进和换行
            - 如果多个 evidence 属于同一道题，请把它们合并成一段完整代码，并分别填入相关 evidence_id

            ## 语言保持（重要）
            - 先判断每段代码的语言，并【保持学生原始语言】：Python 必须保持 Python，绝不改写成 C 或其它语言；C 保持 C。

            ## 可运行性要求（按语言）
            - C：可补齐缺失的 #include、结构体/宏定义；若缺 main 可补一个最小 main（构造小规模测试数据、调用出错函数，能触发批注指出的错误最佳），补充行用 /* 补全 */ 标注，不要用交互式输入
            - Python：保持可运行的最小片段，仅补齐必要的 import；不得改写成 C，不得虚构与学生无关的逻辑
            - 任何语言都不要修改学生原有代码（包括不修复其中的错误）
            """;

    private final AnimationAiClient aiClient;
    private final ObjectMapper objectMapper;

    public LLMCodeExtractor(AnimationAiClient aiClient, ObjectMapper objectMapper) {
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 从证据块中提取完整代码。
     *
     * @param experimentTitle 实验标题，用于帮助模型判断题目边界
     * @param blocks          与当前提交相关的证据块（建议只传入被引用的证据块）
     * @return Map<evidenceId, fullCode>
     */
    public Map<String, String> extractFullCode(String experimentTitle, List<EvidenceBlockEntity> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return Map.of();
        }
        if (aiClient == null || !aiClient.isChatAvailable()) {
            log.debug("LLM 代码提取不可用，跳过");
            return Map.of();
        }

        String userPrompt = buildPrompt(experimentTitle, blocks);
        try {
            String raw = aiClient.chat(SYSTEM_PROMPT, userPrompt, 0.3);
            return parseResponse(raw);
        } catch (Exception e) {
            log.warn("LLM 代码提取失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private String buildPrompt(String experimentTitle, List<EvidenceBlockEntity> blocks) {
        StringBuilder sb = new StringBuilder();
        if (experimentTitle != null && !experimentTitle.isBlank()) {
            sb.append("【实验标题】").append(experimentTitle).append("\n\n");
        }
        sb.append("【证据块列表】\n");
        for (EvidenceBlockEntity block : blocks) {
            String id = block.getEvidenceId();
            String content = block.getContent();
            if (id == null) {
                continue;
            }
            sb.append("--- evidence_id: ").append(id).append(" ---\n");
            sb.append(content == null ? "" : content).append("\n\n");
        }
        sb.append("请输出 JSON：{ \"evidence_code\": { \"evidence_id\": \"完整代码\" } }");
        return sb.toString();
    }

    private Map<String, String> parseResponse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            String json = extractJsonBlock(raw);
            JsonNode root = objectMapper.readTree(json);
            JsonNode codeNode = root.path("evidence_code");
            Map<String, String> result = new LinkedHashMap<>();
            if (codeNode.isObject()) {
                codeNode.fields().forEachRemaining(entry -> {
                    String value = entry.getValue().asText("").trim();
                    // 去掉可能被模型包上的 markdown 代码块标记
                    value = stripMarkdownCodeBlock(value);
                    result.put(entry.getKey(), value);
                });
            }
            return result;
        } catch (Exception e) {
            log.debug("LLM 代码提取 JSON 解析失败: {}", e.getMessage());
            return Map.of();
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

    private String stripMarkdownCodeBlock(String value) {
        if (value.startsWith("```")) {
            int firstNewline = value.indexOf('\n');
            if (firstNewline > 0) {
                value = value.substring(firstNewline + 1);
            }
            if (value.endsWith("```")) {
                value = value.substring(0, value.length() - 3).trim();
            }
        }
        return value;
    }
}
