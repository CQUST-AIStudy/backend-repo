package com.tap.backend.service.grading.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tap.backend.domain.grading.EvidenceBlockEntity;
import com.tap.backend.domain.grading.EvidenceKind;
import com.tap.backend.service.grading.animation.ErrorPatternDetector.ErrorType;
import org.junit.jupiter.api.Test;

class AnimationWorkflowRouterTest {

    private final AnimationWorkflowRouter router = new AnimationWorkflowRouter();

    @Test
    void routesCodeErrorsToCodeHighlight() {
        AnimationCandidate candidate = candidate(ErrorType.ARRAY_BOUNDS, "for (i=0; i<=n; i++)", "ocr");
        assertEquals(AnimationWorkflow.CODE_HIGHLIGHT, router.route(candidate));
    }

    @Test
    void routesResultMismatchToResultCompare() {
        AnimationCandidate candidate = candidate(ErrorType.RESULT_MISMATCH, "output", "text");
        assertEquals(AnimationWorkflow.RESULT_COMPARE, router.route(candidate));
    }

    @Test
    void routesConceptToHtmlAnimation() {
        AnimationCandidate candidate = candidate(ErrorType.CONCEPT, "递归", "text");
        assertEquals(AnimationWorkflow.HTML_ANIMATION, router.route(candidate));
    }

    @Test
    void routesUnknownTextToHtmlOrGeneric() {
        AnimationCandidate candidate = candidate(null, "需要理解指针原理", "text");
        assertEquals(AnimationWorkflow.HTML_ANIMATION, router.route(candidate));
    }

    private AnimationCandidate candidate(ErrorType type, String anchor, String kind) {
        EvidenceBlockEntity block = mock(EvidenceBlockEntity.class);
        when(block.getKind()).thenReturn(EvidenceKind.valueOf(kind));
        AnimationCandidate.AnnotationInfo annotation = new AnimationCandidate.AnnotationInfo(
                "ERROR", "ev1", anchor, "note", false);
        return new AnimationCandidate(null, annotation, block, null, null, type);
    }
}
