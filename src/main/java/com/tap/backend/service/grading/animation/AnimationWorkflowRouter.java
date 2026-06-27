package com.tap.backend.service.grading.animation;

import com.tap.backend.service.grading.animation.ErrorPatternDetector.ErrorType;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 根据错误类型和证据特征选择动画工作流。
 */
@Component
public class AnimationWorkflowRouter {

    private static final String[] CODE_KEYWORDS = {
            "for", "while", "if", "else", "switch", "int ", "char ", "float ",
            "double ", "void ", "struct ", "return", "break", "continue",
            "malloc", "free", "printf", "scanf", "arr[", "*", "&", "->"
    };

    public AnimationWorkflow route(AnimationCandidate candidate) {
        ErrorType errorType = candidate.detectedErrorType();
        if (errorType == null) {
            return defaultRoute(candidate);
        }

        return switch (errorType) {
            case ARRAY_BOUNDS, INVALID_POINTER, INFINITE_LOOP, MEMORY_LEAK,
                 RECURSION, RUNTIME_ERROR, TYPE_ERROR, LOGIC_ERROR -> AnimationWorkflow.CODE_HIGHLIGHT;
            case RESULT_MISMATCH -> AnimationWorkflow.RESULT_COMPARE;
            case CONCEPT -> AnimationWorkflow.HTML_ANIMATION;
            default -> defaultRoute(candidate);
        };
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
