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
 * 错误演示的分步可视化工作流（统一生产线）。
 * <p>
 * 核心原则：<b>不让大模型直出 HTML</b>，而是让它只产出「结构化步骤数据」——每一步包含代码行高亮、旁白字幕、节点/边状态、变量。
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
            "array", "matrix", "string",
            "linked-list", "doubly-linked-list", "circular-linked-list", "static-linked-list",
            "stack", "queue", "circular-queue",
            "tree", "binary-tree", "heap",
            "graph", "adjacency-matrix", "adjacency-list", "hash-table",
            "pointer", "loop", "code");

    /** 常见别名 → 规范结构名，与前端 PythonTutorRenderer 的 STRUCTURE_ALIASES 保持一致。 */
    private static final Map<String, String> STRUCTURE_ALIASES = Map.ofEntries(
            Map.entry("list", "linked-list"),
            Map.entry("linkedlist", "linked-list"),
            Map.entry("singly-linked-list", "linked-list"),
            Map.entry("double-linked-list", "doubly-linked-list"),
            Map.entry("circular-list", "circular-linked-list"),
            Map.entry("static-list", "static-linked-list"),
            Map.entry("2d-array", "matrix"),
            Map.entry("grid", "matrix"),
            Map.entry("str", "string"),
            Map.entry("ring-queue", "circular-queue"),
            Map.entry("deque", "queue"),
            Map.entry("bst", "binary-tree"),
            Map.entry("binary-search-tree", "binary-tree"),
            Map.entry("huffman-tree", "binary-tree"),
            Map.entry("avl", "binary-tree"),
            Map.entry("min-heap", "heap"),
            Map.entry("max-heap", "heap"),
            Map.entry("adj-matrix", "adjacency-matrix"),
            Map.entry("adj-list", "adjacency-list"),
            Map.entry("hashtable", "hash-table"),
            Map.entry("hashmap", "hash-table"));

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
              "dataStructure": "从下面选一个最贴切的（见末尾结构说明）：array | matrix | string | linked-list | doubly-linked-list | circular-linked-list | static-linked-list | stack | queue | circular-queue | tree | binary-tree | heap | graph | adjacency-matrix | adjacency-list | hash-table | pointer | loop | code",
              "sourceCode": "一段<=25行的代码；必须截取【学生代码】中与错误最相关的片段（保持原样，不要修复错误）；若未提供【学生代码】，sourceCode 必须为空字符串，严禁虚构或套用与学生无关的示例代码",
              "errorLine": 0,
              "correctedCode": "关键的正确写法或要点（可留空字符串）",
              "explanation": "一句话讲清这个概念/为什么会错（<=60字）",
              "steps": [
                {
                  "line": 3,
                  "caption": "这一步在发生什么（旁白字幕，中文，<=32字，语气自然温柔）",
                  "variables": { "pHead": "NULL", "x": "30" },
                  "nodes": [ { "id": "n1", "label": "20", "value": "20", "active": true, "index": 0 } ],
                  "edges": [ { "from": "n1", "to": "n2", "label": "next", "kind": "next" } ],
                  "pointers": [ { "name": "pHead", "target": "n1" } ],
                  "error": false
                }
              ]
            }

            ## 编排要求（决定质量）
            - 步骤数 4-10 步，每步只讲清一件事，circular 推进，前后状态连贯（节点只增/改一点点）。
            - line 指向 sourceCode 中与本步最相关的行号（从 1 开始）；若该步不对应某行填 0。
            - caption 是给学生看的旁白，像老师在旁边讲解，不要写“步骤1”这种废话。
            - nodes 通用字段：id 稳定唯一、label/value 显示文本、active=true 高亮当前操作元素、
              outOfBounds=true 标非法/越界（红色虚线）、index 为下标。
            - edges 用 from/to 引用 node 的 id；label 可写权重或“next”，kind 用于二叉树左右：'child-left'/'child-right'。
            - pointers 画指向某结点的具名箭头（栈 top、队列 front/rear、链表 pHead/头尾等），target 为 node id。
            - 出错的那一步把 error 设为 true，并让 caption 点明错在哪。
            - 若提供了【学生代码】：步骤要围绕学生代码真实的执行/出错过程展开，line 指向 sourceCode（即截取后的学生代码）中的行号，errorLine 指向出错行。
            - 全程中文、术语准确、不啭嗦。

            ## 各结构的数据怎么给（挑与 dataStructure 对应的填）
            - array/string：nodes 顺序即元素顺序，index 为下标；string 每个 node 放一个字符。
            - matrix / adjacency-matrix：每个 node 给 row、col；matrix 也可在顶层给 rows、cols。
            - linked-list / doubly-linked-list / circular-linked-list：nodes 按链序排；edges 给 next 连接；
              用 pointers 标 pHead/头尾；双链表会自动画反向 prior 箭头，循环链表会自动画末→首回边。
            - static-linked-list：每个 node 给 value 与 cursor（游标=下一个下标，尾结点省略即可）。
            - stack：nodes 按 index 从 0（栈底）到顶排列；pointers 里放 { name:"top", target: 栈顶node.id }。
            - queue：nodes 从队头到队尾；pointers 放 front 与 rear。
            - circular-queue：顶层给 capacity；pointers 放 front、rear；已占用的槽 active=true。
            - tree：任意树，用 edges(from=父,to=子)。
            - binary-tree/BST：edges 用 kind='child-left'/'child-right' 指明左右孩子。
            - heap：nodes 的 index 按完全二叉树层序（1 起或 0 起均可），会自动按 2i/2i+1 布局。
            - graph：nodes + edges（label 可作权重）。adjacency-list：edges(from→to) 表示邻居。
            - hash-table：顶层给 buckets（桶数）；每个 node 给 slot（所在桶下标），同桶按出现顺序串成链。
            - pointer：两个 node（一个含 'ptr'，一个目标）示意指向关系。loop：nodes[0].value 作循环条件文字。
            """;

    private final AnimationAiClient aiClient;
    private final ObjectMapper objectMapper;

    public ConceptStepsWorkflow(AnimationAiClient aiClient, ObjectMapper objectMapper) {
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    public AnimationResult generate(AnimationCandidate candidate, int index) {
        String topic = buildTopic(candidate);
        // 防编造：没有学生真实代码时，绝不让 LLM 自由生成代码动画（会画出与学生无关的示例），
        // 只依据教师批注给出纯文字概念说明。
        if (extractStudentCode(candidate).isBlank()) {
            return conceptTextResult(candidate, topic);
        }
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
                frames.add(buildFrame(step, order, structure, root));
            }
        }
        if (frames.isEmpty()) {
            frames.add(staticFrame(explanation));
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("errorType", resolveErrorType(candidate));
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

    private Map<String, Object> buildFrame(JsonNode step, int order, String structure, JsonNode root) {
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
        state.put("pointers", parsePointers(step.path("pointers")));
        // 结构级标量：优先取本步，缺省回落到根级（rows/cols/capacity/buckets）。
        for (String key : new String[]{"rows", "cols", "capacity", "buckets"}) {
            if (step.has(key)) {
                state.put(key, step.path(key).asInt());
            } else if (root != null && root.has(key)) {
                state.put(key, root.path(key).asInt());
            }
        }
        frame.put("state", state);
        return frame;
    }

    private List<Map<String, Object>> parsePointers(JsonNode arr) {
        List<Map<String, Object>> pointers = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode p : arr) {
                String target = firstNonBlank(text(p, "target"), text(p, "node"));
                if (target.isBlank()) {
                    continue;
                }
                Map<String, Object> ptr = new LinkedHashMap<>();
                ptr.put("name", text(p, "name"));
                ptr.put("target", target);
                pointers.add(ptr);
            }
        }
        return pointers;
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
                // 结构特定字段：仅在存在时透传，避免污染无关结构。
                if (n.has("next")) node.put("next", text(n, "next"));
                if (n.has("cursor")) node.put("cursor", n.path("cursor").asInt());
                if (n.has("row")) node.put("row", n.path("row").asInt());
                if (n.has("col")) node.put("col", n.path("col").asInt());
                if (n.has("slot")) node.put("slot", n.path("slot").asInt());
                if (n.has("pointer")) node.put("pointer", text(n, "pointer"));
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
                if (from.isBlank() || to.isBlank()) {
                    continue;
                }
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("from", from);
                edge.put("to", to);
                edge.put("label", text(e, "label"));
                edge.put("kind", text(e, "kind"));
                edges.add(edge);
            }
        }
        return edges;
    }

    // ---- 兜底 -------------------------------------------------------------

    /** 无学生真实代码时的安全结果：纯文字概念说明（来自教师批注），不含虚构代码/节点。 */
    private AnimationResult conceptTextResult(AnimationCandidate candidate, String topic) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("errorType", resolveErrorType(candidate));
        metadata.put("concept", extractConcept(candidate));
        metadata.put("dataStructure", "code");
        String caption = firstNonBlank(candidate.note(), candidate.anchor(), "结合教师批注理解该知识点。");
        return new AnimationResult(
                AnimationWorkflow.CONCEPT_STEPS.name(),
                topic,
                caption,
                List.of(staticFrame(caption)),
                metadata
        );
    }

    private AnimationResult fallbackResult(AnimationCandidate candidate, String topic) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("errorType", resolveErrorType(candidate));
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
        String studentCode = extractStudentCode(candidate);
        if (!studentCode.isBlank()) {
            sb.append("【学生代码】\n").append(studentCode).append("\n");
        }
        sb.append("\n请只输出上述结构的 JSON。");
        return sb.toString();
    }

    /** 取候选里的学生代码上下文，让步骤动画围绕真实代码展开；过长时截断避免提示词膨胀。 */
    private String extractStudentCode(AnimationCandidate candidate) {
        CodeContext ctx = candidate.codeContext();
        if (ctx == null || ctx.fullCode() == null || ctx.fullCode().isBlank()) {
            return "";
        }
        return clampLines(ctx.fullCode(), 60);
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

    /** 现在所有错误类型都可能路由到本工作流，errorType 跟随真实检测结果而非固定 CONCEPT。 */
    private String resolveErrorType(AnimationCandidate candidate) {
        return candidate.detectedErrorType() == null ? "CONCEPT" : candidate.detectedErrorType().name();
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
        v = STRUCTURE_ALIASES.getOrDefault(v, v);
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
