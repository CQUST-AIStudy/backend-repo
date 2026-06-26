package com.tap.backend.service.grading.animation;

import com.tap.backend.service.grading.animation.ErrorParameterExtractor.ArrayBoundsParams;
import com.tap.backend.service.grading.animation.ErrorParameterExtractor.PointerParams;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Python Tutor 式代码执行可视化工作流。
 * 基于真实 anchor_text 和完整代码上下文提取参数，生成 trace。
 */
@Component
public class PythonTutorWorkflow {

    private final ErrorParameterExtractor parameterExtractor;

    public PythonTutorWorkflow(ErrorParameterExtractor parameterExtractor) {
        this.parameterExtractor = parameterExtractor;
    }

    public AnimationResult generate(AnimationCandidate candidate, int index) {
        ErrorPatternDetector.ErrorType type = candidate.detectedErrorType();
        if (type == null) {
            type = ErrorPatternDetector.ErrorType.GENERIC_HIGHLIGHT;
        }

        return switch (type) {
            case ARRAY_BOUNDS -> buildArrayBounds(candidate);
            case INVALID_POINTER -> buildInvalidPointer(candidate);
            case INFINITE_LOOP -> buildInfiniteLoop(candidate);
            case MEMORY_LEAK -> buildMemoryLeak(candidate);
            case RECURSION -> buildRecursion(candidate);
            case RUNTIME_ERROR -> buildRuntimeError(candidate);
            case TYPE_ERROR -> buildTypeError(candidate);
            case LOGIC_ERROR -> buildLogicError(candidate);
            default -> buildGenericCodeTrace(candidate);
        };
    }

    private AnimationResult buildArrayBounds(AnimationCandidate candidate) {
        CodeContext ctx = candidate.codeContext();
        String anchor = candidate.anchor();
        String contextCode = ctx == null ? anchor : ctx.fullCode();
        ArrayBoundsParams params = parameterExtractor.extractArrayBounds(anchor, contextCode);
        List<String> values = parameterExtractor.extractArrayLiteralValues(contextCode);
        int arraySize = params.arraySize();

        List<Map<String, Object>> steps = new ArrayList<>();
        int anchorLine = ctx == null ? 1 : ctx.relativeAnchorLine();

        for (int i = 0; i < arraySize; i++) {
            steps.add(buildStep(i + 1, anchorLine,
                    "i = " + i + "，访问 arr[" + i + "]",
                    Map.of("i", String.valueOf(i), "n", String.valueOf(arraySize)),
                    arrayState(values, arraySize, i, false),
                    false));
        }

        if (params.isInclusive() && params.loopUpperBound() >= arraySize) {
            steps.add(buildStep(arraySize + 1, anchorLine,
                    "条件 i <= " + params.loopUpperBound() + " 仍成立，访问 arr[" + arraySize + "] 导致越界",
                    Map.of("i", String.valueOf(arraySize), "n", String.valueOf(arraySize)),
                    arrayState(values, arraySize, arraySize, true),
                    true));
        }

        String corrected = buildCorrectedLoop(anchor, params.isInclusive());
        return new AnimationResult(
                AnimationWorkflow.PYTHON_TUTOR.name(),
                "数组越界：循环终止条件有误",
                buildExplanation(candidate, "数组越界"),
                steps,
                Map.of(
                        "errorType", "ARRAY_BOUNDS",
                        "dataStructure", "array",
                        "sourceCode", ctx == null ? anchor : ctx.fullCode(),
                        "correctedCode", corrected,
                        "errorLine", anchorLine,
                        "arraySize", arraySize
                )
        );
    }

    private AnimationResult buildInvalidPointer(AnimationCandidate candidate) {
        CodeContext ctx = candidate.codeContext();
        String anchor = candidate.anchor();
        String contextCode = ctx == null ? anchor : ctx.fullCode();
        PointerParams params = parameterExtractor.extractPointer(anchor, contextCode);
        int anchorLine = ctx == null ? 1 : ctx.relativeAnchorLine();

        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(buildStep(1, anchorLine,
                "声明指针 " + params.pointerVar() + "，但尚未指向有效内存",
                Map.of(params.pointerVar(), "未初始化"),
                pointerState(params.pointerVar(), null, false),
                false));
        steps.add(buildStep(2, anchorLine + 1,
                "执行 *" + params.dereferenceVar() + " 时，程序尝试访问未知地址",
                Map.of(params.pointerVar(), "未知地址"),
                pointerState(params.pointerVar(), "未知地址", true),
                true));

        return new AnimationResult(
                AnimationWorkflow.PYTHON_TUTOR.name(),
                "未初始化指针：解引用未知地址",
                buildExplanation(candidate, "空指针/未初始化指针"),
                steps,
                Map.of(
                        "errorType", "INVALID_POINTER",
                        "dataStructure", "pointer",
                        "sourceCode", ctx == null ? anchor : ctx.fullCode(),
                        "correctedCode", "int " + params.pointerVar() + " = &target;\n*" + params.pointerVar() + " = value;",
                        "errorLine", anchorLine
                )
        );
    }

    private AnimationResult buildInfiniteLoop(AnimationCandidate candidate) {
        CodeContext ctx = candidate.codeContext();
        String anchor = candidate.anchor();
        int anchorLine = ctx == null ? 1 : ctx.relativeAnchorLine();
        ErrorParameterExtractor.LoopParams params = parameterExtractor.extractLoop(anchor);

        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            steps.add(buildStep(i + 1, anchorLine,
                    "第 " + (i + 1) + " 次迭代，循环条件仍成立：" + params.condition(),
                    Map.of("iteration", String.valueOf(i + 1)),
                    loopState(params.condition(), false),
                    false));
        }
        steps.add(buildStep(6, anchorLine,
                "循环条件始终为真，程序无法退出循环",
                Map.of("状态", "死循环"),
                loopState(params.condition(), true),
                true));

        return new AnimationResult(
                AnimationWorkflow.PYTHON_TUTOR.name(),
                "死循环：循环终止条件永远为真",
                buildExplanation(candidate, "死循环"),
                steps,
                Map.of(
                        "errorType", "INFINITE_LOOP",
                        "dataStructure", "loop",
                        "sourceCode", ctx == null ? anchor : ctx.fullCode(),
                        "correctedCode", "检查循环变量更新，确保条件最终能变为假",
                        "errorLine", anchorLine
                )
        );
    }

    private AnimationResult buildMemoryLeak(AnimationCandidate candidate) {
        CodeContext ctx = candidate.codeContext();
        String anchor = candidate.anchor();
        int anchorLine = ctx == null ? 1 : ctx.relativeAnchorLine();

        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(buildStep(1, anchorLine,
                "调用 malloc 分配了一块堆内存",
                Map.of("heap", "已分配 1 块"),
                heapState(1, false),
                false));
        steps.add(buildStep(2, anchorLine + 1,
                "程序退出前没有调用 free 释放，造成内存泄漏",
                Map.of("heap", "1 块未释放"),
                heapState(1, true),
                true));

        return new AnimationResult(
                AnimationWorkflow.PYTHON_TUTOR.name(),
                "内存泄漏：动态分配未释放",
                buildExplanation(candidate, "内存泄漏"),
                steps,
                Map.of(
                        "errorType", "MEMORY_LEAK",
                        "dataStructure", "heap",
                        "sourceCode", ctx == null ? anchor : ctx.fullCode(),
                        "correctedCode", "free(ptr);\nptr = NULL;",
                        "errorLine", anchorLine
                )
        );
    }

    private AnimationResult buildRecursion(AnimationCandidate candidate) {
        CodeContext ctx = candidate.codeContext();
        String anchor = candidate.anchor();
        int anchorLine = ctx == null ? 1 : ctx.relativeAnchorLine();

        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            steps.add(buildStep(i + 1, anchorLine,
                    "递归调用第 " + (i + 1) + " 层",
                    Map.of("depth", String.valueOf(i + 1), "call", "f(" + (i + 1) + ")"),
                    recursionState(i + 1, false),
                    false));
        }
        steps.add(buildStep(5, anchorLine,
                "递归层次过深或缺少有效终止条件，可能导致栈溢出",
                Map.of("depth", "过深", "call", "..."),
                recursionState(4, true),
                true));

        return new AnimationResult(
                AnimationWorkflow.PYTHON_TUTOR.name(),
                "递归错误：终止条件或递归深度问题",
                buildExplanation(candidate, "递归错误"),
                steps,
                Map.of(
                        "errorType", "RECURSION",
                        "dataStructure", "tree",
                        "sourceCode", ctx == null ? anchor : ctx.fullCode(),
                        "correctedCode", "确保递归有明确的终止条件，并控制递归深度",
                        "errorLine", anchorLine
                )
        );
    }

    private AnimationResult buildRuntimeError(AnimationCandidate candidate) {
        CodeContext ctx = candidate.codeContext();
        String anchor = candidate.anchor();
        int anchorLine = ctx == null ? 1 : ctx.relativeAnchorLine();

        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(buildStep(1, anchorLine,
                "程序执行到该语句",
                Map.of("状态", "运行中"),
                genericState(false),
                false));
        steps.add(buildStep(2, anchorLine,
                "运行时异常触发，程序无法继续",
                Map.of("状态", "异常终止"),
                genericState(true),
                true));

        return new AnimationResult(
                AnimationWorkflow.PYTHON_TUTOR.name(),
                "运行时错误",
                buildExplanation(candidate, "运行时错误"),
                steps,
                Map.of(
                        "errorType", "RUNTIME_ERROR",
                        "dataStructure", "code",
                        "sourceCode", ctx == null ? anchor : ctx.fullCode(),
                        "correctedCode", "请根据异常信息检查该语句的输入和环境",
                        "errorLine", anchorLine
                )
        );
    }

    private AnimationResult buildTypeError(AnimationCandidate candidate) {
        return buildSimpleError(candidate, "类型错误", "TYPE_ERROR", "code",
                "检查类型匹配，必要时进行显式类型转换");
    }

    private AnimationResult buildLogicError(AnimationCandidate candidate) {
        return buildSimpleError(candidate, "逻辑错误", "LOGIC_ERROR", "code",
                "重新梳理解题逻辑，验证边界条件");
    }

    private AnimationResult buildGenericCodeTrace(AnimationCandidate candidate) {
        return buildSimpleError(candidate, "代码执行错误", "CODE_ERROR", "code",
                "请根据批注修正该语句后重新运行");
    }

    private AnimationResult buildSimpleError(AnimationCandidate candidate,
                                             String title,
                                             String errorType,
                                             String dataStructure,
                                             String corrected) {
        CodeContext ctx = candidate.codeContext();
        String anchor = candidate.anchor();
        int anchorLine = ctx == null ? 1 : ctx.relativeAnchorLine();

        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(buildStep(1, anchorLine,
                "执行到错误语句",
                Map.of("状态", "运行中"),
                genericState(false),
                false));
        steps.add(buildStep(2, anchorLine,
                candidate.note(),
                Map.of("状态", "异常"),
                genericState(true),
                true));

        return new AnimationResult(
                AnimationWorkflow.PYTHON_TUTOR.name(),
                title,
                buildExplanation(candidate, title),
                steps,
                Map.of(
                        "errorType", errorType,
                        "dataStructure", dataStructure,
                        "sourceCode", ctx == null ? anchor : ctx.fullCode(),
                        "correctedCode", corrected,
                        "errorLine", anchorLine
                )
        );
    }

    private Map<String, Object> buildStep(int order, int activeLine, String explanation,
                                          Map<String, String> variables,
                                          Map<String, Object> state, boolean error) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("order", order);
        step.put("line", activeLine);
        step.put("explanation", explanation);
        step.put("variables", variables);
        step.put("state", state);
        step.put("error", error);
        return step;
    }

    private Map<String, Object> arrayState(List<String> values, int size, int activeIndex, boolean outOfBounds) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            nodes.add(Map.of(
                    "id", "arr" + i,
                    "label", "arr[" + i + "]",
                    "value", i < values.size() ? values.get(i) : String.valueOf((i + 1) * 10),
                    "active", i == activeIndex,
                    "outOfBounds", false,
                    "index", i
            ));
        }
        if (outOfBounds) {
            nodes.add(Map.of(
                    "id", "arr" + size,
                    "label", "arr[" + size + "]",
                    "value", "越界",
                    "active", true,
                    "outOfBounds", true,
                    "index", size
            ));
        }
        return Map.of("dataStructure", "array", "nodes", nodes, "edges", List.of());
    }

    private Map<String, Object> pointerState(String pointerVar, String target, boolean error) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(Map.of("id", "ptr", "label", pointerVar, "value", target == null ? "?" : target, "active", true));
        if (target != null) {
            nodes.add(Map.of("id", "target", "label", target, "value", "未知", "active", error));
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        if (target != null) {
            edges.add(Map.of("from", "ptr", "to", "target", "label", "指向"));
        }
        return Map.of("dataStructure", "pointer", "nodes", nodes, "edges", edges);
    }

    private Map<String, Object> loopState(String condition, boolean error) {
        List<Map<String, Object>> nodes = List.of(
                Map.of("id", "loop", "label", "循环", "value", condition, "active", true)
        );
        return Map.of("dataStructure", "loop", "nodes", nodes, "edges", List.of());
    }

    private Map<String, Object> heapState(int blocks, boolean leaked) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int i = 0; i < blocks; i++) {
            nodes.add(Map.of("id", "heap" + i, "label", "heap[" + i + "]", "value", leaked ? "未释放" : "已分配", "active", true));
        }
        return Map.of("dataStructure", "heap", "nodes", nodes, "edges", List.of());
    }

    private Map<String, Object> recursionState(int depth, boolean error) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        for (int i = 0; i < depth; i++) {
            nodes.add(Map.of("id", "call" + i, "label", "f(" + (i + 1) + ")", "value", "调用中", "active", i == depth - 1));
            if (i > 0) {
                edges.add(Map.of("from", "call" + (i - 1), "to", "call" + i, "label", "调用"));
            }
        }
        return Map.of("dataStructure", "tree", "nodes", nodes, "edges", edges);
    }

    private Map<String, Object> genericState(boolean error) {
        return Map.of("dataStructure", "code", "nodes", List.of(), "edges", List.of());
    }

    private String buildExplanation(AnimationCandidate candidate, String errorLabel) {
        ProblemContext problem = candidate.problemContext();
        String title = problem == null || problem.experimentTitle() == null
                ? "该实验" : "「" + problem.experimentTitle() + "」";
        return title + "中，" + errorLabel + "：" + candidate.note();
    }

    private String buildCorrectedLoop(String anchor, boolean isInclusive) {
        if (anchor == null) return "for (int i = 0; i < n; i++) { ... }";
        if (isInclusive) {
            return anchor.replace("<=", "<");
        }
        return anchor;
    }
}
