package com.tap.backend.service.grading.animation;

import com.tap.backend.service.grading.animation.execution.CodeExecutionSandboxService;
import com.tap.backend.service.grading.animation.execution.ExecutionTrace;
import com.tap.backend.service.grading.animation.execution.TraceStep;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Python Tutor 式代码执行可视化工作流。
 * <p>
 * 基于真实代码执行得到的 trace，生成教学动画数据。
 * 不再使用以前基于规则手工构造步骤的伪执行方式。
 */
@Component
public class PythonTutorWorkflow {

    private static final Logger log = LoggerFactory.getLogger(PythonTutorWorkflow.class);

    private final CodeExecutionSandboxService sandboxService;

    public PythonTutorWorkflow(CodeExecutionSandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    public AnimationResult generate(AnimationCandidate candidate, int index) {
        CodeContext ctx = candidate.codeContext();
        String sourceCode = ctx == null ? candidate.anchor() : ctx.fullCode();
        int anchorLine = ctx == null ? 1 : ctx.relativeAnchorLine();
        ErrorPatternDetector.ErrorType errorType = candidate.detectedErrorType();
        if (errorType == null) {
            errorType = ErrorPatternDetector.ErrorType.GENERIC_HIGHLIGHT;
        }

        // 1. 真实执行代码（缺 main 的片段自动补最小 main 壳，保证可编译）
        String executableCode = ensureMainFunction(sourceCode);
        int lineOffset = countPrependedLines(sourceCode, executableCode);
        int adjustedAnchorLine = anchorLine + lineOffset;
        ExecutionTrace trace = sandboxService.execute("c", executableCode, buildStdin(candidate));

        if (!trace.success()) {
            log.warn("PYTHON_TUTOR 真实执行失败，回退: {}", trace.errorMessage());
            return fallbackResult(candidate, anchorLine, trace.errorMessage());
        }

        // 2. 找到错误步骤（trace 行号基于 executableCode，需用平移后的锚点行）
        int errorStepIndex = findErrorStepIndex(trace, adjustedAnchorLine, errorType);

        // 3. 生成解释文本
        String explanation = buildExplanation(candidate, errorType, trace, errorStepIndex);
        String correctedCode = buildCorrectedCode(candidate, errorType);

        // 4. 组装结果（展示代码使用实际执行的代码，与 trace 行号对齐）
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("errorType", errorType.name());
        metadata.put("dataStructure", mapToDataStructure(errorType));
        metadata.put("sourceCode", executableCode);
        metadata.put("correctedCode", correctedCode);
        metadata.put("errorLine", adjustedAnchorLine);
        metadata.put("errorStepIndex", errorStepIndex);
        metadata.put("trace", trace.toFrameList());

        return new AnimationResult(
                AnimationWorkflow.PYTHON_TUTOR.name(),
                buildTitle(candidate, errorType),
                explanation,
                trace.toFrameList(),
                metadata
        );
    }

    private int findErrorStepIndex(ExecutionTrace trace, int anchorLine,
                                   ErrorPatternDetector.ErrorType errorType) {
        List<TraceStep> steps = trace.steps();
        if (steps == null || steps.isEmpty()) {
            return -1;
        }

        // 优先使用 trace 中标记为 error 的步骤
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).error()) {
                return i;
            }
        }

        // 否则回退到 anchor 所在行对应的步骤
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).line() == anchorLine) {
                return i;
            }
        }

        // 对于数组越界，尝试找到 i >= n 的那一步
        if (errorType == ErrorPatternDetector.ErrorType.ARRAY_BOUNDS) {
            Integer n = extractIntVariable(steps, "n");
            if (n != null) {
                for (int i = 0; i < steps.size(); i++) {
                    Integer idx = extractIntVariable(steps.subList(0, i + 1), "i");
                    if (idx != null && idx >= n) {
                        return i;
                    }
                }
            }
        }

        return steps.size() - 1;
    }

    private String buildStdin(AnimationCandidate candidate) {
        // 如果题目或实验上下文提供了输入数据，可以在这里注入。
        // 目前先返回空输入，后续可从 problemContext 或测试用例中提取。
        return "";
    }

    /**
     * 代码片段缺少 main 函数时，补一个最小 main 壳使其可编译执行。
     * <p>补壳只保证编译通过（结构体/函数片段也能拿到编译诊断），
     * 有意义的执行轨迹依赖上游 LLM 提取时补出的测试 main。</p>
     */
    private String ensureMainFunction(String sourceCode) {
        if (sourceCode == null || sourceCode.contains("int main(") || sourceCode.contains("void main(")) {
            return sourceCode;
        }
        StringBuilder sb = new StringBuilder();
        if (!sourceCode.contains("#include")) {
            sb.append("#include <stdio.h>\n#include <stdlib.h>\n\n");
        }
        sb.append(sourceCode);
        sb.append("\n\n/* auto-added for trace */\nint main(void) {\n    return 0;\n}\n");
        return sb.toString();
    }

    /**
     * 计算补壳后原始代码前方被插入的行数，用于对齐 trace 行号与锚点行。
     */
    private int countPrependedLines(String sourceCode, String executableCode) {
        if (sourceCode == null || executableCode == null || executableCode.equals(sourceCode)) {
            return 0;
        }
        int idx = executableCode.indexOf(sourceCode);
        if (idx <= 0) {
            return 0;
        }
        return (int) executableCode.substring(0, idx).chars().filter(c -> c == '\n').count();
    }

    private String buildExplanation(AnimationCandidate candidate,
                                    ErrorPatternDetector.ErrorType errorType,
                                    ExecutionTrace trace,
                                    int errorStepIndex) {
        ProblemContext problem = candidate.problemContext();
        String title = problem == null || problem.experimentTitle() == null
                ? "该实验" : "「" + problem.experimentTitle() + "」";

        String base = candidate.note();
        if (base == null || base.isBlank()) {
            base = switch (errorType) {
                case ARRAY_BOUNDS -> "数组访问超出了合法范围";
                case INVALID_POINTER -> "指针未指向有效内存";
                case INFINITE_LOOP -> "程序陷入死循环";
                case MEMORY_LEAK -> "动态分配的内存未释放";
                case RECURSION -> "递归调用缺少终止条件或层次过深";
                case RUNTIME_ERROR -> "程序运行时异常";
                case TYPE_ERROR -> "类型不匹配";
                case LOGIC_ERROR -> "逻辑错误导致结果不正确";
                default -> "代码执行出现错误";
            };
        }

        String stepInfo = "";
        List<TraceStep> steps = trace.steps();
        if (errorStepIndex >= 0 && errorStepIndex < steps.size()) {
            TraceStep step = steps.get(errorStepIndex);
            stepInfo = String.format("（执行到第 %d 步，源代码第 %d 行）", step.step(), step.line());
        }

        return title + "中，" + base + stepInfo;
    }

    private String buildCorrectedCode(AnimationCandidate candidate,
                                      ErrorPatternDetector.ErrorType errorType) {
        CodeContext ctx = candidate.codeContext();
        String anchor = candidate.anchor();
        String sourceCode = ctx == null ? anchor : ctx.fullCode();

        return switch (errorType) {
            case ARRAY_BOUNDS -> {
                // 把 <= n 改为 < n，或把 i < n+1 改为 i < n
                String corrected = sourceCode.replace("<= n", "< n");
                if (corrected.equals(sourceCode)) {
                    corrected = sourceCode.replace("< n + 1", "< n");
                }
                yield corrected.equals(sourceCode) ? "请检查数组访问下标是否越界" : corrected;
            }
            case INVALID_POINTER -> "请确保指针在使用前已指向有效内存，例如：\nint *p = &target;\n*p = value;";
            case INFINITE_LOOP -> "请检查循环变量是否在循环体内更新，确保终止条件最终成立。";
            case MEMORY_LEAK -> "请在不再使用动态内存时调用 free(ptr)，并将 ptr 置为 NULL。";
            case RECURSION -> "请确保递归函数有明确的终止条件，并控制递归深度。";
            case RUNTIME_ERROR -> "请根据异常信息检查输入、类型和运行环境。";
            case TYPE_ERROR -> "请检查类型匹配，必要时进行显式类型转换。";
            case LOGIC_ERROR -> "请重新梳理解题逻辑，验证边界条件。";
            default -> candidate.note() != null ? candidate.note() : "请根据批注修正代码。";
        };
    }

    private AnimationResult fallbackResult(AnimationCandidate candidate, int anchorLine, String reason) {
        CodeContext ctx = candidate.codeContext();
        String sourceCode = ctx == null ? candidate.anchor() : ctx.fullCode();
        ErrorPatternDetector.ErrorType errorType = candidate.detectedErrorType();
        if (errorType == null) {
            errorType = ErrorPatternDetector.ErrorType.GENERIC_HIGHLIGHT;
        }

        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(buildStep(1, anchorLine, "执行到错误位置", Map.of(), Map.of("dataStructure", "code", "nodes", List.of(), "edges", List.of()), true));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("errorType", errorType.name());
        metadata.put("dataStructure", "code");
        metadata.put("sourceCode", sourceCode);
        metadata.put("correctedCode", candidate.note());
        metadata.put("errorLine", anchorLine);
        metadata.put("errorStepIndex", 0);
        metadata.put("trace", steps);
        metadata.put("fallbackReason", reason);

        return new AnimationResult(
                AnimationWorkflow.PYTHON_TUTOR.name(),
                buildTitle(candidate, errorType),
                candidate.note() != null ? candidate.note() : "真实执行失败：" + reason,
                steps,
                metadata
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

    private String buildTitle(AnimationCandidate candidate, ErrorPatternDetector.ErrorType type) {
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

    private String mapToDataStructure(ErrorPatternDetector.ErrorType type) {
        if (type == null) return "code";
        return switch (type) {
            case ARRAY_BOUNDS -> "array";
            case INVALID_POINTER -> "pointer";
            case INFINITE_LOOP -> "loop";
            case MEMORY_LEAK -> "heap";
            case RECURSION -> "tree";
            default -> "code";
        };
    }

    @SuppressWarnings("unchecked")
    private Integer extractIntVariable(List<TraceStep> steps, String varName) {
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        TraceStep last = steps.get(steps.size() - 1);
        Object value = last.locals().get(varName);
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }
}
