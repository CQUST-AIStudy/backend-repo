package com.tap.backend.service.animation;

import com.tap.backend.service.grading.animation.AnimationCandidate;
import com.tap.backend.service.grading.animation.AnimationResult;
import com.tap.backend.service.grading.animation.AnimationWorkflow;
import com.tap.backend.service.grading.animation.CodeContext;
import com.tap.backend.service.grading.animation.ConceptStepsWorkflow;
import com.tap.backend.service.grading.animation.ErrorPatternDetector;
import com.tap.backend.service.grading.animation.ProblemContext;
import com.tap.backend.service.grading.animation.execution.CodeExecutionSandboxService;
import com.tap.backend.service.grading.animation.execution.ExecutionTrace;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 动画合成组件：输入「代码 / stdin / 题面」→ 前端播放器可消费的 demonstration Map。
 * <p>
 * 真实执行（gcc 插桩沙箱）优先，失败/空轨迹时回退 LLM 概念分步动画（CONCEPT_STEPS）。
 * 不含任何持久化，供「按题演示」({@code StudentCodeDemoService}) 与「手动输入演示」
 * ({@code CodePlaygroundService}) 复用。
 */
@Component
public class CodeDemoComposer {

    private static final Logger log = LoggerFactory.getLogger(CodeDemoComposer.class);

    /** 从题面提取首个「输入样例」：兼容围栏代码块与纯文本，止于「输出样例」或结尾。 */
    private static final Pattern INPUT_SAMPLE = Pattern.compile(
            "输入样例[^\\n：:]*[：:]?\\s*(?:```[a-zA-Z]*\\s*)?([\\s\\S]*?)(?:```|输出样例|$)");

    private final CodeExecutionSandboxService sandboxService;
    private final ConceptStepsWorkflow conceptStepsWorkflow;
    private final AnimationAiClient aiClient;

    public CodeDemoComposer(CodeExecutionSandboxService sandboxService,
                            ConceptStepsWorkflow conceptStepsWorkflow,
                            AnimationAiClient aiClient) {
        this.sandboxService = sandboxService;
        this.conceptStepsWorkflow = conceptStepsWorkflow;
        this.aiClient = aiClient;
    }

    /**
     * 合成一段代码的执行/错误演示。真实执行优先（缺 main 的片段补最小 main 壳），
     * 失败/空轨迹时回退 LLM 概念分步动画。
     *
     * @param experimentId 供 LLM 兜底的 ProblemContext 使用；无实验语境可传 {@code null}
     */
    public Map<String, Object> buildDemonstration(String code, String stdin, String title, Long experimentId) {
        // 1. 真实执行优先（缺 main 的片段补最小 main 壳）
        String executable = ensureMainFunction(code);
        try {
            ExecutionTrace trace = sandboxService.execute("c", executable, stdin);
            if (trace.success()) {
                List<Map<String, Object>> frames = trace.toFrameList();
                if (!frames.isEmpty()) {
                    return demonstration(
                            title,
                            "逐行展示这段代码的真实执行过程。",
                            executable,
                            "",
                            0,
                            frames,
                            AnimationWorkflow.PYTHON_TUTOR.name());
                }
            }
            log.info("代码演示真实执行无有效轨迹，回退 LLM：success={}", trace.success());
        } catch (RuntimeException e) {
            log.warn("代码演示真实执行异常，回退 LLM：{}", e.getMessage());
        }

        // 2. LLM 概念分步兜底
        AnimationResult result = conceptStepsWorkflow.generate(buildCandidate(code, title, experimentId), 0);
        Object metaSource = result.metadata().get("sourceCode");
        String sourceCode = metaSource != null && !metaSource.toString().isBlank() ? metaSource.toString() : code;
        return demonstration(
                firstNonBlank(result.title(), title),
                result.explanation(),
                sourceCode,
                String.valueOf(result.metadata().getOrDefault("correctedCode", "")),
                toInt(result.metadata().get("errorLine")),
                result.frames(),
                result.workflow());
    }

    private AnimationCandidate buildCandidate(String code, String title, Long experimentId) {
        List<String> lines = Arrays.asList(code.split("\n", -1));
        CodeContext codeContext = new CodeContext(lines, 1, lines.size(), 1, lines.size());
        ProblemContext problemContext = new ProblemContext(experimentId, title, "", List.of(), null, null);
        AnimationCandidate.AnnotationInfo annotation = new AnimationCandidate.AnnotationInfo(
                "GENERIC", null, code, "演示这段代码的执行过程", false);
        return new AnimationCandidate(null, annotation, null, codeContext, problemContext,
                ErrorPatternDetector.ErrorType.GENERIC_HIGHLIGHT);
    }

    private Map<String, Object> demonstration(String title, String explanation, String sourceCode,
                                              String correctedCode, int errorLine, Object frames, String workflow) {
        Map<String, Object> demo = new LinkedHashMap<>();
        demo.put("id", "code-demo");
        demo.put("title", title);
        demo.put("explanation", explanation == null ? "" : explanation);
        demo.put("sourceCode", sourceCode);
        demo.put("correctedCode", correctedCode == null ? "" : correctedCode);
        demo.put("errorLine", errorLine);
        demo.put("frames", frames == null ? List.of() : frames);
        demo.put("workflow", workflow);
        demo.put("highlightStartLine", 0);
        demo.put("highlightEndLine", 0);
        return demo;
    }

    /**
     * 代码片段缺少 main 函数时补最小 main 壳，保证可编译执行。
     * 逻辑照搬 PythonTutorWorkflow.ensureMainFunction。
     */
    String ensureMainFunction(String sourceCode) {
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
     * 无用户输入时自动决定 stdin：优先调用 LLM 按题意+代码生成一组合法输入，
     * LLM 不可用/失败时回退题面「输入样例」解析。
     */
    public String autoStdin(String statementMd, String code) {
        String llm = llmGenerateStdin(statementMd, code);
        if (!llm.isBlank()) {
            return llm;
        }
        return resolveStdin(statementMd);
    }

    /** 从题面解析首个「输入样例」作为默认 stdin；解析不到返回空串。 */
    String resolveStdin(String statementMd) {
        if (statementMd == null || statementMd.isBlank()) {
            return "";
        }
        Matcher m = INPUT_SAMPLE.matcher(statementMd);
        if (m.find()) {
            String sample = m.group(1);
            if (sample != null) {
                return cleanSample(sample);
            }
        }
        return "";
    }

    /**
     * 清洗题面样例：去除模板占位说明（如「在这里给出一组输入。例如：」），提取真正的输入数据。
     * <p>PTA 题面常为模板 stub：真实样例往往跟在「例如：」之后，或前置一行以冒号结尾的提示语。
     */
    private String cleanSample(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (s.isEmpty()) {
            return "";
        }
        // 真实样例常跟在「例如：/例如:」之后，取其后内容
        int idx = Math.max(s.lastIndexOf("例如："), s.lastIndexOf("例如:"));
        if (idx >= 0) {
            s = s.substring(idx + 3).strip();
        }
        // 丢弃开头以冒号结尾的提示行（如「在这里给出一组输入。」）与空行
        String[] lines = s.split("\n", -1);
        StringBuilder kept = new StringBuilder();
        boolean started = false;
        for (String line : lines) {
            String t = line.strip();
            if (!started) {
                if (t.isEmpty() || t.endsWith("：") || t.endsWith(":")) {
                    continue;
                }
                started = true;
            }
            if (kept.length() > 0) {
                kept.append("\n");
            }
            kept.append(line);
        }
        return kept.toString().strip();
    }

    /** 调用 LLM 依据题目描述与代码构造一组可直接喂给程序的合法 stdin；失败返回空串。 */
    private String llmGenerateStdin(String statementMd, String code) {
        if (statementMd == null || statementMd.isBlank() || !aiClient.isChatAvailable()) {
            return "";
        }
        try {
            String system = "你是编程题的输入构造器。根据题目描述与程序代码，构造一组能让程序正常运行、"
                    + "符合题意的合法标准输入(stdin)。严格要求：只输出输入内容本身（可包含多行与空格），"
                    + "不要输出任何解释、标签、引号或 Markdown 代码块围栏。";
            String user = "题目描述：\n" + truncate(statementMd, 4000)
                    + "\n\n程序代码：\n" + truncate(code, 4000)
                    + "\n\n请只输出一组可直接作为 stdin 的输入：";
            return sanitizeStdin(aiClient.chat(system, user, 0.2));
        } catch (RuntimeException e) {
            log.warn("LLM 生成 stdin 失败，回退题面解析：{}", e.getMessage());
            return "";
        }
    }

    /** 清洗 LLM 返回：去除代码块围栏与首尾空白，限制长度。 */
    private static String sanitizeStdin(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            s = nl >= 0 ? s.substring(nl + 1) : s.substring(3);
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
        }
        s = s.strip();
        if (s.length() > 2000) {
            s = s.substring(0, 2000);
        }
        return s.strip();
    }

    private static int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 0;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
