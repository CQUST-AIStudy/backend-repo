package com.tap.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.grading.EvidenceBlockEntity;
import com.tap.backend.domain.grading.EvidenceKind;
import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.domain.grading.ScoreItemEntity;
import com.tap.backend.service.animation.AnimationAiClient;
import com.tap.backend.service.grading.animation.AnimationWorkflowRouter;
import com.tap.backend.service.grading.animation.CodeContextExtractor;
import com.tap.backend.service.grading.animation.CodeHighlightAnimationWorkflow;
import com.tap.backend.service.grading.animation.CommentIssueExtractor;
import com.tap.backend.service.grading.animation.ErrorParameterExtractor;
import com.tap.backend.service.grading.animation.ErrorPatternDetector;
import com.tap.backend.service.grading.animation.GenericHighlightWorkflow;
import com.tap.backend.service.grading.animation.HtmlAnimationWorkflow;
import com.tap.backend.service.grading.animation.LLMCodeExtractor;
import com.tap.backend.service.grading.animation.ProblemContext;
import com.tap.backend.service.grading.animation.ProblemContextResolver;
import com.tap.backend.service.grading.animation.PythonTutorWorkflow;
import com.tap.backend.service.grading.animation.ResultCompareWorkflow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GradingErrorDemonstrationServiceTest {

    private GradingErrorDemonstrationService service;

    @BeforeEach
    void setUp() {
        ProblemContextResolver problemContextResolver = mock(ProblemContextResolver.class);
        when(problemContextResolver.resolve(null)).thenReturn(ProblemContext.empty());

        ErrorPatternDetector detector = new ErrorPatternDetector();
        CodeContextExtractor codeContextExtractor = new CodeContextExtractor();
        AnimationWorkflowRouter router = new AnimationWorkflowRouter();
        LLMCodeExtractor llmCodeExtractor = mock(LLMCodeExtractor.class);
        when(llmCodeExtractor.extractFullCode(null, List.of())).thenReturn(Map.of());
        CommentIssueExtractor commentIssueExtractor = mock(CommentIssueExtractor.class);
        when(commentIssueExtractor.extractIssues(null, List.of(), null, List.of())).thenReturn(List.of());

        PythonTutorWorkflow pythonTutorWorkflow = new PythonTutorWorkflow(new ErrorParameterExtractor());

        AnimationAiClient aiClient = mock(AnimationAiClient.class);
        when(aiClient.isChatAvailable()).thenReturn(false);
        CodeHighlightAnimationWorkflow codeHighlightWorkflow = new CodeHighlightAnimationWorkflow(aiClient, new ObjectMapper());
        HtmlAnimationWorkflow htmlAnimationWorkflow = new HtmlAnimationWorkflow(aiClient, new ObjectMapper());
        ResultCompareWorkflow resultCompareWorkflow = new ResultCompareWorkflow();
        GenericHighlightWorkflow genericHighlightWorkflow = new GenericHighlightWorkflow();

        service = new GradingErrorDemonstrationService(
                problemContextResolver,
                codeContextExtractor,
                detector,
                router,
                llmCodeExtractor,
                commentIssueExtractor,
                codeHighlightWorkflow,
                pythonTutorWorkflow,
                htmlAnimationWorkflow,
                resultCompareWorkflow,
                genericHighlightWorkflow
        );
    }

    @Test
    void arrayBoundsAnnotation_generatesDemonstration() {
        ScoreItemEntity score = scoreWithAnnotations("""
                [{"evidence_id":"ev-1","type":"CROSS","anchor_text":"for (int i = 0; i <= n; i++)","note":"数组越界","wavy":false}]
                """);
        EvidenceBlockEntity block = block("ev-1", EvidenceKind.text, """
                int arr[5];
                for (int i = 0; i <= n; i++) {
                    printf("%d\\n", arr[i]);
                }
                """);

        List<GradingErrorDemonstrationService.ErrorDemonstration> demos =
                service.buildDemonstrations(new GradingSubmissionEntity(), List.of(score), List.of(block));

        assertEquals(1, demos.size());
        assertEquals("ARRAY_BOUNDS", demos.get(0).errorType());
        assertEquals("CODE_HIGHLIGHT", demos.get(0).workflow());
    }

    @Test
    void invalidPointerAnnotation_generatesDemonstration() {
        ScoreItemEntity score = scoreWithAnnotations("""
                [{"evidence_id":"ev-2","type":"CROSS","anchor_text":"int *temp;","note":"使用了未初始化指针","wavy":false}]
                """);
        EvidenceBlockEntity block = block("ev-2", EvidenceKind.text, """
                void swap(int *a, int *b) {
                    int *temp;
                    *temp = *a;
                }
                """);

        List<GradingErrorDemonstrationService.ErrorDemonstration> demos =
                service.buildDemonstrations(new GradingSubmissionEntity(), List.of(score), List.of(block));

        assertEquals(1, demos.size());
        assertEquals("INVALID_POINTER", demos.get(0).errorType());
        assertEquals("CODE_HIGHLIGHT", demos.get(0).workflow());
    }

    @Test
    void runtimeErrorAnnotation_generatesDemonstration() {
        ScoreItemEntity score = scoreWithAnnotations("""
                [{"evidence_id":"ev-3","type":"CROSS","anchor_text":"divide(0)","note":"运行时异常终止","wavy":false}]
                """);
        EvidenceBlockEntity block = block("ev-3", EvidenceKind.text, """
                int divide(int x) {
                    return 100 / x;
                }
                divide(0);
                """);

        List<GradingErrorDemonstrationService.ErrorDemonstration> demos =
                service.buildDemonstrations(new GradingSubmissionEntity(), List.of(score), List.of(block));

        assertEquals(1, demos.size());
        assertEquals("RUNTIME_ERROR", demos.get(0).errorType());
        assertEquals("CODE_HIGHLIGHT", demos.get(0).workflow());
    }

    @Test
    void conceptAnnotation_withCode_routesToCodeHighlight() {
        ScoreItemEntity score = scoreWithAnnotations("""
                [{"evidence_id":"ev-4","type":"CROSS","anchor_text":"递归","note":"请理解递归调用过程","wavy":false}]
                """);
        EvidenceBlockEntity block = block("ev-4", EvidenceKind.text, """
                int fib(int n) {
                    return fib(n - 1) + fib(n - 2);
                }
                """);

        List<GradingErrorDemonstrationService.ErrorDemonstration> demos =
                service.buildDemonstrations(new GradingSubmissionEntity(), List.of(score), List.of(block));

        assertEquals(1, demos.size());
        assertEquals("CODE_HIGHLIGHT", demos.get(0).workflow());
        assertTrue(demos.get(0).sourceCode().contains("int fib"));
    }

    @Test
    void missingDebugAnalysis_withCode_showsFullCode() {
        ScoreItemEntity score = scoreWithAnnotations("""
                [{"evidence_id":"ev-dbg","type":"CROSS","anchor_text":"仅描述结果","note":"仅描述结果，未分析调试过程","wavy":false}]
                """);
        EvidenceBlockEntity block = block("ev-dbg", EvidenceKind.text, """
                BiTree search(BiTree T, int key) {
                    if (T == NULL) return NULL;
                    if (key < T->data) return search(T->rchild, key);
                    if (key > T->data) return search(T->lchild, key);
                    return T;
                }
                """);

        List<GradingErrorDemonstrationService.ErrorDemonstration> demos =
                service.buildDemonstrations(new GradingSubmissionEntity(), List.of(score), List.of(block));

        assertEquals(1, demos.size());
        assertEquals("CODE_HIGHLIGHT", demos.get(0).workflow());
        assertTrue(demos.get(0).sourceCode().contains("BiTree search"));
    }

    @Test
    void unrelatedErrorAnnotation_generatesNothing() {
        ScoreItemEntity score = scoreWithAnnotations("""
                [{"evidence_id":"ev-5","type":"CROSS","anchor_text":"printf","note":"代码风格不符合规范","wavy":false}]
                """);
        EvidenceBlockEntity block = block("ev-5", EvidenceKind.text, """
                int main() {
                    printf("hello");
                    return 0;
                }
                """);

        List<GradingErrorDemonstrationService.ErrorDemonstration> demos =
                service.buildDemonstrations(new GradingSubmissionEntity(), List.of(score), List.of(block));

        assertTrue(demos.isEmpty());
    }

    @Test
    void anchorNotLocatable_butCodeBlock_fallsBackToFullCode() {
        ScoreItemEntity score = scoreWithAnnotations("""
                [{"evidence_id":"ev-6","type":"CROSS","anchor_text":"这段代码越界了","note":"数组越界","wavy":false}]
                """);
        EvidenceBlockEntity block = block("ev-6", EvidenceKind.text, """
                int arr[5];
                for (int i = 0; i <= 5; i++) {
                    arr[i] = i;
                }
                """);

        List<GradingErrorDemonstrationService.ErrorDemonstration> demos =
                service.buildDemonstrations(new GradingSubmissionEntity(), List.of(score), List.of(block));

        assertEquals(1, demos.size(), "Should fallback to full code block when anchor cannot be located");
        assertEquals("CODE_HIGHLIGHT", demos.get(0).workflow());
        assertTrue(demos.get(0).sourceCode().contains("int arr[5]"));
    }

    @Test
    void commentFallback_doesNotGenerateAnimation_whenNoEvidence() {
        ScoreItemEntity score = new ScoreItemEntity();
        score.setComment("数组越界：for (int i = 0; i <= n; i++)");
        score.setAnnotationsJson(null);

        List<GradingErrorDemonstrationService.ErrorDemonstration> demos =
                service.buildDemonstrations(new GradingSubmissionEntity(), List.of(score), List.of());

        assertTrue(demos.isEmpty(), "Should not generate animation from comments without evidence");
    }

    @Test
    void commentIssue_withCodeEvidence_generatesCodeHighlight() {
        CommentIssueExtractor commentIssueExtractor = mock(CommentIssueExtractor.class);


        GradingErrorDemonstrationService customService = buildServiceWithCommentExtractor(commentIssueExtractor);

        EvidenceBlockEntity block = block("ev-bst", EvidenceKind.text, """
                BiTree search(BiTree T, int key) {
                    if (T == NULL) return NULL;
                    if (key < T->data) return search(T->rchild, key);
                    if (key > T->data) return search(T->lchild, key);
                    return T;
                }
                """);
        when(commentIssueExtractor.extractIssues(any(), anyList(), any(), anyList()))
                .thenReturn(List.of(new CommentIssueExtractor.CommentIssue(
                        "ev-bst", "search(T->rchild, key)", "左右子树递归方向写反了", "LOGIC_ERROR")));

        ScoreItemEntity score = new ScoreItemEntity();
        score.setComment("BST search 左右子树写反了");

        List<GradingErrorDemonstrationService.ErrorDemonstration> demos =
                customService.buildDemonstrations(new GradingSubmissionEntity(), List.of(score), List.of(block));

        assertEquals(1, demos.size());
        assertEquals("CODE_HIGHLIGHT", demos.get(0).workflow());
        assertTrue(demos.get(0).sourceCode().contains("BiTree search"));
    }

    private GradingErrorDemonstrationService buildServiceWithCommentExtractor(CommentIssueExtractor extractor) {
        ProblemContextResolver problemContextResolver = mock(ProblemContextResolver.class);
        when(problemContextResolver.resolve(null)).thenReturn(ProblemContext.empty());
        ErrorPatternDetector detector = new ErrorPatternDetector();
        CodeContextExtractor codeContextExtractor = new CodeContextExtractor();
        AnimationWorkflowRouter router = new AnimationWorkflowRouter();
        LLMCodeExtractor llmCodeExtractor = mock(LLMCodeExtractor.class);
        when(llmCodeExtractor.extractFullCode(null, List.of())).thenReturn(Map.of());

        AnimationAiClient aiClient = mock(AnimationAiClient.class);
        when(aiClient.isChatAvailable()).thenReturn(false);
        CodeHighlightAnimationWorkflow codeHighlightWorkflow = new CodeHighlightAnimationWorkflow(aiClient, new ObjectMapper());
        HtmlAnimationWorkflow htmlAnimationWorkflow = new HtmlAnimationWorkflow(aiClient, new ObjectMapper());
        PythonTutorWorkflow pythonTutorWorkflow = new PythonTutorWorkflow(new ErrorParameterExtractor());
        ResultCompareWorkflow resultCompareWorkflow = new ResultCompareWorkflow();
        GenericHighlightWorkflow genericHighlightWorkflow = new GenericHighlightWorkflow();

        return new GradingErrorDemonstrationService(
                problemContextResolver,
                codeContextExtractor,
                detector,
                router,
                llmCodeExtractor,
                extractor,
                codeHighlightWorkflow,
                pythonTutorWorkflow,
                htmlAnimationWorkflow,
                resultCompareWorkflow,
                genericHighlightWorkflow
        );
    }

    private ScoreItemEntity scoreWithAnnotations(String json) {
        ScoreItemEntity score = new ScoreItemEntity();
        score.setAnnotationsJson(json);
        return score;
    }

    private EvidenceBlockEntity block(String evidenceId, EvidenceKind kind, String content) {
        EvidenceBlockEntity block = new EvidenceBlockEntity();
        block.setEvidenceId(evidenceId);
        block.setKind(kind);
        block.setContent(content);
        return block;
    }
}
