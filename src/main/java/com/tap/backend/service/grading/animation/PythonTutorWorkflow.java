package com.tap.backend.service.grading.animation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Python Tutor 式执行可视化工作流：适合代码类错误。
 * 当前为基于真实代码片段的半真实执行，后续可替换为真实代码执行。
 */
@Component
public class PythonTutorWorkflow {

    private static final Pattern ARRAY_SIZE_PATTERN =
            Pattern.compile("arr\\[(\\d+)\\]|\\[(\\d+)\\]|大小为(\\d+)|长度是(\\d+)");
    private static final Pattern LOOP_BOUND_PATTERN =
            Pattern.compile("<=\\s*(\\w+)|<\\s*(\\w+)|>=\\s*(\\w+)|>\\s*(\\w+)");
    private static final Pattern ARRAY_LITERAL_PATTERN =
            Pattern.compile("\\{\\s*([^}]+)\\s*\\}");

    public AnimationResult generate(AnimationCandidate candidate, int index) {
        String combined = (candidate.anchor() + " " + candidate.note()).toLowerCase(Locale.ROOT);

        if (containsAny(combined, "越界", "out of bounds", "arr[", "i <=")) {
            return buildArrayBounds(candidate, index);
        }
        if (containsAny(combined, "未初始化", "空指针", "null pointer", "野指针")) {
            return buildInvalidPointer(candidate, index);
        }
        if (containsAny(combined, "死循环", "无限循环", "while(true)", "循环终止")) {
            return buildInfiniteLoop(candidate, index);
        }
        if (containsAny(combined, "内存泄漏", "malloc", "free", "未释放")) {
            return buildMemoryLeak(candidate, index);
        }

        // 兜底：通用代码高亮
        return buildGenericCodeTrace(candidate, index);
    }

    private AnimationResult buildArrayBounds(AnimationCandidate candidate, int index) {
        ArrayBoundsParams params = extractArrayBoundsParams(candidate);
        CodeContext ctx = candidate.codeContext();
        String anchor = candidate.anchor();
        String corrected = buildCorrectedLoop(anchor, params.isInclusive());

        List<Map<String, Object>> steps = new ArrayList<>();
        int arraySize = params.arraySize();
        int anchorLine = ctx == null ? 1 : ctx.relativeAnchorLine();

        // 正常访问步骤
        for (int i = 0; i < arraySize; i++) {
            steps.add(buildStep(i + 1, anchorLine,
                    "i = " + i + "，访问 arr[" + i + "]",
                    Map.of("i", String.valueOf(i), "n", String.valueOf(arraySize)),
                    buildMemoryCells(arraySize, i, false),
                    false));
        }

        // 越界步骤
        if (params.isInclusive() && params.loopUpperBound() >= arraySize) {
            steps.add(buildStep(arraySize + 1, anchorLine,
                    "条件 i <= " + params.loopUpperBound() + " 仍成立，访问 arr[" + arraySize + "] 导致越界",
                    Map.of("i", String.valueOf(arraySize), "n", String.valueOf(arraySize)),
                    buildMemoryCells(arraySize, arraySize, true),
                    true));
        }

        String sourceCode = ctx == null ? anchor : ctx.fullCode();
        return new AnimationResult(
                AnimationWorkflow.PYTHON_TUTOR.name(),
                "数组越界：循环终止条件有误",
                candidate.note(),
                steps,
                Map.of(
                        "errorType", "ARRAY_BOUNDS",
                        "sourceCode", sourceCode,
                        "correctedCode", corrected,
                        "errorLine", anchorLine,
                        "dataStructure", "array"
                )
        );
    }

    private AnimationResult buildInvalidPointer(AnimationCandidate candidate, int index) {
        CodeContext ctx = candidate.codeContext();
        String anchor = candidate.anchor();
        String sourceCode = ctx == null
                ? (anchor.contains("temp") ? anchor : "int *temp;\n*temp = *a;")
                : ctx.fullCode();
        int anchorLine = ctx == null ? 1 : ctx.relativeAnchorLine();

        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(buildStep(1, anchorLine,
                "声明指针 temp，但 temp 未初始化，也没有指向有效内存",
                Map.of("temp", "未初始化", "a", "&x", "b", "&y"),
                List.of(),
                false));
        steps.add(buildStep(2, anchorLine + 1,
                "执行 *temp 时，程序尝试写入未知地址",
                Map.of("temp", "未知地址", "写入", "*a"),
                List.of(),
                true));

        return new AnimationResult(
                AnimationWorkflow.PYTHON_TUTOR.name(),
                "未初始化指针：解引用未知地址",
                candidate.note(),
                steps,
                Map.of(
                        "errorType", "INVALID_POINTER",
                        "sourceCode", sourceCode,
                        "correctedCode", "int temp = *a;\n*a = *b;\n*b = temp;",
                        "errorLine", anchorLine,
                        "dataStructure", "pointer"
                )
        );
    }

    private AnimationResult buildInfiniteLoop(AnimationCandidate candidate, int index) {
        CodeContext ctx = candidate.codeContext();
        String sourceCode = ctx == null ? candidate.anchor() : ctx.fullCode();
        int anchorLine = ctx == null ? 1 : ctx.relativeAnchorLine();

        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            steps.add(buildStep(i + 1, anchorLine,
                    "第 " + (i + 1) + " 次循环，条件始终为真，无法退出",
                    Map.of("iteration", String.valueOf(i + 1)),
                    List.of(),
                    false));
        }
        steps.add(buildStep(6, anchorLine,
                "循环无法终止，程序陷入死循环",
                Map.of("状态", "死循环"),
                List.of(),
                true));

        return new AnimationResult(
                AnimationWorkflow.PYTHON_TUTOR.name(),
                "死循环：循环终止条件永远为真",
                candidate.note(),
                steps,
                Map.of(
                        "errorType", "INFINITE_LOOP",
                        "sourceCode", sourceCode,
                        "errorLine", anchorLine,
                        "dataStructure", "loop"
                )
        );
    }

    private AnimationResult buildMemoryLeak(AnimationCandidate candidate, int index) {
        CodeContext ctx = candidate.codeContext();
        String sourceCode = ctx == null ? candidate.anchor() : ctx.fullCode();
        int anchorLine = ctx == null ? 1 : ctx.relativeAnchorLine();

        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(buildStep(1, anchorLine,
                "调用 malloc 分配内存",
                Map.of("heap", "已分配 1 块内存"),
                List.of(Map.of("label", "heap[0]", "value", "allocated", "active", true)),
                false));
        steps.add(buildStep(2, anchorLine + 1,
                "未调用 free 释放，程序退出后内存泄漏",
                Map.of("heap", "1 块内存未释放"),
                List.of(Map.of("label", "heap[0]", "value", "leaked", "active", true)),
                true));

        return new AnimationResult(
                AnimationWorkflow.PYTHON_TUTOR.name(),
                "内存泄漏：动态分配未释放",
                candidate.note(),
                steps,
                Map.of(
                        "errorType", "MEMORY_LEAK",
                        "sourceCode", sourceCode,
                        "correctedCode", "free(ptr);",
                        "errorLine", anchorLine,
                        "dataStructure", "heap"
                )
        );
    }

    private AnimationResult buildGenericCodeTrace(AnimationCandidate candidate, int index) {
        CodeContext ctx = candidate.codeContext();
        String sourceCode = ctx == null ? candidate.anchor() : ctx.fullCode();
        int anchorLine = ctx == null ? 1 : ctx.relativeAnchorLine();

        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(buildStep(1, anchorLine,
                "执行到错误语句",
                Map.of("状态", "运行中"),
                List.of(),
                false));
        steps.add(buildStep(2, anchorLine,
                candidate.note(),
                Map.of("状态", "异常"),
                List.of(),
                true));

        return new AnimationResult(
                AnimationWorkflow.PYTHON_TUTOR.name(),
                "代码执行错误",
                candidate.note(),
                steps,
                Map.of(
                        "errorType", "CODE_ERROR",
                        "sourceCode", sourceCode,
                        "errorLine", anchorLine,
                        "dataStructure", "code"
                )
        );
    }

    private ArrayBoundsParams extractArrayBoundsParams(AnimationCandidate candidate) {
        String anchor = candidate.anchor();
        CodeContext ctx = candidate.codeContext();
        String context = ctx == null ? anchor : ctx.fullCode();

        int arraySize = 5;
        int loopUpperBound = 5;
        boolean isInclusive = anchor != null && anchor.contains("<=");

        // 从 anchor 提取数组大小
        Matcher sizeMatcher = ARRAY_SIZE_PATTERN.matcher(anchor);
        if (sizeMatcher.find()) {
            String sizeStr = firstNonNull(sizeMatcher.group(1), sizeMatcher.group(2),
                    sizeMatcher.group(3), sizeMatcher.group(4));
            arraySize = parseIntOrDefault(sizeStr, arraySize);
        }

        // 从上下文提取数组字面量
        Matcher literalMatcher = ARRAY_LITERAL_PATTERN.matcher(context);
        if (literalMatcher.find()) {
            String[] values = literalMatcher.group(1).split(",");
            arraySize = values.length;
        }

        // 提取循环上界
        Matcher boundMatcher = LOOP_BOUND_PATTERN.matcher(anchor);
        if (boundMatcher.find()) {
            String bound = firstNonNull(boundMatcher.group(1), boundMatcher.group(2),
                    boundMatcher.group(3), boundMatcher.group(4));
            if (bound != null && bound.matches("\\d+")) {
                loopUpperBound = Integer.parseInt(bound);
            } else {
                loopUpperBound = arraySize;
            }
        }

        return new ArrayBoundsParams(arraySize, loopUpperBound, isInclusive);
    }

    private List<Map<String, Object>> buildMemoryCells(int arraySize, int activeIndex, boolean outOfBounds) {
        List<Map<String, Object>> cells = new ArrayList<>();
        for (int i = 0; i < arraySize; i++) {
            cells.add(Map.of(
                    "label", "arr[" + i + "]",
                    "value", String.valueOf((i + 1) * 10),
                    "active", i == activeIndex,
                    "outOfBounds", false
            ));
        }
        if (outOfBounds) {
            cells.add(Map.of(
                    "label", "arr[" + arraySize + "]",
                    "value", "越界",
                    "active", true,
                    "outOfBounds", true
            ));
        }
        return cells;
    }

    private Map<String, Object> buildStep(int order, int activeLine, String explanation,
                                          Map<String, String> variables,
                                          List<Map<String, Object>> memory, boolean error) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("order", order);
        step.put("line", activeLine);
        step.put("explanation", explanation);
        step.put("variables", variables);
        step.put("memory", memory);
        step.put("error", error);
        return step;
    }

    private String buildCorrectedLoop(String anchor, boolean isInclusive) {
        if (anchor == null) return "for (int i = 0; i < n; i++) { ... }";
        if (isInclusive) {
            return anchor.replace("<=", "<");
        }
        return anchor;
    }

    private String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private int parseIntOrDefault(String value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private record ArrayBoundsParams(int arraySize, int loopUpperBound, boolean isInclusive) {}
}
