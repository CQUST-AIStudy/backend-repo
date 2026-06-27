package com.tap.backend.service.grading.animation;

import com.tap.backend.domain.grading.EvidenceBlockEntity;
import com.tap.backend.domain.grading.ScoreItemEntity;
import com.tap.backend.service.grading.animation.ErrorPatternDetector.ErrorType;

/**
 * 错误演示动画的候选对象，包含批注、证据块、代码上下文和题目上下文。
 */
public record AnimationCandidate(
        ScoreItemEntity sourceItem,
        AnnotationInfo annotation,
        EvidenceBlockEntity evidenceBlock,
        CodeContext codeContext,
        ProblemContext problemContext,
        ErrorType detectedErrorType
) {
    public String anchor() {
        return annotation.anchorText();
    }

    public String note() {
        return annotation.note();
    }

    public String type() {
        return annotation.type();
    }

    public String evidenceId() {
        return annotation.evidenceId();
    }

    /**
     * 从 annotation 提取的精简信息。
     */
    public record AnnotationInfo(
            String type,
            String evidenceId,
            String anchorText,
            String note,
            boolean wavy
    ) {}
}
