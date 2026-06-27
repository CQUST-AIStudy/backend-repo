package com.tap.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.grading.EvidenceBlockEntity;
import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.domain.grading.ScoreItemEntity;
import com.tap.backend.service.grading.animation.AnimationCandidate;
import com.tap.backend.service.grading.animation.AnimationResult;
import com.tap.backend.service.grading.animation.AnimationWorkflow;
import com.tap.backend.service.grading.animation.AnimationWorkflowRouter;
import com.tap.backend.service.grading.animation.CodeContext;
import com.tap.backend.service.grading.animation.CodeHighlightAnimationWorkflow;
import com.tap.backend.service.grading.animation.CodeContextExtractor;
import com.tap.backend.service.grading.animation.ErrorPatternDetector;
import com.tap.backend.service.grading.animation.ErrorPatternDetector.ErrorType;
import com.tap.backend.service.grading.animation.GenericHighlightWorkflow;
import com.tap.backend.service.grading.animation.HtmlAnimationWorkflow;
import com.tap.backend.service.grading.animation.ProblemContext;
import com.tap.backend.service.grading.animation.ProblemContextResolver;
import com.tap.backend.service.grading.animation.PythonTutorWorkflow;
import com.tap.backend.service.grading.animation.ResultCompareWorkflow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 基于 AI 批注生成错误演示动画。
 * 支持多种工作流：Python Tutor 式代码执行可视化、AI 生成 HTML 动画、图文对比、通用高亮。
 */
@Service
public class GradingErrorDemonstrationService {

    private static final int MAX_DEMONSTRATIONS = 4;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProblemContextResolver problemContextResolver;
    private final CodeContextExtractor codeContextExtractor;
    private final ErrorPatternDetector errorPatternDetector;
    private final AnimationWorkflowRouter router;
    private final CodeHighlightAnimationWorkflow codeHighlightWorkflow;
    private final PythonTutorWorkflow pythonTutorWorkflow;
    private final HtmlAnimationWorkflow htmlAnimationWorkflow;
    private final ResultCompareWorkflow resultCompareWorkflow;
    private final GenericHighlightWorkflow genericHighlightWorkflow;

    public GradingErrorDemonstrationService(ProblemContextResolver problemContextResolver,
                                            CodeContextExtractor codeContextExtractor,
                                            ErrorPatternDetector errorPatternDetector,
                                            AnimationWorkflowRouter router,
                                            CodeHighlightAnimationWorkflow codeHighlightWorkflow,
                                            PythonTutorWorkflow pythonTutorWorkflow,
                                            HtmlAnimationWorkflow htmlAnimationWorkflow,
                                            ResultCompareWorkflow resultCompareWorkflow,
                                            GenericHighlightWorkflow genericHighlightWorkflow) {
        this.problemContextResolver = problemContextResolver;
        this.codeContextExtractor = codeContextExtractor;
        this.errorPatternDetector = errorPatternDetector;
        this.router = router;
        this.codeHighlightWorkflow = codeHighlightWorkflow;
        this.pythonTutorWorkflow = pythonTutorWorkflow;
        this.htmlAnimationWorkflow = htmlAnimationWorkflow;
        this.resultCompareWorkflow = resultCompareWorkflow;
        this.genericHighlightWorkflow = genericHighlightWorkflow;
    }

    /**
     * 基于评分项和证据块生成错误演示动画。
     *
     * @param submission     当前提交（用于获取题目上下文）
     * @param scoreItems     评分项列表
     * @param evidenceBlocks 证据块列表
     * @return 错误演示列表
     */
    public List<ErrorDemonstration> buildDemonstrations(
            GradingSubmissionEntity submission,
            List<ScoreItemEntity> scoreItems,
            List<EvidenceBlockEntity> evidenceBlocks) {

        ProblemContext problemContext = problemContextResolver.resolve(
                submission == null ? null : submission.getTask());
        List<AnimationCandidate> candidates = collectCandidates(scoreItems, evidenceBlocks, problemContext);

        List<ErrorDemonstration> result = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (result.size() >= MAX_DEMONSTRATIONS) {
                break;
            }
            AnimationCandidate candidate = candidates.get(i);
            AnimationWorkflow workflow = router.route(candidate);
            AnimationResult animationResult = executeWorkflow(workflow, candidate, result.size() + 1);
            List<?> frames = animationResult.frames();
            boolean isCodeHighlight = workflow == AnimationWorkflow.CODE_HIGHLIGHT;
            if ((frames == null || frames.isEmpty()) && !isCodeHighlight) {
                continue;
            }
            ErrorDemonstration demo = toErrorDemonstration(candidate, animationResult, result.size() + 1);
            if (isUnique(result, demo)) {
                result.add(demo);
            }
        }

        return result;
    }

    private List<AnimationCandidate> collectCandidates(
            List<ScoreItemEntity> scoreItems,
            List<EvidenceBlockEntity> evidenceBlocks,
            ProblemContext problemContext) {

        if (scoreItems == null || evidenceBlocks == null) {
            return List.of();
        }

        Map<String, EvidenceBlockEntity> evidenceById = evidenceBlocks.stream()
                .filter(eb -> eb.getEvidenceId() != null)
                .collect(Collectors.toMap(EvidenceBlockEntity::getEvidenceId, eb -> eb, (a, b) -> a));

        List<AnimationCandidate> candidates = new ArrayList<>();
        for (ScoreItemEntity item : scoreItems) {
            List<AnimationCandidate.AnnotationInfo> annotations = parseAnnotations(item.getAnnotationsJson());
            for (AnimationCandidate.AnnotationInfo ann : annotations) {
                EvidenceBlockEntity block = evidenceById.get(ann.evidenceId());
                if (block == null || block.getContent() == null) {
                    continue;
                }
                String evidenceKind = block.getKind() == null ? "text" : block.getKind().name();
                ErrorType errorType = errorPatternDetector.detect(ann.anchorText(), ann.note(), evidenceKind);
                if (errorType == null || errorType == ErrorType.GENERIC_HIGHLIGHT) {
                    continue; // 无法识别为明确错误类型，不生成动画
                }
                CodeContext codeContext = codeContextExtractor.extract(block.getContent(), ann.anchorText());
                if (errorPatternDetector.isCodeError(errorType)
                        && (codeContext == null || codeContext.fullLines().isEmpty())) {
                    continue; // 代码类错误必须能在证据中定位 anchor
                }
                candidates.add(new AnimationCandidate(item, ann, block, codeContext, problemContext, errorType));
            }
        }
        return candidates;
    }

    private List<AnimationCandidate.AnnotationInfo> parseAnnotations(String annotationsJson) {
        if (annotationsJson == null || annotationsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(annotationsJson);
            if (!root.isArray()) {
                return List.of();
            }
            List<AnimationCandidate.AnnotationInfo> result = new ArrayList<>();
            for (JsonNode node : root) {
                String type = text(node, "type").toUpperCase(Locale.ROOT);
                if ("CHECK".equals(type)) {
                    continue; // 只处理错误/警告
                }
                String anchor = firstNonBlank(text(node, "anchorText"), text(node, "anchor_text"));
                String note = text(node, "note");
                String evidenceId = firstNonBlank(text(node, "evidenceId"), text(node, "evidence_id"));
                boolean wavy = node.path("wavy").asBoolean(false);
                if ((anchor != null || note != null) && evidenceId != null && !evidenceId.isBlank()) {
                    result.add(new AnimationCandidate.AnnotationInfo(type, evidenceId, anchor, note, wavy));
                }
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private AnimationResult executeWorkflow(AnimationWorkflow workflow, AnimationCandidate candidate, int index) {
        return switch (workflow) {
            case CODE_HIGHLIGHT -> codeHighlightWorkflow.generate(candidate, index);
            case PYTHON_TUTOR -> pythonTutorWorkflow.generate(candidate, index);
            case HTML_ANIMATION -> htmlAnimationWorkflow.generate(candidate, index);
            case RESULT_COMPARE -> resultCompareWorkflow.generate(candidate, index);
            case GENERIC_HIGHLIGHT -> genericHighlightWorkflow.generate(candidate, index);
        };
    }

    private ErrorDemonstration toErrorDemonstration(AnimationCandidate candidate,
                                                    AnimationResult result,
                                                    int index) {
        CodeContext ctx = candidate.codeContext();
        String sourceCode = ctx == null ? candidate.anchor() : ctx.fullCode();
        int anchorLine = ctx == null ? 1 : ctx.relativeAnchorLine();
        int highlightStart = ctx == null ? 1 : ctx.highlightStartLine();
        int highlightEnd = ctx == null ? 1 : ctx.highlightEndLine();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> frames = (List<Map<String, Object>>) result.frames();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errorRanges = (List<Map<String, Object>>) result.metadata().getOrDefault("errorRanges", List.of());
        String popupHtml = result.metadata().getOrDefault("popupHtml", "").toString();

        return new ErrorDemonstration(
                "error-" + index,
                extractErrorType(result),
                result.title(),
                result.explanation(),
                sourceCode,
                result.metadata().getOrDefault("correctedCode", "").toString(),
                anchorLine,
                frames,
                result.workflow(),
                highlightStart,
                highlightEnd,
                candidate.problemContext(),
                anchorLine,
                errorRanges,
                popupHtml
        );
    }

    private String extractErrorType(AnimationResult result) {
        Object errorType = result.metadata().get("errorType");
        return errorType != null ? errorType.toString() : "GENERIC";
    }

    private boolean isUnique(List<ErrorDemonstration> existing, ErrorDemonstration demo) {
        return existing.stream().noneMatch(e ->
                e.errorType().equals(demo.errorType())
                        && e.sourceCode().equals(demo.sourceCode()));
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record ErrorDemonstration(
            String id,
            String errorType,
            String title,
            String explanation,
            String sourceCode,
            String correctedCode,
            int errorLine,
            List<Map<String, Object>> frames,
            String workflow,
            int highlightStartLine,
            int highlightEndLine,
            ProblemContext problemContext,
            int anchorLineInEvidence,
            List<Map<String, Object>> errorRanges,
            String popupHtml
    ) {
    }

    public record TraceStep(
            int order,
            String explanation,
            int activeLine,
            Map<String, String> variables,
            List<MemoryCell> memory,
            boolean error
    ) {
        public TraceStep {
            variables = variables == null ? Map.of() : new LinkedHashMap<>(variables);
            memory = memory == null ? List.of() : List.copyOf(memory);
        }
    }

    public record MemoryCell(String label, String value, boolean active, boolean outOfBounds) {}
}
