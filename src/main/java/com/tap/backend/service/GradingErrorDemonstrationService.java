package com.tap.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.grading.EvidenceBlockEntity;
import com.tap.backend.domain.grading.EvidenceKind;
import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.domain.grading.ScoreItemEntity;
import com.tap.backend.service.grading.animation.AnimationCandidate;
import com.tap.backend.service.grading.animation.AnimationResult;
import com.tap.backend.service.grading.animation.AnimationWorkflow;
import com.tap.backend.service.grading.animation.AnimationWorkflowRouter;
import com.tap.backend.service.grading.animation.CodeContext;
import com.tap.backend.service.grading.animation.CodeContextExtractor;
import com.tap.backend.service.grading.animation.CommentIssueExtractor;
import com.tap.backend.service.grading.animation.ConceptStepsWorkflow;
import com.tap.backend.service.grading.animation.ErrorPatternDetector;
import com.tap.backend.service.grading.animation.ErrorPatternDetector.ErrorType;
import com.tap.backend.service.grading.animation.LLMCodeExtractor;
import com.tap.backend.service.grading.animation.ProblemContext;
import com.tap.backend.service.grading.animation.ProblemContextResolver;
import com.tap.backend.service.grading.animation.PythonTutorWorkflow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 基于 AI 批注生成错误演示动画。
 * 支持多种工作流：Python Tutor 式代码执行可视化、AI 生成 HTML 动画、图文对比、通用高亮。
 */
@Service
public class GradingErrorDemonstrationService {

    private static final Logger log = LoggerFactory.getLogger(GradingErrorDemonstrationService.class);

    private static final int MAX_DEMONSTRATIONS = 4;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProblemContextResolver problemContextResolver;
    private final CodeContextExtractor codeContextExtractor;
    private final ErrorPatternDetector errorPatternDetector;
    private final AnimationWorkflowRouter router;
    private final LLMCodeExtractor llmCodeExtractor;
    private final CommentIssueExtractor commentIssueExtractor;
    private final PythonTutorWorkflow pythonTutorWorkflow;
    private final ConceptStepsWorkflow conceptStepsWorkflow;

    public GradingErrorDemonstrationService(ProblemContextResolver problemContextResolver,
                                            CodeContextExtractor codeContextExtractor,
                                            ErrorPatternDetector errorPatternDetector,
                                            AnimationWorkflowRouter router,
                                            LLMCodeExtractor llmCodeExtractor,
                                            CommentIssueExtractor commentIssueExtractor,
                                            PythonTutorWorkflow pythonTutorWorkflow,
                                            ConceptStepsWorkflow conceptStepsWorkflow) {
        this.problemContextResolver = problemContextResolver;
        this.codeContextExtractor = codeContextExtractor;
        this.errorPatternDetector = errorPatternDetector;
        this.router = router;
        this.llmCodeExtractor = llmCodeExtractor;
        this.commentIssueExtractor = commentIssueExtractor;
        this.pythonTutorWorkflow = pythonTutorWorkflow;
        this.conceptStepsWorkflow = conceptStepsWorkflow;
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

        // 优先使用 code 类型证据块生成动画，避免整页报告文字被当作代码展示
        List<EvidenceBlockEntity> codeBlocks = evidenceBlocks == null ? List.of()
                : evidenceBlocks.stream()
                        .filter(eb -> eb.getKind() == EvidenceKind.code)
                        .collect(Collectors.toList());
        if (!codeBlocks.isEmpty()) {
            evidenceBlocks = codeBlocks;
            log.debug("Using {} code evidence blocks for error demonstrations", codeBlocks.size());
        }

        ProblemContext problemContext = problemContextResolver.resolve(
                submission == null ? null : submission.getTask());
        String finalReview = submission == null ? null : submission.getFinalReviewComment();
        String title = problemContext == null ? null : problemContext.experimentTitle();

        List<CommentIssueExtractor.CommentIssue> commentIssues =
                commentIssueExtractor.extractIssues(title, scoreItems, finalReview, evidenceBlocks);
        Map<String, String> llmFullCode = extractLLMFullCode(problemContext, scoreItems, evidenceBlocks, commentIssues);
        List<AnimationCandidate> candidates = collectCandidates(scoreItems, evidenceBlocks, problemContext, llmFullCode, commentIssues);

        List<ErrorDemonstration> result = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (result.size() >= MAX_DEMONSTRATIONS) {
                break;
            }
            AnimationCandidate candidate = candidates.get(i);
            AnimationWorkflow workflow = router.route(candidate);
            AnimationResult animationResult = executeWorkflow(workflow, candidate, result.size() + 1);
            List<?> frames = animationResult == null ? null : animationResult.frames();
            if (frames == null || frames.isEmpty()) {
                continue;
            }
            ErrorDemonstration demo = toErrorDemonstration(candidate, animationResult, result.size() + 1);
            if (isUnique(result, demo)) {
                result.add(demo);
            }
        }

        return result;
    }

    private Map<String, String> extractLLMFullCode(
            ProblemContext problemContext,
            List<ScoreItemEntity> scoreItems,
            List<EvidenceBlockEntity> evidenceBlocks,
            List<CommentIssueExtractor.CommentIssue> commentIssues) {
        if (llmCodeExtractor == null || scoreItems == null || evidenceBlocks == null) {
            return Map.of();
        }
        List<String> referencedIds = new ArrayList<>(scoreItems.stream()
                .flatMap(item -> parseAnnotations(item.getAnnotationsJson()).stream())
                .map(AnimationCandidate.AnnotationInfo::evidenceId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList());
        if (commentIssues != null) {
            commentIssues.stream()
                    .map(CommentIssueExtractor.CommentIssue::evidenceId)
                    .filter(id -> id != null && !id.isBlank())
                    .forEach(referencedIds::add);
        }
        // 不再只喂"被批注引用的碎片"：把所有像代码的证据块（含截图 OCR/VLM）一并交给 LLM 聚合，
        // 以便重建更完整的原始代码（此类报告代码常在截图里、批注引用稀疏）。
        java.util.LinkedHashMap<String, EvidenceBlockEntity> selected = new java.util.LinkedHashMap<>();
        for (EvidenceBlockEntity eb : evidenceBlocks) {
            if (eb.getEvidenceId() == null) {
                continue;
            }
            if (referencedIds.contains(eb.getEvidenceId()) || isCodeBearing(eb)) {
                selected.put(eb.getEvidenceId(), eb);
            }
            if (selected.size() >= 16) {
                break;
            }
        }
        String title = problemContext == null ? null : problemContext.experimentTitle();
        return llmCodeExtractor.extractFullCode(title, new ArrayList<>(selected.values()));
    }

    private static final java.util.regex.Pattern CODE_HINT = java.util.regex.Pattern.compile(
            "#include|\\bint\\s+main|\\bdef\\s+\\w+\\s*\\(|\\bimport\\s+\\w+|printf\\(|scanf\\(|torch|nn\\.|print\\(|reshape\\(|conv2d");

    /** 证据块是否像代码（含截图 OCR/VLM 里的代码），用于把完整代码喂给 LLM 聚合。 */
    private boolean isCodeBearing(EvidenceBlockEntity eb) {
        if (eb == null) {
            return false;
        }
        if (eb.getKind() == EvidenceKind.code) {
            return true;
        }
        String content = eb.getContent();
        return content != null && !content.isBlank() && CODE_HINT.matcher(content).find();
    }



    private List<AnimationCandidate> collectCandidates(
            List<ScoreItemEntity> scoreItems,
            List<EvidenceBlockEntity> evidenceBlocks,
            ProblemContext problemContext,
            Map<String, String> llmFullCode,
            List<CommentIssueExtractor.CommentIssue> commentIssues) {

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
                AnimationCandidate candidate = buildCandidate(item, ann, evidenceById, llmFullCode, problemContext);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }

        // 如果结构化批注没产生候选，从评语/总评里再挖一遍
        if (commentIssues != null) {
            for (CommentIssueExtractor.CommentIssue issue : commentIssues) {
                AnimationCandidate candidate = buildCandidateFromCommentIssue(issue, evidenceById, evidenceBlocks, llmFullCode, problemContext);
                if (candidate != null && candidates.stream().noneMatch(c -> sameAnchor(c, issue))) {
                    candidates.add(candidate);
                }
            }
        }

        return candidates;
    }

    private AnimationCandidate buildCandidate(
            ScoreItemEntity item,
            AnimationCandidate.AnnotationInfo ann,
            Map<String, EvidenceBlockEntity> evidenceById,
            Map<String, String> llmFullCode,
            ProblemContext problemContext) {
        EvidenceBlockEntity block = evidenceById.get(ann.evidenceId());
        if (block == null || block.getContent() == null) {
            return null;
        }
        String evidenceKind = block.getKind() == null ? "text" : block.getKind().name();
        ErrorType errorType = errorPatternDetector.detect(ann.anchorText(), ann.note(), evidenceKind);
        if (errorType == null || errorType == ErrorType.GENERIC_HIGHLIGHT) {
            return null;
        }
        CodeContext codeContext = resolveCodeContext(block, ann, llmFullCode);
        // 防编造：拿不到真实代码上下文就跳过该候选——宁可不演示，也不生成与学生无关的内容。
        if (codeContext == null || codeContext.fullLines().isEmpty()) {
            return null;
        }
        return new AnimationCandidate(item, ann, block, codeContext, problemContext, errorType);
    }

    private AnimationCandidate buildCandidateFromCommentIssue(
            CommentIssueExtractor.CommentIssue issue,
            Map<String, EvidenceBlockEntity> evidenceById,
            List<EvidenceBlockEntity> evidenceBlocks,
            Map<String, String> llmFullCode,
            ProblemContext problemContext) {
        EvidenceBlockEntity block = evidenceById.get(issue.evidenceId());
        if (block == null || block.getContent() == null) {
            // evidence_id 未指定或不存在，尝试在所有代码证据块里定位 anchor
            block = findBestCodeBlockForAnchor(issue.anchorText(), evidenceBlocks, llmFullCode);
        }
        if (block == null || block.getContent() == null) {
            return null;
        }
        String evidenceKind = block.getKind() == null ? "text" : block.getKind().name();
        ErrorType errorType = detectErrorType(issue.errorTypeHint(), issue.anchorText(), issue.note(), evidenceKind);
        AnimationCandidate.AnnotationInfo ann = new AnimationCandidate.AnnotationInfo(
                "CROSS", block.getEvidenceId(), issue.anchorText(), issue.note(), false);
        CodeContext codeContext = resolveCodeContext(block, ann, llmFullCode);
        if (codeContext == null || codeContext.fullLines().isEmpty()) {
            return null;
        }
        return new AnimationCandidate(null, ann, block, codeContext, problemContext, errorType);
    }

    private EvidenceBlockEntity findBestCodeBlockForAnchor(
            String anchorText,
            List<EvidenceBlockEntity> evidenceBlocks,
            Map<String, String> llmFullCode) {
        if (evidenceBlocks == null) {
            return null;
        }
        // 优先在 LLM 已提取到完整代码的证据块里找
        for (EvidenceBlockEntity block : evidenceBlocks) {
            String llmCode = llmFullCode == null ? null : llmFullCode.get(block.getEvidenceId());
            if (llmCode != null && !llmCode.isBlank()
                    && codeContextExtractor.extract(llmCode, anchorText).fullLines().size() > 0) {
                return block;
            }
        }
        // 否则在原始证据内容里找
        for (EvidenceBlockEntity block : evidenceBlocks) {
            if (block.getContent() != null
                    && codeContextExtractor.extract(block.getContent(), anchorText).fullLines().size() > 0) {
                return block;
            }
        }
        // 最后找第一个看起来像代码的证据块兜底
        return evidenceBlocks.stream()
                .filter(eb -> eb.getContent() != null && codeContextExtractor.extract(eb.getContent(), "").fullLines().size() > 0)
                .findFirst()
                .orElse(null);
    }

    private ErrorType detectErrorType(String hint, String anchor, String note, String evidenceKind) {
        if (hint != null && !hint.isBlank()) {
            try {
                return ErrorType.valueOf(hint.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        ErrorType detected = errorPatternDetector.detect(anchor, note, evidenceKind);
        if (detected == null || detected == ErrorType.GENERIC_HIGHLIGHT) {
            return ErrorType.CONCEPT;
        }
        return detected;
    }

    private boolean sameAnchor(AnimationCandidate candidate, CommentIssueExtractor.CommentIssue issue) {
        return candidate.evidenceBlock().getEvidenceId().equals(issue.evidenceId())
                && candidate.anchor().equals(issue.anchorText());
    }

    private CodeContext resolveCodeContext(
            EvidenceBlockEntity block,
            AnimationCandidate.AnnotationInfo ann,
            Map<String, String> llmFullCode) {
        CodeContext local = codeContextExtractor.extract(block.getContent(), ann.anchorText());
        String llmCode = llmFullCode == null ? null : llmFullCode.get(ann.evidenceId());
        if (llmCode != null && !llmCode.isBlank()) {
            CodeContext fromLlm = codeContextExtractor.extract(llmCode, ann.anchorText());
            if (fromLlm != null && !fromLlm.fullLines().isEmpty()) {
                // 原文优先：原始代码块本身结构完整时直接用原文，杜绝模型改写学生代码
                if (isStructurallyCompleteCode(local)) {
                    return local;
                }
                if (local == null || local.fullLines().isEmpty()) {
                    return fromLlm.withSource("llm_completed");
                }
                // 忠实度校验：补全结果需覆盖原文 >=80% 的非空行，否则丢弃补全
                if (lineCoverage(fromLlm.fullCode(), local.fullCode()) >= 0.8) {
                    return fromLlm.withSource("llm_completed");
                }
                log.warn("LLM 代码补全忠实度不足，回退原文 (evidence={})", ann.evidenceId());
                return local;
            }
        }
        return local;
    }

    /** 结构完整：大括号配平且含函数头/main，视为报告原文已是完整代码。 */
    private boolean isStructurallyCompleteCode(CodeContext ctx) {
        if (ctx == null || ctx.fullLines().isEmpty()) {
            return false;
        }
        String code = ctx.fullCode();
        long open = code.chars().filter(c -> c == '{').count();
        long close = code.chars().filter(c -> c == '}').count();
        boolean balanced = open > 0 && open == close;
        boolean hasEntry = code.contains("main(") || code.contains("def ") || code.contains("class ");
        return balanced && hasEntry;
    }

    /** 原文非空行在补全结果中出现的比例（trim 后逐行匹配）。 */
    private double lineCoverage(String completed, String raw) {
        java.util.Set<String> completedLines = new java.util.HashSet<>();
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
            case PYTHON_TUTOR -> {
                AnimationResult ptResult = pythonTutorWorkflow.generate(candidate, index);
                // 真实执行失败或没有 trace 步骤时，回退到结构化步骤动画
                if (isPythonTutorFallback(ptResult)) {
                    log.warn("PYTHON_TUTOR 回退到 CONCEPT_STEPS: candidate={}", candidate.anchor());
                    yield conceptStepsWorkflow.generate(candidate, index);
                }
                yield ptResult;
            }
            case CONCEPT_STEPS -> conceptStepsWorkflow.generate(candidate, index);
        };
    }

    private boolean isPythonTutorFallback(AnimationResult result) {
        if (result == null) {
            return true;
        }
        List<?> frames = result.frames();
        if (frames == null || frames.isEmpty()) {
            return true;
        }
        Object reason = result.metadata().get("fallbackReason");
        return reason != null && !reason.toString().isBlank();
    }

    private ErrorDemonstration toErrorDemonstration(AnimationCandidate candidate,
                                                    AnimationResult result,
                                                    int index) {
        CodeContext ctx = candidate.codeContext();
        String sourceCode = ctx == null ? candidate.anchor() : ctx.fullCode();
        int anchorLine = ctx == null ? 1 : ctx.relativeAnchorLine();
        int highlightStart = ctx == null ? 1 : ctx.highlightStartLine();
        int highlightEnd = ctx == null ? 1 : ctx.highlightEndLine();

        // CONCEPT_STEPS 等由工作流自带示意代码：metadata 提供 sourceCode 时优先采用，
        // 并让每步的 line 驱动高亮（不强加固定 highlight 区间）。其它工作流行为不变。
        Object metaSource = result.metadata().get("sourceCode");
        if (metaSource != null && !metaSource.toString().isBlank()) {
            sourceCode = metaSource.toString();
            anchorLine = parseInt(result.metadata().get("errorLine"), 0);
            highlightStart = 0;
            highlightEnd = 0;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> frames = (List<Map<String, Object>>) result.frames();

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
                anchorLine
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

    private int parseInt(Object value, int defaultValue) {
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
        return defaultValue;
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
            int anchorLineInEvidence
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
