package com.tap.backend.service.grading.animation;

import com.tap.backend.service.grading.animation.ErrorPatternDetector.ErrorType;
import com.tap.backend.service.grading.animation.execution.CodeExecutionSandboxService;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 根据错误类型和证据特征选择动画工作流。
 * <p>
 * 路由策略：
 * <ul>
 *   <li>代码类错误（数组越界、指针、死循环等）且能提取到可执行代码时，优先使用 PYTHON_TUTOR 真实执行可视化。</li>
 *   <li>PYTHON_TUTOR 执行失败时，由 {@link PythonTutorWorkflow} 内部回退到 CODE_HIGHLIGHT。</li>
 *   <li>结果/数据类错误使用 RESULT_COMPARE。</li>
 *   <li>概念/原理类错误使用 HTML_ANIMATION。</li>
 *   <li>其他情况使用 CODE_HIGHLIGHT 或 GENERIC_HIGHLIGHT 兜底。</li>
 * </ul>
 */
@Component
public class AnimationWorkflowRouter {

    private static final String[] CODE_KEYWORDS = {
            "for", "while", "if", "else", "switch", "int ", "char ", "float ",
            "double ", "void ", "struct ", "return", "break", "continue",
            "malloc", "free", "printf", "scanf", "arr[", "*", "&", "->"
    };

    private final CodeExecutionSandboxService sandboxService;

    public AnimationWorkflowRouter(CodeExecutionSandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    public AnimationWorkflow route(AnimationCandidate candidate) {
        ErrorType errorType = candidate.detectedErrorType();

        // 1. 代码类错误优先走 PYTHON_TUTOR 真实执行可视化
        if (isCodeError(errorType) && hasExecutableCode(candidate)) {
            if (sandboxService.isAvailable("c")) {
                return AnimationWorkflow.PYTHON_TUTOR;
            }
        }

        // 2. 无法真实执行时，回退到代码高亮 + D3 弹窗
        if (candidate.codeContext() != null && !candidate.codeContext().fullLines().isEmpty()) {
            return AnimationWorkflow.CODE_HIGHLIGHT;
        }

        // 3. 按错误类型选择其他工作流
        if (errorType != null) {
            return switch (errorType) {
                case RESULT_MISMATCH -> AnimationWorkflow.RESULT_COMPARE;
                case CONCEPT -> AnimationWorkflow.HTML_ANIMATION;
                case ARRAY_BOUNDS, INVALID_POINTER, INFINITE_LOOP, MEMORY_LEAK,
                        RECURSION, RUNTIME_ERROR, TYPE_ERROR, LOGIC_ERROR -> AnimationWorkflow.CODE_HIGHLIGHT;
                default -> defaultRoute(candidate);
            };
        }

        return defaultRoute(candidate);
    }

    private boolean isCodeError(ErrorType errorType) {
        if (errorType == null) {
            return false;
        }
        return switch (errorType) {
            case ARRAY_BOUNDS, INVALID_POINTER, INFINITE_LOOP, MEMORY_LEAK,
                    RECURSION, RUNTIME_ERROR, TYPE_ERROR, LOGIC_ERROR -> true;
            default -> false;
        };
    }

    private boolean hasExecutableCode(AnimationCandidate candidate) {
        CodeContext ctx = candidate.codeContext();
        if (ctx == null || ctx.fullCode() == null || ctx.fullCode().isBlank()) {
            return false;
        }
        String code = ctx.fullCode();
        // 目前支持 C 语言单文件程序，需要包含 main 函数
        return code.contains("int main(") && code.length() < 20000;
    }

    private AnimationWorkflow defaultRoute(AnimationCandidate candidate) {
        String anchor = candidate.anchor();
        String note = candidate.note();
        String evidenceKind = candidate.evidenceBlock().getKind() == null
                ? "text"
                : candidate.evidenceBlock().getKind().name();
        String combined = (anchor + " " + note).toLowerCase(Locale.ROOT);

        if (looksLikeCode(anchor) || ("ocr".equalsIgnoreCase(evidenceKind) && looksLikeCode(anchor))) {
            return AnimationWorkflow.CODE_HIGHLIGHT;
        }
        if (containsAny(combined, new String[]{"结果", "输出", "图表", "数据", "对比", "差异", "不匹配", "不符"})) {
            return AnimationWorkflow.RESULT_COMPARE;
        }
        if ("text".equalsIgnoreCase(evidenceKind) || "vlm".equalsIgnoreCase(evidenceKind)) {
            return AnimationWorkflow.HTML_ANIMATION;
        }
        return AnimationWorkflow.GENERIC_HIGHLIGHT;
    }

    private boolean looksLikeCode(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String keyword : CODE_KEYWORDS) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String text, String[] keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
