package com.tap.backend.service.grading.animation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.service.animation.AnimationAiClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 概念/原理类错误的分步可视化工作流（路 B）。
 * <p>
 * 与已弃用的 {@link HtmlAnimationWorkflow} 的本质区别：<b>不让大模型直出整段 HTML</b>，
 * 而是让它只产出「结构化步骤数据」——每一步包含代码行高亮、旁白字幕、节点/边状态、变量。
 * 渲染交给前端固定的 {@code PythonTutorRenderer}，从而保证：
 * <ul>
 *   <li>三条轨道同步：代码行 ↔ 画面 ↔ 字幕（对齐图码式教学动画）；</li>
 *   <li>风格一致、布局稳定，不受大模型每次自由发挥的 HTML 质量影响。</li>
 * </ul>
 * 大模型只决定「讲什么」，引擎保证「好不好看」。
 */
@Component
public class ConceptStepsWorkflow {

    private static final Logger log = LoggerFactory.getLogger(ConceptStepsWorkflow.class);
    private static final Pattern JSON_FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    /** 渲染器支持的可视化结构类型；模型只能从中取值，越界时回落 code。 */
    private static final Set<String> ALLOWED_STRUCTURES = Set.of(
            "array", "linked-list", "tree", "graph", "pointer", "loop", "heap", "code");

    private static final int MAX_STEPS = 12;
    private static final int MAX_NODES_PER_STEP = 12;
    private static final int MAX_SOURCE_LINES = 30;

    private static final String SYSTEM_PROMPT = """
            你是一位数据结构/算法教学动画的编排设计师。请把「学生错误相关的概念或原理」拆解成一段
            可逐步播放的教学动画，用来帮助学生真正看懂。

            ## 只输出结构化步骤数据，不要输出 HTML、不要输出任何解释性文字
            严格返回一个 JSON 对象（不要包裹在其它文本里），字段如下：

            {
              "title": "简短标题（<=16字）",
              "concept": "本动画讲解的核心概念，如：链表结构 / 指针与内存 / 递归执行过程",
              "dataStructure": "从这些里选一个最贴切的：array | linked-list | tree | graph | pointer | loop | heap | code",
              "sourceCode": "一段<=25行的示意代码（可用C/伪代码），仅用于配合动画讲解，不必是学生原代码",
              "errorLine": 0,
              "correctedCode": "关键的正确写法或要点（可留空字符串）",
              "explanation": "一句话讲清这个概念/为什么会错（<=60字）",
              "steps": [
                {
                  "line": 3,
                  "caption": "这一步在发生什么（旁白字幕，中文，<=32字，语气自然温柔）",
                  "variables": { "pHead": "NULL", "x": "30" },
                  "nodes": [ { "id": "n1", "label": "20", "value": "20", "active": true } ],
                  "edges": [ { "from": "n1", "to": "n2", "label": "next" } ],
                  "error": false
                }
              ]
            }

            ## 编排要求（决定质量）
            - 步骤数 4-10 步，每步只讲清一件事，circular 推进，前后状态连贯（节点只增/改一点点）。
            - line 指向 sourceCode 中与本步最相关的行号（从 1 开始）；若该步不对应某行填 0。
            - caption 是给学生看的旁白，像老师在旁边讲解，不要写“步骤1”这种废话。
            - nodes 表示画面里的元素：id 稳定唯一、label 显示文本、active=true 高亮当前操作的元素。
            - edges 用 from/to 引用 node 的 id，label 可写“next”“prior”等指针名；无连线则给空数组。
            - dataStructure=linked-list/tree/graph/pointer 时务必给出 nodes 与 edges；array/loop/heap 主要用 nodes。
            - 出错的那一步把 error 设为 true，并让 caption 点明错在哪。
            - 全程中文、术语准确、不啰嗦。
            """;

    private final AnimationAiClient aiClient;
    private final ObjectMapper objectMapper;

    public ConceptStepsWorkflow(AnimationAiClient aiClient, ObjectMapper objectMapper) {
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    public AnimationResult generate(AnimationCandidate candidate, int index) {
        String topic = buildTopic(candidate);
        JsonNode parsed = null;
        if (aiClient.isChatAvailable()) {
            try {
                String raw = aiClient.chat(SYSTEM_PROMPT, buildUserPrompt(candidate, topic), 0.4);
                parsed = extractJson(raw);
            } catch (Exception e) {
                log.warn("概念分步动画 AI 生成失败，回退静态单步: {}", e.getMessage());
            }
        }
        if (parsed == null) {
            return fallbackResult(candidate, topic);
        }
        try {
            return buildResult(parsed, candidate, topic);
        } catch (Exception e) {
            log.warn("概念分步动画解析失败，回退静态单步: {}", e.getMessage());
            return fallbackResult(candidate, topic);
        }
    }

    // ---- 解析与组装 -------------------------------------------------------

    private AnimationResult buildResult(JsonNode root, AnimationCandidate candidate, String topic) {
        String title = firstNonBlank(text(root, "title"), topic);
        String concept = firstNonBlank(text(root, "concept"), extractConcept(candidate));
        String structure = normalizeStructure(text(root, "dataStructure"));
        String sourceCode = clampLines(text(root, "sourceCode"), MAX_SOURCE_LINES);
        String correctedCode = text(root, "correctedCode");
        String explanation = firstNonBlank(text(root, "explanation"), candidate.note());
        int errorLine = root.path("errorLine").asInt(0);

        List<Map<String, Object>> frames = new ArrayList<>();
        JsonNode steps = root.path("steps");
        if (steps.isArray()) {
            int order = 0;
            for (JsonNode step : steps) {
                if (order >= MAX_STEPS) {
                    break;
                }
                order++;
                frames.add(buildFrame(step, order, structure));
            }
        }
        if (frames.isEmpty()) {
            frames.add(staticFrame(explanation));
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("errorType", "CONCEPT");
        metadata.put("concept", concept);
        metadata.put("dataStructure", structure);
        // 让示意代码 / 修正写法 / 错误行随 metadata 流到前端（toErrorDemonstration 会优先读取）。
        if (!sourceCode.isBlank()) {
            metadata.put("sourceCode", sourceCode);
        }
        if (!correctedCode.isBlank()) {
            metadata.put("correctedCode", correctedCode);
        }
        if (errorLine > 0) {
            metadata.put("errorLine", errorLine);
        }

        return new AnimationResult(
                AnimationWorkflow.CONCEPT_STEPS.name(),
                title,
                explanation,
                frames,
                metadata
        );
    }

    private Map<String, Object> buildFrame(JsonNode step, int order, String structure) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("order", order);
        frame.put("line", step.path("line").asInt(0));
        frame.put("variables", parseVariables(step.path("variables")));
        frame.put("explanation", text(step, "caption"));
        frame.put("error", step.path("error").asBoolean(false));
        frame.put("memory", List.of());

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("dataStructure", structure);
        state.put("nodes", parseNodes(step.path("nodes")));
        state.put("edges", parseEdges(step.path("edges")));
        frame.put("state", state);
        return frame;
    }

    private Map<String, String> parseVariables(JsonNode node) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> vars.put(e.getKey(), asPlainText(e.getValue())));
        }
        return vars;
    }

    private List<Map<String, Object>> parseNodes(JsonNode arr) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            int i = 0;
            for (JsonNode n : arr) {
                if (i >= MAX_NODES_PER_STEP) {
                    break;
                }
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", firstNonBlank(text(n, "id"), "n" + i));
                node.put("label", text(n, "label"));
                node.put("value", firstNonBlank(text(n, "value"), text(n, "label")));
                node.put("active", n.path("active").asBoolean(false));
                node.put("outOfBounds", n.path("outOfBounds").asBoolean(false));
                node.put("index", n.path("index").asInt(i));
                nodes.add(node);
                i++;
            }
        }
        return nodes;
    }

    private List<Map<String, Object>> parseEdges(JsonNode arr) {
        List<Map<String, Object>> edges = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode e : arr) {
                String from = firstNonBlank(text(e, "from"), text(e, "source"));
                String to = firstNonBlank(text(e, "to"), text(e, "target"));
                if (from == null || to == null) {
                    continue;
                }
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("from", from);
                edge.put("to", to);
                edge.put("label", text(e, "label"));
                edges.add(edge);
            }
        }
        return edges;
    }

    // ---- 兜底 -------------------------------------------------------------

    private AnimationResult fallbackResult(AnimationCandidate candidate, String topic) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("errorType", "CONCEPT");
        metadata.put("concept", extractConcept(candidate));
        metadata.put("dataStructure", "code");
        return new AnimationResult(
                AnimationWorkflow.CONCEPT_STEPS.name(),
                topic,
                candidate.note(),
                List.of(staticFrame(candidate.note())),
                metadata
        );
    }

    private Map<String, Object> staticFrame(String caption) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("order", 1);
        frame.put("line", 0);
        frame.put("variables", Map.of());
        frame.put("explanation", caption == null ? "" : caption);
        frame.put("error", false);
        frame.put("memory", List.of());
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("dataStructure", "code");
        state.put("nodes", List.of());
        state.put("edges", List.of());
        frame.put("state", state);
        return frame;
    }

    // ---- 提示词 / 主题 ----------------------------------------------------

    private String buildUserPrompt(AnimationCandidate candidate, String topic) {
        ProblemContext problem = candidate.problemContext();
        StringBuilder sb = new StringBuilder();
        sb.append("【主题】").append(topic).append("\n");
        if (problem != null && problem.experimentTitle() != null && !problem.experimentTitle().isBlank()) {
            sb.append("【实验标题】").append(problem.experimentTitle()).append("\n");
        }
        if (problem != null && problem.experimentRequirements() != null && !problem.experimentRequirements().isEmpty()) {
            sb.append("【实验要求】\n").append(problem.experimentRequirements()).append("\n");
        }
        sb.append("【学生错误内容/位置】\n").append(nullToEmpty(candidate.anchor())).append("\n");
        sb.append("【教师批注】\n").append(nullToEmpty(candidate.note())).append("\n");
        sb.append("\n请只输出上述结构的 JSON。");
        return sb.toString();
    }

    private String buildTopic(AnimationCandidate candidate) {
        String problemTitle = candidate.problemContext() != null
                ? nullToEmpty(candidate.problemContext().experimentTitle())
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
        String combined = (nullToEmpty(candidate.anchor()) + " " + nullToEmpty(candidate.note()))
                .toLowerCase(Locale.ROOT);
        if (combined.contains("递归")) return "递归执行过程";
        if (combined.contains("指针")) return "指针与内存地址";
        if (combined.contains("链表")) return "链表结构";
        if (combined.contains("树")) return "树形结构";
        if (combined.contains("图")) return "图结构";
        if (combined.contains("复杂度")) return "时间复杂度分析";
        if (combined.contains("栈")) return "栈的结构";
        if (combined.contains("队列")) return "队列的结构";
        if (combined.contains("内存")) return "内存管理";
        if (combined.contains("原理")) return "算法原理";
        return "核心概念";
    }

    // ---- 工具 -------------------------------------------------------------

    private JsonNode extractJson(String raw) throws Exception {
        String trimmed = raw == null ? "" : raw.trim();
        Matcher matcher = JSON_FENCE.matcher(trimmed);
        if (matcher.find()) {
            trimmed = matcher.group(1).trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            trimmed = trimmed.substring(start, end + 1);
        }
        return objectMapper.readTree(trimmed);
    }

    private String normalizeStructure(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (v.equals("linkedlist") || v.equals("list")) {
            v = "linked-list";
        }
        return ALLOWED_STRUCTURES.contains(v) ? v : "code";
    }

    private String clampLines(String code, int maxLines) {
        if (code == null || code.isBlank()) {
            return "";
        }
        String[] lines = code.split("\n", -1);
        if (lines.length <= maxLines) {
            return code;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            sb.append(lines[i]);
            if (i < maxLines - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    /** 变量值可能是数字/布尔/对象，统一转成展示用字符串。 */
    private String asPlainText(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isValueNode()) {
            return value.asText("");
        }
        return value.toString();
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
