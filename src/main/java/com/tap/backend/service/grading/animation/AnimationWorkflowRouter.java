package com.tap.backend.service.grading.animation;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 根据错误类型选择对应的动画工作流。
 */
@Component
public class AnimationWorkflowRouter {

    private static final String[] CODE_KEYWORDS = {
            "for", "while", "if", "else", "switch", "int ", "char ", "float ",
            "double ", "void ", "struct ", "return", "break", "continue",
            "malloc", "free", "printf", "scanf", "arr[", "*", "&", "->"
    };

    private static final String[] RESULT_KEYWORDS = {
            "结果", "输出", "图表", "数据", "对比", "差异", "预期", "实际",
            "不正确", "不匹配", "不符", "错误结果"
    };

    private static final String[] CONCEPT_KEYWORDS = {
            "原理", "概念", "理解", "思想", "本质", "机制", "模型",
            "复杂度", "时间复杂度", "空间复杂度", "递归", "指针"
    };

    /**
     * 根据候选对象判断应使用哪种工作流。
     */
    public AnimationWorkflow route(AnimationCandidate candidate) {
        String anchor = candidate.anchor();
        String note = candidate.note();
        String evidenceKind = candidate.evidenceBlock().getKind() == null
                ? "text"
                : candidate.evidenceBlock().getKind().name();
        String combined = (anchor + " " + note).toLowerCase(Locale.ROOT);

        // 1. 代码类错误：anchor 包含代码关键字，或证据块是代码截图/OCR
        if (looksLikeCode(anchor) || isCodeEvidence(evidenceKind)) {
            return AnimationWorkflow.PYTHON_TUTOR;
        }

        // 2. 结果分析类错误
        if (containsAny(combined, RESULT_KEYWORDS)) {
            return AnimationWorkflow.RESULT_COMPARE;
        }

        // 3. 概念/原理类错误
        if (containsAny(combined, CONCEPT_KEYWORDS) || "text".equals(evidenceKind) || "vlm".equals(evidenceKind)) {
            return AnimationWorkflow.HTML_ANIMATION;
        }

        // 4. 兜底
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

    private boolean isCodeEvidence(String evidenceKind) {
        return "ocr".equalsIgnoreCase(evidenceKind)
                || "vlm".equalsIgnoreCase(evidenceKind)
                || "image".equalsIgnoreCase(evidenceKind);
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
