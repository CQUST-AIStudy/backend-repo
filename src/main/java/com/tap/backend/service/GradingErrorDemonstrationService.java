package com.tap.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.grading.EvidenceBlockEntity;
import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.domain.grading.ScoreItemEntity;
import com.tap.backend.service.grading.animation.AnimationCandidate;
import com.tap.backend.service.grading.animation.AnimationResult;
import com.tap.backend.service.grading.animation.AnimationWorkflow;
import com.tap.backend.service.grading.animation.AnimationWorkflowRouter;
import com.tap.backend.service.grading.animation.CodeContext;
import com.tap.backend.service.grading.animation.GenericHighlightWorkflow;
import com.tap.backend.service.grading.animation.HtmlAnimationWorkflow;
import com.tap.backend.service.grading.animation.ProblemContext;
import com.tap.backend.service.grading.animation.PythonTutorWorkflow;
import com.tap.backend.service.grading.animation.ResultCompareWorkflow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 基于 AI 批注生成错误演示动画。
 * 支持多种工作流：Python Tutor 式代码执行可视化、AI 生成 HTML 动画、图文对比、通用高亮。
 */
@Service
public class GradingErrorDemonstrationService {

    private static final Pattern QUOTED_FRAGMENT = Pattern.compile("['‘“`](.{2,120}?)['’”`]");
    private static final Pattern FUNCTION_START =
            Pattern.compile("^\\s*(int|void|char|float|double|bool|struct\\s+\\w+|enum\\s+\\w+)\\s+\\w+\\s*\\(");
    private static final int MAX_DEMONSTRATIONS = 4;
    private static final int CONTEXT_LINES = 5;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnimationWorkflowRouter router;
    private final PythonTutorWorkflow pythonTutorWorkflow;
    private final HtmlAnimationWorkflow htmlAnimationWorkflow;
    private final ResultCompareWorkflow resultCompareWorkflow;
    private final GenericHighlightWorkflow genericHighlightWorkflow;

    public GradingErrorDemonstrationService(AnimationWorkflowRouter router,
                                            PythonTutorWorkflow pythonTutorWorkflow,
                                            HtmlAnimationWorkflow htmlAnimationWorkflow,
                                            ResultCompareWorkflow resultCompareWorkflow,
                                            GenericHighlightWorkflow genericHighlightWorkflow) {
        this.router = router;
        this.pythonTutorWorkflow = pythonTutorWorkflow;
        this.htmlAnimationWorkflow = htmlAnimationWorkflow;
        this.resultCompareWorkflow = resultCompareWorkflow;
        this.genericHighlightWorkflow = genericHighlightWorkflow;
    }

    /**
     * 基于评分项和证据块生成错误演示动画。
     *
     * @param submission    当前提交（用于获取题目上下文）
     * @param scoreItems    评分项列表
     * @param evidenceBlocks 证据块列表
     * @return 错误演示列表
     */
    public List<ErrorDemonstration> buildDemonstrations(
            GradingSubmissionEntity submission,
            List<ScoreItemEntity> scoreItems,
            List<EvidenceBlockEntity> evidenceBlocks) {

        ProblemContext problemContext = buildProblemContext(submission);
        List<AnimationCandidate> candidates = collectCandidates(scoreItems, evidenceBlocks, problemContext);

        List<ErrorDemonstration> result = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (result.size() >= MAX_DEMONSTRATIONS) break;
            AnimationCandidate candidate = candidates.get(i);
            AnimationWorkflow workflow = router.route(candidate);
            AnimationResult animationResult = executeWorkflow(workflow, candidate, result.size() + 1);
            ErrorDemonstration demo = toErrorDemonstration(candidate, animationResult, result.size() + 1);
            if (isUnique(result, demo)) {
                result.add(demo);
            }
        }

        // 兜底：如果基于 annotations 没有生成任何演示，尝试从评语生成
        if (result.isEmpty() && scoreItems != null) {
            result.addAll(buildFromComments(scoreItems));
        }

        return result;
    }

    private List<AnimationCandidate> collectCandidates(
            List<ScoreItemEntity> scoreItems,
            List<EvidenceBlockEntity> evidenceBlocks,
            ProblemContext problemContext) {

        if (scoreItems == null || evidenceBlocks == null) {
            return List.of();
        }

        Map<String, EvidenceBlockEntity> evidenceById = evidenceBlocks.stream()
                .filter(eb -> eb.getEvidenceId() != null)
                .collect(Collectors.toMap(EvidenceBlockEntity::getEvidenceId, eb -> eb, (a, b) -> a));

        List<AnimationCandidate> candidates = new ArrayList<>();
        for (ScoreItemEntity item : scoreItems) {
            List<AnimationCandidate.AnnotationInfo> annotations = parseAnnotations(item.getAnnotationsJson());
            for (AnimationCandidate.AnnotationInfo ann : annotations) {
                EvidenceBlockEntity block = evidenceById.get(ann.evidenceId());
                if (block == null || block.getContent() == null) {
                    continue;
                }
                CodeContext codeContext = extractCodeContext(block, ann.anchorText());
                candidates.add(new AnimationCandidate(item, ann, block, codeContext, problemContext));
            }
        }
        return candidates;
    }

    private List<AnimationCandidate.AnnotationInfo> parseAnnotations(String annotationsJson) {
        if (annotationsJson == null || annotationsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(annotationsJson);
            if (!root.isArray()) {
                return List.of();
            }
            List<AnimationCandidate.AnnotationInfo> result = new ArrayList<>();
            for (JsonNode node : root) {
                String type = text(node, "type").toUpperCase(Locale.ROOT);
                if ("CHECK".equals(type)) {
                    continue; // 只处理错误/警告
                }
                String anchor = firstNonBlank(text(node, "anchorText"), text(node, "anchor_text"));
                String note = text(node, "note");
                String evidenceId = text(node, "evidenceId");
                boolean wavy = node.path("wavy").asBoolean(false);
                if ((anchor != null || note != null) && evidenceId != null && !evidenceId.isBlank()) {
                    result.add(new AnimationCandidate.AnnotationInfo(type, evidenceId, anchor, note, wavy));
                }
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private CodeContext extractCodeContext(EvidenceBlockEntity block, String anchorText) {
        String content = block.getContent();
        if (content == null || content.isBlank()) {
            return null;
        }
        List<String> lines = content.lines().collect(Collectors.toList());
        int anchorLine = findLineIndex(lines, anchorText);
        if (anchorLine < 0) {
            anchorLine = fuzzyFindLineIndex(lines, anchorText);
        }

        int contextStart;
        int contextEnd;

        // 如果证据块包含完整函数，返回整个函数
        if (isCompleteFunction(content)) {
            contextStart = 0;
            contextEnd = lines.size() - 1;
        } else {
            contextStart = Math.max(0, anchorLine - CONTEXT_LINES);
            contextEnd = Math.min(lines.size() - 1, anchorLine + CONTEXT_LINES);
        }

        List<String> contextLines = lines.subList(contextStart, contextEnd + 1);
        return new CodeContext(contextLines, anchorLine, anchorLine, contextStart, contextEnd);
    }

    private int findLineIndex(List<String> lines, String text) {
        if (text == null || text.isBlank()) return -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }

    private int fuzzyFindLineIndex(List<String> lines, String text) {
        if (text == null || text.isBlank()) return -1;
        String[] parts = text.split("\\s+");
        if (parts.length == 0) return -1;
        String firstPart = parts[0];
        String lastPart = parts[parts.length - 1];
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains(firstPart) && line.contains(lastPart)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isCompleteFunction(String content) {
        return FUNCTION_START.matcher(content).find() && content.trim().endsWith("}");
    }

    private ProblemContext buildProblemContext(GradingSubmissionEntity submission) {
        if (submission == null || submission.getTask() == null) {
            return null;
        }
        var task = submission.getTask();
        return new ProblemContext(
                task.getExperimentId(),
                task.getRubric() != null ? task.getRubric().getName() : null,
                null,
                List.of(),
                null,
                null
        );
    }

    private AnimationResult executeWorkflow(AnimationWorkflow workflow, AnimationCandidate candidate, int index) {
        return switch (workflow) {
            case PYTHON_TUTOR -> pythonTutorWorkflow.generate(candidate, index);
            case HTML_ANIMATION -> htmlAnimationWorkflow.generate(candidate, index);
            case RESULT_COMPARE -> resultCompareWorkflow.generate(candidate, index);
            case GENERIC_HIGHLIGHT -> genericHighlightWorkflow.generate(candidate, index);
        };
    }

    private ErrorDemonstration toErrorDemonstration(AnimationCandidate candidate,
                                                    AnimationResult result,
                                                    int index) {
        CodeContext ctx = candidate.codeContext();
        String sourceCode = ctx == null ? candidate.anchor() : ctx.fullCode();
        int errorLine = ctx == null ? 1 : ctx.relativeAnchorLine();
        int highlightStart = ctx == null ? 1 : (ctx.highlightStartLine() + 1);
        int highlightEnd = ctx == null ? 1 : (ctx.highlightEndLine() + 1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> frames = (List<Map<String, Object>>) result.frames();

        return new ErrorDemonstration(
                "error-" + index,
                extractErrorType(result),
                result.title(),
                result.explanation(),
                sourceCode,
                result.metadata().getOrDefault("correctedCode", "").toString(),
                errorLine,
                frames,
                result.workflow(),
                highlightStart,
                highlightEnd,
                candidate.problemContext()
        );
    }

    private String extractErrorType(AnimationResult result) {
        Object errorType = result.metadata().get("errorType");
        return errorType != null ? errorType.toString() : "GENERIC";
    }

    private boolean isUnique(List<ErrorDemonstration> existing, ErrorDemonstration demo) {
        return existing.stream().noneMatch(e ->
                e.errorType().equals(demo.errorType())
                        && e.sourceCode().equals(demo.sourceCode()));
    }

    /**
     * 兜底：从评语关键词生成简单的错误演示（兼容旧逻辑）。
     */
    private List<ErrorDemonstration> buildFromComments(List<ScoreItemEntity> scoreItems) {
        List<ErrorDemonstration> result = new ArrayList<>();
        for (ScoreItemEntity item : scoreItems) {
            if (result.size() >= MAX_DEMONSTRATIONS) break;
            Optional<ErrorCandidate> candidate = deriveFromComment(item);
            if (candidate.isPresent()) {
                ErrorDemonstration demo = buildLegacy(candidate.get(), result.size() + 1);
                if (demo != null && isUnique(result, demo)) {
                    result.add(demo);
                }
            }
        }
        return result;
    }

    private Optional<ErrorCandidate> deriveFromComment(ScoreItemEntity item) {
        String comment = item.getComment();
        if (comment == null || comment.isBlank()) {
            return Optional.empty();
        }
        String lower = comment.toLowerCase(Locale.ROOT);
        if (!containsAny(lower, "越界", "未初始化", "空指针", "异常终止", "崩溃")) {
            return Optional.empty();
        }
        Matcher matcher = QUOTED_FRAGMENT.matcher(comment);
        String anchor = matcher.find() ? matcher.group(1).trim() : inferAnchor(comment);
        return Optional.of(new ErrorCandidate("COMMENT", anchor, comment, item));
    }

    private ErrorDemonstration buildLegacy(ErrorCandidate candidate, int index) {
        String combined = (candidate.anchor() + " " + candidate.note()).toLowerCase(Locale.ROOT);
        if (containsAny(combined, "越界", "out of bounds", "i <=", "arr[5]")) {
            return buildLegacyArrayBounds(candidate, index);
        }
        if (containsAny(combined, "未初始化", "空指针", "null pointer", "temp")) {
            return buildLegacyInvalidPointer(candidate, index);
        }
        if (containsAny(combined, "异常终止", "崩溃", "runtime error")) {
            return buildLegacyRuntimeError(candidate, index);
        }
        return null;
    }

    private ErrorDemonstration buildLegacyArrayBounds(ErrorCandidate candidate, int index) {
        String source = normalizeLoop(candidate.anchor());
        String corrected = source.replace("<= n", "< n").replace("<=5", "< 5").replace("<= 5", "< 5");
        List<TraceStep> steps = List.of(
                step(1, "初始化循环变量", 1, Map.of("i", "0", "n", "5"), memory(0, false), false),
                step(2, "i = 1，访问第二个数组元素", 2, Map.of("i", "1", "n", "5"), memory(1, false), false),
                step(3, "i = 2，访问仍在有效范围内", 2, Map.of("i", "2", "n", "5"), memory(2, false), false),
                step(4, "i = 3，循环继续正常执行", 2, Map.of("i", "3", "n", "5"), memory(3, false), false),
                step(5, "i = 4，访问最后一个合法元素 arr[n-1]", 2, Map.of("i", "4", "n", "5"), memory(4, false), false),
                step(6, "条件 i <= n 仍成立，访问 arr[n] 导致越界", 2,
                        Map.of("i", "5", "n", "5", "访问位置", "arr[5]"), memory(5, true), true)
        );
        return new ErrorDemonstration("error-" + index, "ARRAY_BOUNDS",
                "数组越界：循环终止条件多执行一次",
                candidate.note(), source, corrected, 1,
                steps.stream().map(this::stepToMap).toList(),
                AnimationWorkflow.PYTHON_TUTOR.name(), 1, 1, null);
    }

    private ErrorDemonstration buildLegacyInvalidPointer(ErrorCandidate candidate, int index) {
        String source = candidate.anchor().contains("temp")
                ? candidate.anchor() : "int *temp;\n*temp = *a;";
        String corrected = "int temp = *a;\n*a = *b;\n*b = temp;";
        List<TraceStep> steps = List.of(
                step(1, "声明指针 temp，但 temp 未初始化，也没有指向有效内存", 1,
                        Map.of("temp", "未初始化", "a", "&x", "b", "&y"), List.of(), false),
                step(2, "执行 *temp 时，程序尝试写入未知地址", 2,
                        Map.of("temp", "未知地址", "写入", "*a"), List.of(), true),
                step(3, "运行时可能立即崩溃，后续交换语句无法可靠执行", 2,
                        Map.of("程序状态", "异常终止"), List.of(), true)
        );
        return new ErrorDemonstration("error-" + index, "INVALID_POINTER",
                "未初始化指针：解引用未知地址",
                candidate.note(), source, corrected, 2,
                steps.stream().map(this::stepToMap).toList(),
                AnimationWorkflow.PYTHON_TUTOR.name(), 1, 1, null);
    }

    private ErrorDemonstration buildLegacyRuntimeError(ErrorCandidate candidate, int index) {
        List<TraceStep> steps = List.of(
                step(1, "程序进入相关代码段", 1, Map.of("状态", "运行中"), List.of(), false),
                step(2, "错误条件触发，程序无法继续完成预期流程", 1,
                        Map.of("状态", "异常终止"), List.of(), true)
        );
        return new ErrorDemonstration("error-" + index, "RUNTIME_ERROR", "运行时错误",
                candidate.note(), candidate.anchor(), "请根据批注修正该语句后重新运行。", 1,
                steps.stream().map(this::stepToMap).toList(),
                AnimationWorkflow.PYTHON_TUTOR.name(), 1, 1, null);
    }

    private Map<String, Object> stepToMap(TraceStep step) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("order", step.order());
        map.put("line", step.activeLine());
        map.put("explanation", step.explanation());
        map.put("variables", step.variables());
        map.put("memory", step.memory().stream().map(this::memoryToMap).toList());
        map.put("error", step.error());
        return map;
    }

    private Map<String, Object> memoryToMap(MemoryCell cell) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("label", cell.label());
        map.put("value", cell.value());
        map.put("active", cell.active());
        map.put("outOfBounds", cell.outOfBounds());
        return map;
    }

    private TraceStep step(int order, String explanation, int activeLine,
                           Map<String, String> variables, List<MemoryCell> memory, boolean error) {
        return new TraceStep(order, explanation, activeLine, variables, memory, error);
    }

    private List<MemoryCell> memory(int activeIndex, boolean outOfBounds) {
        List<MemoryCell> cells = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            cells.add(new MemoryCell("arr[" + i + "]", String.valueOf((i + 1) * 10), i == activeIndex, false));
        }
        if (outOfBounds) {
            cells.add(new MemoryCell("arr[5]", "越界", true, true));
        }
        return cells;
    }

    private String normalizeLoop(String anchor) {
        if (anchor == null || anchor.isBlank()) {
            return "for (int i = 0; i <= n; i++) {\n    value = arr[i];\n}";
        }
        return anchor.contains("{") ? anchor : anchor + " {\n    value = arr[i];\n}";
    }

    private String inferAnchor(String comment) {
        String lower = comment.toLowerCase(Locale.ROOT);
        if (lower.contains("temp")) return "int *temp;\n*temp = *a;";
        if (lower.contains("arr[5]")) return "for (int i = 0; i <= 5; i++)";
        if (lower.contains("i <= n")) return "for (int i = 0; i <= n; i++)";
        return "相关错误语句";
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private record ErrorCandidate(String type, String anchor, String note, ScoreItemEntity sourceItem) {}

    public record ErrorDemonstration(
            String id,
            String errorType,
            String title,
            String explanation,
            String sourceCode,
            String correctedCode,
            int errorLine,
            List<Map<String, Object>> frames,
            String workflow,
            int highlightStartLine,
            int highlightEndLine,
            ProblemContext problemContext
    ) {}

    public record TraceStep(
            int order,
            String explanation,
            int activeLine,
            Map<String, String> variables,
            List<MemoryCell> memory,
            boolean error
    ) {
        public TraceStep {
            variables = variables == null ? Map.of() : new LinkedHashMap<>(variables);
            memory = memory == null ? List.of() : List.copyOf(memory);
        }
    }

    public record MemoryCell(String label, String value, boolean active, boolean outOfBounds) {}
}
