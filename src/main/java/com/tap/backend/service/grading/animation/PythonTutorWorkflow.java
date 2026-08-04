package com.tap.backend.service.grading.animation;

import com.tap.backend.service.animation.AnimationAiClient;
import com.tap.backend.service.grading.animation.execution.CodeExecutionSandboxService;
import com.tap.backend.service.grading.animation.execution.ExecutionTrace;
import com.tap.backend.service.grading.animation.execution.TraceStep;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Python Tutor 式代码执行可视化工作流。
 * <p>
 * 基于真实代码执行得到的 trace，生成教学动画数据。
 * 不再使用以前基于规则手工构造步骤的伪执行方式。
 * <p>
 * 执行前会对从报告提取的代码做「可执行化补强」：
 * <ol>
 *   <li>规则级：补缺失的 #include 与未定义的大写宏（如 MAX、N）；</li>
 *   <li>LLM 级：缺少 main 时让大模型补一个真实调用学生函数、能触发批注错误的 main
 *       （含忠实度校验：原文行覆盖率 &gt;= 80% 且原文连续保留，否则回退空壳）；</li>
 *   <li>以上都失败时回退到最小 main 壳（保证可编译，交由上层回退策略处理）。</li>
 * </ol>
 */
@Component
public class PythonTutorWorkflow {

    private static final Logger log = LoggerFactory.getLogger(PythonTutorWorkflow.class);

    /** 提取代码中常见但无需补定义的标准宏/标识符。 */
    private static final Set<String> STD_MACROS = Set.of(
            "NULL", "EOF", "TRUE", "FALSE", "BUFSIZ", "STDIN", "STDOUT", "STDERR", "INT_MAX",
            "INT_MIN", "LONG_MAX", "SIZE_MAX", "RAND_MAX", "EXIT_SUCCESS", "EXIT_FAILURE");

    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:c|C|cpp)?\\s*([\\s\\S]*?)```");
    private static final Pattern DEFINE_PATTERN = Pattern.compile("#define\\s+([A-Za-z_]\\w*)");
    private static final Pattern MACRO_USE_PATTERN = Pattern.compile("\\b([A-Z][A-Z0-9_]{1,15})\\b");

    private static final String PREP_SYSTEM_PROMPT = """
            你是 C 代码"可执行化"助手。把从学生实验报告提取的（可能残缺的）C 代码片段，补成能编译、能运行、能触发指定错误的完整程序。

            ## 铁律
            1. 学生原始代码的每一行都必须【原样保留、顺序不变、一字不改】（包括其中的错误！绝对不要修复错误）。
            2. 只允许做两件事：
               a) 在【文件开头】补缺失的 #include 与 #define（如 MAX、N、SIZE 等宏给一个合理默认值）
               b) 在【文件末尾】追加一个 main 函数：构造小规模测试数据，【真实调用】学生代码里的函数
                  （例如给数组赋值后调用排序函数），让程序执行能够走到批注指出的错误行
            3. 不要修复学生代码里的错误，不要改变其逻辑，不要删除任何一行学生代码。
            4. 不要使用交互式输入（scanf 会卡住程序），测试数据直接写在 main 里。
            5. 只输出完整 C 代码：不要 markdown 代码块标记（```）、不要任何解释文字。
            """;

    private final CodeExecutionSandboxService sandboxService;
    private final AnimationAiClient aiClient;
    private final boolean llmCodePrepEnabled;

    public PythonTutorWorkflow(CodeExecutionSandboxService sandboxService,
                               AnimationAiClient aiClient,
                               @Value("${tap.grading.demo.llm-code-prep-enabled:true}") boolean llmCodePrepEnabled) {
        this.sandboxService = sandboxService;
        this.aiClient = aiClient;
        this.llmCodePrepEnabled = llmCodePrepEnabled;
    }

    public AnimationResult generate(AnimationCandidate candidate, int index) {
        CodeContext ctx = candidate.codeContext();
        String sourceCode = ctx == null ? candidate.anchor() : ctx.fullCode();
        int anchorLine = ctx == null ? 1 : ctx.relativeAnchorLine();
        ErrorPatternDetector.ErrorType errorType = candidate.detectedErrorType();
        if (errorType == null) {
            errorType = ErrorPatternDetector.ErrorType.GENERIC_HIGHLIGHT;
        }

        // 1. 真实执行代码（执行前做可执行化补强：规则补宏/头文件 + LLM 生成调用 main）
        ExecutablePrep prep = prepareExecutableCode(candidate, sourceCode);
        String executableCode = prep.code();
        int adjustedAnchorLine = anchorLine + prep.lineOffset();
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
        metadata.put("codeSource", ctx == null ? "raw" : ctx.source());
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

    // ------------------------------------------------------------------
    // 执行前代码补强
    // ------------------------------------------------------------------

    record ExecutablePrep(String code, int lineOffset) {}

    /**
     * 把提取的代码补强为可执行形态，并返回行号偏移（原文在补强代码中前移的行数）。
     *
     * <p>顺序：规则补 #include/#define → 缺 main 时 LLM 补调用 main（忠实度校验）→ 空壳回退。</p>
     */
    ExecutablePrep prepareExecutableCode(AnimationCandidate candidate, String sourceCode) {
        String rulePrep = ruleBasedPreparation(sourceCode);
        String executable;
        if (hasMainFunction(rulePrep)) {
            executable = rulePrep;
        } else {
            executable = llmPreparation(candidate, sourceCode, rulePrep);
            if (executable == null) {
                executable = appendMainShell(rulePrep);
            }
        }
        int lineOffset = countPrependedLines(sourceCode, executable);
        return new ExecutablePrep(executable, lineOffset);
    }

    /** 规则级补强：只允许在原文【开头】插入 #include 与 #define，不改动原文任何行。 */
    private String ruleBasedPreparation(String sourceCode) {
        StringBuilder prefix = new StringBuilder();
        if (!sourceCode.contains("#include <stdio.h>")
                && (sourceCode.contains("printf") || sourceCode.contains("scanf")
                    || sourceCode.contains("puts") || sourceCode.contains("gets"))) {
            prefix.append("#include <stdio.h>\n");
        }
        if (!sourceCode.contains("#include <stdlib.h>")
                && (sourceCode.contains("malloc") || sourceCode.contains("free")
                    || sourceCode.contains("atoi") || sourceCode.contains("exit"))) {
            prefix.append("#include <stdlib.h>\n");
        }
        if (!sourceCode.contains("#include <string.h>")
                && (sourceCode.contains("strcpy") || sourceCode.contains("strcat")
                    || sourceCode.contains("strlen") || sourceCode.contains("strcmp")
                    || sourceCode.contains("strncpy"))) {
            prefix.append("#include <string.h>\n");
        }
        for (String macro : findUndefinedMacros(sourceCode)) {
            prefix.append("#define ").append(macro).append(" 100\n");
        }
        return prefix.length() == 0 ? sourceCode : prefix + sourceCode;
    }

    /** 找出代码中使用但未定义的大写标识符（视为缺失宏）。 */
    private Set<String> findUndefinedMacros(String code) {
        Set<String> defined = new HashSet<>();
        Matcher def = DEFINE_PATTERN.matcher(code);
        while (def.find()) {
            defined.add(def.group(1));
        }
        Set<String> used = new HashSet<>();
        Matcher use = MACRO_USE_PATTERN.matcher(code);
        while (use.find()) {
            String name = use.group(1);
            if (!defined.contains(name) && !STD_MACROS.contains(name)) {
                used.add(name);
            }
        }
        return used;
    }

    /** LLM 补强：无 main 时让大模型补一个真实调用学生函数的 main。失败或失真返回 null。 */
    private String llmPreparation(AnimationCandidate candidate, String originalCode, String rulePrepCode) {
        if (!llmCodePrepEnabled || aiClient == null || !aiClient.isChatAvailable()) {
            return null;
        }
        String userPrompt = buildPrepPrompt(candidate, originalCode, rulePrepCode);
        try {
            String raw = aiClient.chat(PREP_SYSTEM_PROMPT, userPrompt, 0.2);
            String code = stripCodeBlock(raw);
            if (code == null || code.isBlank() || !code.contains("\n")) {
                return null;
            }
            // 忠实度校验：原文必须连续保留（保证行号可对齐），且原文行覆盖率 >= 80%
            if (!code.contains(originalCode)) {
                if (lineCoverage(code, originalCode) < 0.8) {
                    log.warn("LLM 代码补强失真，回退空壳 (candidate={})", candidate.anchor());
                    return null;
                }
                // 原文不连续：行号无法对齐，视为不可用
                log.warn("LLM 代码补强未连续保留原文，回退空壳 (candidate={})", candidate.anchor());
                return null;
            }
            log.info("LLM 代码补强成功: candidate={}, codeLength={}", candidate.anchor(), code.length());
            return code;
        } catch (Exception e) {
            log.warn("LLM 代码补强失败: {}", e.getMessage());
            return null;
        }
    }

    private String buildPrepPrompt(AnimationCandidate candidate, String originalCode, String rulePrepCode) {
        StringBuilder sb = new StringBuilder();
        ErrorPatternDetector.ErrorType errorType = candidate.detectedErrorType();
        if (errorType != null) {
            sb.append("【错误类型】").append(errorType.name()).append('\n');
        }
        if (candidate.note() != null && !candidate.note().isBlank()) {
            sb.append("【批注描述】").append(candidate.note()).append('\n');
        }
        if (candidate.anchor() != null && !candidate.anchor().isBlank()
                && !originalCode.contains(candidate.anchor())) {
            sb.append("【错误相关代码片段】\n").append(candidate.anchor()).append('\n');
        }
        sb.append("【已规则补强的代码（可直接在其基础上补充 main）】\n")
                .append(rulePrepCode)
                .append('\n')
                .append("【输出】完整 C 代码：");
        return sb.toString();
    }

    /** 去掉 LLM 可能包裹的 markdown 代码块标记。 */
    private String stripCodeBlock(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        Matcher matcher = JSON_BLOCK.matcher(trimmed);
        if (matcher.find()) {
            trimmed = matcher.group(1).trim();
        }
        return trimmed;
    }

    /** 原文非空行在补强结果中出现的比例（trim 后逐行匹配）。 */
    private double lineCoverage(String completed, String raw) {
        Set<String> completedLines = new HashSet<>();
        for (String line : completed.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                completedLines.add(t);
            }
        }
        int total = 0, hit = 0;
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            total++;
            if (completedLines.contains(t)) {
                hit++;
            }
        }
        return total == 0 ? 1.0 : (double) hit / total;
    }

    private boolean hasMainFunction(String code) {
        return code != null && (code.contains("int main(") || code.contains("void main("));
    }

    /**
     * 代码片段缺少 main 函数时，补一个最小 main 壳使其可编译执行。
     * <p>补壳只保证编译通过；有意义的执行轨迹依赖上游 LLM 补出的测试 main。</p>
     */
    private String appendMainShell(String sourceCode) {
        StringBuilder sb = new StringBuilder();
        if (!sourceCode.contains("#include")) {
            sb.append("#include <stdio.h>\n#include <stdlib.h>\n\n");
        }
        sb.append(sourceCode);
        sb.append("\n\n/* auto-added for trace */\nint main(void) {\n    return 0;\n}\n");
        return sb.toString();
    }

    /**
     * 计算补强后原始代码前方被插入的行数，用于对齐 trace 行号与锚点行。
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
