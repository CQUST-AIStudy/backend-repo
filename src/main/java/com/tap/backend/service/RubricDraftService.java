package com.tap.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.ai.AiProperties;
import com.tap.backend.infra.text.FileTextExtractor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

@Service
public class RubricDraftService {
    private static final int MAX_TEMPLATE_TEXT_CHARS = 12000;
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(?:[一二三四五六七八九十]+[、.．\\)]?|\\d+[、.．\\)]?)\\s*(.+)$");

    private final ObjectMapper objectMapper;
    private final FileTextExtractor fileTextExtractor;
    private final AiProperties aiProperties;

    public RubricDraftService(ObjectMapper objectMapper,
                              FileTextExtractor fileTextExtractor,
                              AiProperties aiProperties) {
        this.objectMapper = objectMapper;
        this.fileTextExtractor = fileTextExtractor;
        this.aiProperties = aiProperties;
    }

    public DraftRubric generateDraft(MultipartFile file, String subjectHint, String nameHint) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Template file is required");
        }

        String filename = file.getOriginalFilename();
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read uploaded template", e);
        }

        String extracted = fileTextExtractor.extract(filename, file.getContentType(), bytes);
        if (extracted == null || extracted.isBlank()) {
            throw new IllegalArgumentException("Template text could not be extracted");
        }

        String normalized = normalizeText(extracted);
        Map<String, String> sections = extractSections(normalized);
        String focusedContext = buildFocusedContext(filename, subjectHint, nameHint, normalized, sections);

        DraftRubric aiDraft = tryGenerateByAi(focusedContext, subjectHint, nameHint);
        if (aiDraft != null) {
            return normalizeDraft(aiDraft, filename, subjectHint, nameHint, sections);
        }
        return heuristicDraft(filename, subjectHint, nameHint, sections);
    }

    private DraftRubric tryGenerateByAi(String focusedContext, String subjectHint, String nameHint) {
        ProviderConfig config = resolveProvider();
        if (config == null) {
            return null;
        }

        try {
            RestClient client = RestClient.builder()
                    .baseUrl(config.baseUrl())
                    .defaultHeader("Authorization", "Bearer " + config.apiKey())
                    .build();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.model());
            body.put("temperature", 0.2);
            body.put("max_tokens", 1600);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", "You design grading rubrics for lab reports. Return JSON only."),
                    Map.of("role", "user", "content", buildDraftPrompt(focusedContext, subjectHint, nameHint))
            ));

            String raw = client.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(raw);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                return null;
            }
            return parseDraft(content);
        } catch (Exception e) {
            return null;
        }
    }

    private DraftRubric parseDraft(String content) throws Exception {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstLf = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstLf >= 0 && lastFence > firstLf) {
                trimmed = trimmed.substring(firstLf + 1, lastFence).trim();
            }
        }

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            trimmed = trimmed.substring(start, end + 1);
        }

        JsonNode node = objectMapper.readTree(trimmed);
        List<DraftDimension> dimensions = new ArrayList<>();
        JsonNode dimsNode = node.path("dimensions");
        if (dimsNode.isArray()) {
            for (JsonNode dimNode : dimsNode) {
                String name = dimNode.path("name").asText("").trim();
                if (name.isBlank()) {
                    continue;
                }
                String description = dimNode.path("description").asText("").trim();
                BigDecimal maxScore = decimalOrDefault(dimNode.path("maxScore").asText(null), BigDecimal.TEN);
                Integer weight = intOrDefault(dimNode.path("weight").asText(null), 0);
                dimensions.add(new DraftDimension(name, description, maxScore, weight));
            }
        }

        return new DraftRubric(
                node.path("name").asText("").trim(),
                node.path("subject").asText("").trim(),
                node.path("description").asText("").trim(),
                node.path("customPrompt").asText("").trim(),
                dimensions
        );
    }

    private DraftRubric normalizeDraft(DraftRubric draft,
                                       String filename,
                                       String subjectHint,
                                       String nameHint,
                                       Map<String, String> sections) {
        String name = firstNonBlank(draft.name(), nameHint, inferNameFromFilename(filename), "实验报告评分标准");
        String subject = firstNonBlank(draft.subject(), subjectHint, inferSubject(sections), "实验课程");
        String description = firstNonBlank(
                draft.description(),
                buildDescriptionFromSections(sections),
                "根据教师上传的空白实验模板自动生成的评分标准草案。");
        String customPrompt = firstNonBlank(
                draft.customPrompt(),
                buildCustomPromptFromSections(sections),
                "评分时优先关注任务完成度、结果分析质量与报告规范性；证据不足时应明确指出缺失点。");

        List<DraftDimension> normalized = normalizeDimensions(draft.dimensions());
        if (normalized.isEmpty()) {
            normalized = heuristicDimensions(sections);
        }
        return new DraftRubric(name, subject, description, customPrompt, normalized);
    }

    private DraftRubric heuristicDraft(String filename,
                                       String subjectHint,
                                       String nameHint,
                                       Map<String, String> sections) {
        return new DraftRubric(
                firstNonBlank(nameHint, inferNameFromFilename(filename), "实验报告评分标准"),
                firstNonBlank(subjectHint, inferSubject(sections), "实验课程"),
                firstNonBlank(buildDescriptionFromSections(sections), "根据教师上传的空白实验模板自动生成的评分标准草案。"),
                buildCustomPromptFromSections(sections),
                heuristicDimensions(sections)
        );
    }

    private List<DraftDimension> normalizeDimensions(List<DraftDimension> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return List.of();
        }

        List<DraftDimension> cleaned = dimensions.stream()
                .filter(Objects::nonNull)
                .map(dim -> new DraftDimension(
                        dim.name() == null ? "" : dim.name().trim(),
                        dim.description() == null ? "" : dim.description().trim(),
                        dim.maxScore() == null || dim.maxScore().signum() <= 0 ? BigDecimal.TEN : dim.maxScore(),
                        dim.weight() == null ? 0 : dim.weight()))
                .filter(dim -> !dim.name().isBlank())
                .limit(6)
                .toList();
        if (cleaned.isEmpty()) {
            return List.of();
        }

        int weightSum = cleaned.stream()
                .map(DraftDimension::weight)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        List<DraftDimension> adjusted = new ArrayList<>();
        if (weightSum <= 0) {
            int base = 100 / cleaned.size();
            int remainder = 100 - base * cleaned.size();
            for (int i = 0; i < cleaned.size(); i++) {
                DraftDimension dim = cleaned.get(i);
                adjusted.add(new DraftDimension(
                        dim.name(),
                        dim.description(),
                        dim.maxScore(),
                        base + (i < remainder ? 1 : 0)));
            }
            return adjusted;
        }

        int assigned = 0;
        for (int i = 0; i < cleaned.size(); i++) {
            DraftDimension dim = cleaned.get(i);
            int weight = BigDecimal.valueOf(dim.weight())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(weightSum), 0, RoundingMode.HALF_UP)
                    .intValue();
            if (i == cleaned.size() - 1) {
                weight = 100 - assigned;
            }
            assigned += weight;
            adjusted.add(new DraftDimension(dim.name(), dim.description(), dim.maxScore(), weight));
        }
        return adjusted;
    }

    private List<DraftDimension> heuristicDimensions(Map<String, String> sections) {
        boolean hasTask = containsAny(sections.get("task"), "内容", "任务", "步骤", "要求");
        boolean hasResult = containsAny(sections.get("result"), "结果", "分析", "运行", "输出", "截图");

        List<DraftDimension> dims = new ArrayList<>();
        dims.add(new DraftDimension(
                "任务完成度",
                hasTask
                        ? "根据实验任务和具体要求，评估学生是否完成核心操作、关键步骤与规定内容。"
                        : "评估学生是否完成实验核心任务与基本要求。",
                new BigDecimal("30"),
                40));
        dims.add(new DraftDimension(
                "结果与分析",
                hasResult
                        ? "结合实验结果、现象说明与原因分析，评估结论是否准确、分析是否充分。"
                        : "评估实验结果说明、问题分析与总结是否完整、合理。",
                new BigDecimal("25"),
                30));
        dims.add(new DraftDimension(
                "报告规范性",
                "评估报告结构、文字表达、代码或截图呈现是否清晰规范。",
                new BigDecimal("20"),
                20));
        dims.add(new DraftDimension(
                "独立思考与改进",
                "评估是否体现调试过程、问题定位、优化尝试或额外分析。",
                new BigDecimal("10"),
                10));
        return dims;
    }

    private String buildDraftPrompt(String focusedContext, String subjectHint, String nameHint) {
        StringBuilder builder = new StringBuilder();
        builder.append("请根据教师上传的空白实验报告模板，生成一份实验报告评分标准草案。\n")
                .append("只能依据模板中的实验目的、要求、任务和结果分析要求来设计评分维度。\n")
                .append("输出必须是 JSON，不要输出 Markdown。\n")
                .append("要求：\n")
                .append("1. dimensions 生成 4 到 6 个。\n")
                .append("2. weight 总和必须等于 100。\n")
                .append("3. maxScore 必须为正数。\n")
                .append("4. customPrompt 要提醒评分模型基于学生实际完成内容和证据评分，不要无依据臆断。\n")
                .append("5. JSON 结构如下：\n")
                .append("{\n")
                .append("  \"name\": \"评分标准名称\",\n")
                .append("  \"subject\": \"课程或实验主题\",\n")
                .append("  \"description\": \"评分标准说明\",\n")
                .append("  \"customPrompt\": \"AI 评分补充要求\",\n")
                .append("  \"dimensions\": [\n")
                .append("    {\"name\":\"任务完成度\",\"description\":\"...\",\"maxScore\":30,\"weight\":40}\n")
                .append("  ]\n")
                .append("}\n");

        if ((subjectHint != null && !subjectHint.isBlank()) || (nameHint != null && !nameHint.isBlank())) {
            builder.append("教师提示：\n")
                    .append("- subjectHint: ").append(safe(subjectHint, 120)).append('\n')
                    .append("- nameHint: ").append(safe(nameHint, 120)).append('\n');
        }

        builder.append("\n模板内容：\n").append(focusedContext);
        return builder.toString();
    }

    private Map<String, String> extractSections(String normalized) {
        List<SectionLine> headings = new ArrayList<>();
        String[] rawLines = normalized.split("\n");
        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i].trim();
            if (line.isBlank()) {
                continue;
            }
            Matcher matcher = HEADING_PATTERN.matcher(line);
            if (matcher.find()) {
                headings.add(new SectionLine(i, matcher.group(1).trim()));
            }
        }

        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("objective", sliceSection(rawLines, headings, idx -> containsAny(headings.get(idx).title(), "目的", "要求")));
        sections.put("task", sliceSection(rawLines, headings, idx -> containsAny(headings.get(idx).title(), "内容", "任务", "步骤")));
        sections.put("result", sliceSection(rawLines, headings, idx -> containsAny(headings.get(idx).title(), "结果", "分析", "总结")));
        return sections;
    }

    private String sliceSection(String[] rawLines, List<SectionLine> headings, HeadingMatcher matcher) {
        for (int i = 0; i < headings.size(); i++) {
            if (!matcher.matches(i)) {
                continue;
            }
            int start = headings.get(i).lineIndex();
            int end = i + 1 < headings.size() ? headings.get(i + 1).lineIndex() : rawLines.length;
            StringBuilder builder = new StringBuilder();
            for (int lineIndex = start; lineIndex < end; lineIndex++) {
                String line = rawLines[lineIndex].trim();
                if (!line.isBlank()) {
                    builder.append(line).append('\n');
                }
            }
            String result = builder.toString().trim();
            if (!result.isBlank()) {
                return safe(result, 2500);
            }
        }
        return "";
    }

    private String buildFocusedContext(String filename,
                                       String subjectHint,
                                       String nameHint,
                                       String normalized,
                                       Map<String, String> sections) {
        StringBuilder builder = new StringBuilder();
        builder.append("filename: ").append(safe(filename, 200)).append('\n');
        if (subjectHint != null && !subjectHint.isBlank()) {
            builder.append("subjectHint: ").append(safe(subjectHint, 120)).append('\n');
        }
        if (nameHint != null && !nameHint.isBlank()) {
            builder.append("nameHint: ").append(safe(nameHint, 120)).append('\n');
        }
        appendSection(builder, "实验目的和要求", sections.get("objective"));
        appendSection(builder, "实验内容与任务", sections.get("task"));
        appendSection(builder, "实验结果与分析", sections.get("result"));
        builder.append("\nRaw template excerpt:\n").append(safe(normalized, MAX_TEMPLATE_TEXT_CHARS));
        return builder.toString();
    }

    private void appendSection(StringBuilder builder, String title, String value) {
        if (value != null && !value.isBlank()) {
            builder.append('\n').append(title).append(":\n").append(value).append('\n');
        }
    }

    private String normalizeText(String extracted) {
        String normalized = extracted.replace("\r\n", "\n").replace('\r', '\n');
        normalized = normalized.replace('\u3000', ' ');
        normalized = normalized.replaceAll("[\\t\\x0B\\f]+", " ");
        normalized = normalized.replaceAll("\\n{3,}", "\n\n");
        return normalized.trim();
    }

    private ProviderConfig resolveProvider() {
        String provider = aiProperties.provider() == null
                ? ""
                : aiProperties.provider().trim().toLowerCase(Locale.ROOT);

        if ("dashscope".equals(provider) || "qwen".equals(provider)) {
            AiProperties.Dashscope dashscope = aiProperties.dashscope();
            String apiKey = dashscope == null ? null : dashscope.apiKey();
            if (apiKey == null || apiKey.isBlank()) {
                return null;
            }
            String baseUrl = dashscope.baseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
            }
            String model = dashscope.model();
            if (model == null || model.isBlank()) {
                model = "qwen-plus-latest";
            }
            return new ProviderConfig(baseUrl, apiKey.trim(), model);
        }

        if ("openai".equals(provider)) {
            AiProperties.OpenAi openAi = aiProperties.openai();
            String apiKey = openAi == null ? null : openAi.apiKey();
            if (apiKey == null || apiKey.isBlank()) {
                return null;
            }
            String baseUrl = openAi.baseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://api.openai.com/v1";
            }
            String model = openAi.model();
            if (model == null || model.isBlank()) {
                model = "gpt-4o-mini";
            }
            return new ProviderConfig(baseUrl, apiKey.trim(), model);
        }
        return null;
    }

    private String inferNameFromFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "实验报告评分标准";
        }
        String base = filename.replaceAll("\\.[^.]+$", "");
        return base.isBlank() ? "实验报告评分标准" : base + "-评分标准";
    }

    private String inferSubject(Map<String, String> sections) {
        String source = firstNonBlank(sections.get("task"), sections.get("objective"), "");
        if (source.contains("数据库")) return "数据库实验";
        if (source.contains("Vue") || source.contains("前端")) return "前端实验";
        if (source.contains("Spring") || source.contains("Java")) return "Java Web实验";
        return "实验课程";
    }

    private String buildDescriptionFromSections(Map<String, String> sections) {
        String objective = sections.get("objective");
        String task = sections.get("task");
        if ((objective == null || objective.isBlank()) && (task == null || task.isBlank())) {
            return "";
        }
        return "根据实验模板中的目标、要求与任务内容生成。"
                + (objective != null && !objective.isBlank() ? " 目标摘要：" + safe(objective, 180) : "")
                + (task != null && !task.isBlank() ? " 任务摘要：" + safe(task, 220) : "");
    }

    private String buildCustomPromptFromSections(Map<String, String> sections) {
        String objective = safe(sections.get("objective"), 260);
        String task = safe(sections.get("task"), 320);
        StringBuilder builder = new StringBuilder("评分时必须结合学生实际完成内容、结果说明与分析过程，避免仅凭模板内容给高分。");
        if (!objective.isBlank()) {
            builder.append(" 重点核对实验目标与要求：").append(objective);
        }
        if (!task.isBlank()) {
            builder.append(" 重点核对具体任务：").append(task);
        }
        builder.append(" 若证据不足，应给出保守评分并明确说明缺失点。");
        return builder.toString();
    }

    private BigDecimal decimalOrDefault(String value, BigDecimal fallback) {
        try {
            return value == null || value.isBlank() ? fallback : new BigDecimal(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private Integer intOrDefault(String value, Integer fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String safe(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(0, maxLen - 3)) + "...";
    }

    private record ProviderConfig(String baseUrl, String apiKey, String model) {}

    private record SectionLine(int lineIndex, String title) {}

    @FunctionalInterface
    private interface HeadingMatcher {
        boolean matches(int index);
    }

    public record DraftRubric(
            String name,
            String subject,
            String description,
            String customPrompt,
            List<DraftDimension> dimensions
    ) {}

    public record DraftDimension(
            String name,
            String description,
            BigDecimal maxScore,
            Integer weight
    ) {}
}
