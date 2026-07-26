package com.tap.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tap.backend.ai.AiProvider;
import com.tap.backend.domain.classroom.TeachingClassEntity;
import com.tap.backend.domain.teaching.TeachingAdviceReportEntity;
import com.tap.backend.repo.TeachingAdviceReportRepository;
import com.tap.backend.repo.TeachingClassRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TeachingAdviceService {
    private static final Set<String> LEVELS = Set.of("EXPERIMENT", "CLASS", "COURSE");
    private static final String COMPLETED =
            "(CAST(LOWER(COALESCE(sa.submission_status, '')) AS BINARY) " +
            "IN (CAST('submitted' AS BINARY), CAST('graded' AS BINARY), CAST('closed' AS BINARY)) " +
            "OR CAST(COALESCE(sa.completion_evidence, 'NONE') AS BINARY) " +
            "IN (CAST('TRANSCRIPT_SCORE' AS BINARY), CAST('ANSWER_SHEET' AS BINARY), " +
            "CAST('SCORED_CODE' AS BINARY)))";

    @PersistenceContext
    private EntityManager entityManager;

    private final TeachingClassRepository classRepository;
    private final TeachingAdviceReportRepository reportRepository;
    private final TeachingAdvicePromptFactory promptFactory;
    private final AiProvider aiProvider;
    private final ObjectMapper objectMapper;

    public TeachingAdviceService(
            TeachingClassRepository classRepository,
            TeachingAdviceReportRepository reportRepository,
            TeachingAdvicePromptFactory promptFactory,
            AiProvider aiProvider,
            ObjectMapper objectMapper
    ) {
        this.classRepository = classRepository;
        this.reportRepository = reportRepository;
        this.promptFactory = promptFactory;
        this.aiProvider = aiProvider;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> options(Long teacherId) {
        List<Map<String, Object>> classes = classRepository.findAllByTeacherId(teacherId).stream()
                .map(item -> mapOf(
                        "id", item.getId(),
                        "name", item.getName(),
                        "courseId", item.getCourseId(),
                        "courseName", textOr(item.getCourseName(), "课程待补充"),
                        "termId", item.getTermId(),
                        "termName", resolveTermName(item.getTermId()),
                        "metadataComplete", item.getCourseId() != null && item.getTermId() != null
                ))
                .toList();

        List<Map<String, Object>> courses = rows(
                "SELECT CAST(id AS SIGNED), course_code, name, subject FROM course WHERE status = 'ACTIVE' ORDER BY name",
                Map.of()
        ).stream().map(row -> mapOf(
                "id", toLong(row[0]), "code", row[1], "name", row[2], "subject", row[3]
        )).toList();

        List<Map<String, Object>> terms = rows(
                "SELECT CAST(id AS SIGNED), term_code, name, start_date, end_date FROM academic_term ORDER BY start_date DESC, id DESC",
                Map.of()
        ).stream().map(row -> mapOf(
                "id", toLong(row[0]), "code", row[1], "name", row[2], "startDate", row[3], "endDate", row[4]
        )).toList();

        return mapOf("classes", classes, "courses", courses, "terms", terms);
    }

    public Map<String, Object> context(
            Long teacherId,
            String requestedLevel,
            Long classId,
            Long experimentId,
            boolean includeHistory
    ) {
        String level = normalizeLevel(requestedLevel);
        ScopeAnchor anchor = "EXPERIMENT".equals(level)
                ? requireExperimentAnchor(teacherId, experimentId)
                : requireClassAnchor(teacherId, classId);

        Map<String, Object> scope = scopeMap(level, anchor, includeHistory);
        Map<String, Object> metrics = switch (level) {
            case "EXPERIMENT" -> experimentMetrics(teacherId, anchor);
            case "CLASS" -> classMetrics(teacherId, anchor, includeHistory);
            case "COURSE" -> courseMetrics(teacherId, anchor, includeHistory);
            default -> throw new IllegalStateException("unexpected scope level");
        };
        return mapOf("scope", scope, "metrics", metrics, "generatedAt", Instant.now().toString());
    }

    public Map<String, Object> generate(
            Long teacherId,
            String requestedLevel,
            Long classId,
            Long experimentId,
            boolean includeHistory
    ) {
        String level = normalizeLevel(requestedLevel);
        Map<String, Object> context = context(teacherId, level, classId, experimentId, includeHistory);
        Map<String, Object> scope = castMap(context.get("scope"));
        Map<String, Object> metrics = castMap(context.get("metrics"));
        TeachingAdviceReportEntity report = newReport(teacherId, level, scope, metrics);

        try {
            ObjectNode advice;
            if ("mock".equalsIgnoreCase(aiProvider.name())) {
                advice = fallbackAdvice(level, metrics);
            } else {
                String prompt = promptFactory.build(level, context);
                advice = parseAndValidateAdvice(aiProvider.chat(prompt, null), evidenceIds(metrics));
            }
            report.setAdviceJson(writeJson(advice));
            report.setStatus("COMPLETED");
            report = reportRepository.save(report);
            return reportMap(report);
        } catch (RuntimeException error) {
            report.setStatus("FAILED");
            report.setErrorMessage(limit(error.getMessage(), 1000));
            reportRepository.save(report);
            throw error;
        }
    }

    public List<Map<String, Object>> listReports(Long teacherId) {
        return reportRepository.findTop20ByTeacherIdOrderByCreatedAtDesc(teacherId).stream()
                .map(this::reportMap)
                .toList();
    }

    public Map<String, Object> getReport(Long teacherId, Long reportId) {
        return reportMap(reportRepository.findByIdAndTeacherId(reportId, teacherId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "teaching advice report not found")));
    }

    private Map<String, Object> experimentMetrics(Long teacherId, ScopeAnchor anchor) {
        String scopePredicate = courseTermPredicate(anchor, false);
        Map<String, Object> params = courseTermParams(anchor, false);
        params.put("teacherId", teacherId);
        params.put("templateId", anchor.templateId());

        List<Map<String, Object>> evidence = new ArrayList<>();
        List<Map<String, Object>> comparisons = new ArrayList<>();
        List<Object[]> comparisonRows = rows(
                "SELECT CAST(tc.id AS SIGNED), tc.name, COUNT(DISTINCT sa.student_id), " +
                "SUM(CASE WHEN " + COMPLETED + " THEN 1 ELSE 0 END), " +
                "ROUND(100 * SUM(CASE WHEN " + COMPLETED + " THEN 1 ELSE 0 END) / NULLIF(COUNT(sa.id), 0), 1), " +
                "ROUND(AVG(COALESCE(sa.best_total_score, sa.latest_total_score)), 1) " +
                "FROM assignment_offering ao JOIN teaching_class tc ON tc.id = ao.class_id " +
                "LEFT JOIN course c ON c.id = tc.course_id LEFT JOIN student_assignment sa ON sa.offering_id = ao.id " +
                "WHERE ao.teacher_id = :teacherId AND ao.template_id = :templateId AND " + scopePredicate + " " +
                "GROUP BY tc.id, tc.name ORDER BY tc.name",
                params
        );
        int index = 1;
        for (Object[] row : comparisonRows) {
            String evidenceId = evidenceId(index++);
            Map<String, Object> item = mapOf(
                    "evidenceId", evidenceId,
                    "classId", toLong(row[0]),
                    "className", row[1],
                    "studentCount", toInt(row[2]),
                    "completedCount", toInt(row[3]),
                    "completionRate", toDouble(row[4]),
                    "averageScore", toDouble(row[5])
            );
            comparisons.add(item);
            evidence.add(evidence(evidenceId, "班级实验表现", item));
        }

        List<Map<String, Object>> problems = new ArrayList<>();
        List<Object[]> problemRows = rows(
                "SELECT ap.problem_no, MAX(ap.title), COUNT(DISTINCT sps.student_id), " +
                "SUM(CASE WHEN sps.accepted_at IS NOT NULL THEN 1 ELSE 0 END), " +
                "ROUND(100 * SUM(CASE WHEN sps.accepted_at IS NOT NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(sps.id), 0), 1), " +
                "ROUND(AVG(COALESCE(sps.attempt_count, 0)), 1) " +
                "FROM assignment_offering ao JOIN teaching_class tc ON tc.id = ao.class_id " +
                "LEFT JOIN course c ON c.id = tc.course_id JOIN assignment_problem ap ON ap.offering_id = ao.id AND ap.status = 'ACTIVE' " +
                "LEFT JOIN student_problem_state sps ON sps.offering_id = ao.id AND sps.problem_id = ap.id " +
                "WHERE ao.teacher_id = :teacherId AND ao.template_id = :templateId AND " + scopePredicate + " " +
                "GROUP BY ap.problem_no ORDER BY MIN(ap.sort_order), ap.problem_no",
                params
        );
        for (Object[] row : problemRows) {
            String evidenceId = evidenceId(index++);
            Map<String, Object> item = mapOf(
                    "evidenceId", evidenceId,
                    "problemNo", row[0],
                    "title", row[1],
                    "studentCount", toInt(row[2]),
                    "acceptedCount", toInt(row[3]),
                    "acceptanceRate", toDouble(row[4]),
                    "averageAttempts", toDouble(row[5])
            );
            problems.add(item);
            evidence.add(evidence(evidenceId, "题目掌握情况", item));
        }
        Map<String, Object> scoreDistribution = experimentScoreDistribution(teacherId, anchor);
        String distributionEvidenceId = evidenceId(index++);
        scoreDistribution.put("evidenceId", distributionEvidenceId);
        evidence.add(evidence(distributionEvidenceId, "本实验成绩分层", scoreDistribution));

        List<Map<String, Object>> focusStudents = experimentFocusStudents(teacherId, anchor);
        String focusEvidenceId = evidenceId(index++);
        Map<String, Object> focusSnapshot = mapOf("evidenceId", focusEvidenceId, "students", focusStudents);
        evidence.add(evidence(focusEvidenceId, "本实验重点关注学生", focusSnapshot));

        List<Map<String, Object>> problemErrorPoints = problemErrorPoints(
                "ao.teacher_id = :teacherId AND ao.id = :experimentId",
                Map.of("teacherId", teacherId, "experimentId", anchor.experimentId())
        );
        String pointEvidenceId = evidenceId(index++);
        attachEvidenceRef(problemErrorPoints, pointEvidenceId);
        Map<String, Object> pointSnapshot = mapOf("evidenceId", pointEvidenceId, "items", problemErrorPoints);
        evidence.add(evidence(pointEvidenceId, "本实验题目错误点诊断", pointSnapshot));

        List<Map<String, Object>> errorStatusSummary = problemErrorStatusSummary(
                "ao.teacher_id = :teacherId AND ao.id = :experimentId",
                Map.of("teacherId", teacherId, "experimentId", anchor.experimentId())
        );
        String errorEvidenceId = evidenceId(index++);
        Map<String, Object> errorSnapshot = mapOf("evidenceId", errorEvidenceId, "items", errorStatusSummary);
        evidence.add(evidence(errorEvidenceId, "本实验题目错误类型", errorSnapshot));

        Map<String, Object> metrics = mapOf("classComparison", comparisons, "problemPerformance", problems,
                "scoreDistribution", scoreDistribution, "focusStudents", focusStudents,
                "problemErrorPoints", problemErrorPoints, "errorStatusSummary", errorStatusSummary,
                "evidence", evidence, "dataCoverage", coverage(comparisons, problems));
        metrics.put("learningDiagnosis", learningDiagnosis("EXPERIMENT", metrics));
        metrics.put("teachingSignals", teachingSignals("EXPERIMENT", metrics));
        metrics.put("teachingContext", teachingContext("EXPERIMENT", metrics));
        return metrics;
    }

    private Map<String, Object> classMetrics(Long teacherId, ScopeAnchor anchor, boolean includeHistory) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("teacherId", teacherId);
        params.put("classId", anchor.classId());
        List<Map<String, Object>> evidence = new ArrayList<>();
        List<Map<String, Object>> experiments = new ArrayList<>();
        int index = 1;
        for (Object[] row : rows(
                "SELECT CAST(ao.id AS SIGNED), COALESCE(NULLIF(TRIM(ao.title_override), ''), at.title), " +
                "COUNT(DISTINCT sa.student_id), SUM(CASE WHEN " + COMPLETED + " THEN 1 ELSE 0 END), " +
                "ROUND(100 * SUM(CASE WHEN " + COMPLETED + " THEN 1 ELSE 0 END) / NULLIF(COUNT(sa.id), 0), 1), " +
                "ROUND(AVG(COALESCE(sa.best_total_score, sa.latest_total_score)), 1) " +
                "FROM assignment_offering ao JOIN assignment_template at ON at.id = ao.template_id " +
                "LEFT JOIN student_assignment sa ON sa.offering_id = ao.id " +
                "WHERE ao.teacher_id = :teacherId AND ao.class_id = :classId " +
                "GROUP BY ao.id, ao.title_override, at.title, ao.seq_no ORDER BY ao.seq_no, ao.id",
                params
        )) {
            String evidenceId = evidenceId(index++);
            Map<String, Object> item = mapOf(
                    "evidenceId", evidenceId, "experimentId", toLong(row[0]), "name", row[1],
                    "studentCount", toInt(row[2]), "completedCount", toInt(row[3]),
                    "completionRate", toDouble(row[4]), "averageScore", toDouble(row[5])
            );
            experiments.add(item);
            evidence.add(evidence(evidenceId, "班级历次实验", item));
        }

        Map<String, Object> segments = studentSegments(teacherId, anchor.classId());
        String segmentEvidenceId = evidenceId(index++);
        segments.put("evidenceId", segmentEvidenceId);
        evidence.add(evidence(segmentEvidenceId, "学生分层统计", segments));

        Map<String, Object> scoreDistribution = classScoreDistribution(teacherId, anchor.classId());
        String distributionEvidenceId = evidenceId(index++);
        scoreDistribution.put("evidenceId", distributionEvidenceId);
        evidence.add(evidence(distributionEvidenceId, "班级成绩分布", scoreDistribution));

        List<Map<String, Object>> focusStudents = classFocusStudents(teacherId, anchor.classId());
        String focusEvidenceId = evidenceId(index++);
        Map<String, Object> focusSnapshot = mapOf("evidenceId", focusEvidenceId, "students", focusStudents);
        evidence.add(evidence(focusEvidenceId, "班级重点关注学生", focusSnapshot));

        List<Map<String, Object>> peerClasses = courseClassComparison(teacherId, anchor, false, index, evidence);
        index += peerClasses.size();
        List<Map<String, Object>> history = includeHistory
                ? courseHistory(teacherId, anchor, index, evidence)
                : List.of();
        index += history.size();
        List<Map<String, Object>> problemErrorPoints = problemErrorPoints(
                "ao.teacher_id = :teacherId AND ao.class_id = :classId",
                Map.of("teacherId", teacherId, "classId", anchor.classId())
        );
        String pointEvidenceId = evidenceId(index++);
        attachEvidenceRef(problemErrorPoints, pointEvidenceId);
        Map<String, Object> pointSnapshot = mapOf("evidenceId", pointEvidenceId, "items", problemErrorPoints);
        evidence.add(evidence(pointEvidenceId, "班级题目错误点诊断", pointSnapshot));

        List<Map<String, Object>> errorStatusSummary = problemErrorStatusSummary(
                "ao.teacher_id = :teacherId AND ao.class_id = :classId",
                Map.of("teacherId", teacherId, "classId", anchor.classId())
        );
        String errorEvidenceId = evidenceId(index++);
        Map<String, Object> errorSnapshot = mapOf("evidenceId", errorEvidenceId, "items", errorStatusSummary);
        evidence.add(evidence(errorEvidenceId, "班级题目错误类型", errorSnapshot));

        Map<String, Object> metrics = mapOf("experiments", experiments, "studentSegments", segments,
                "scoreDistribution", scoreDistribution, "focusStudents", focusStudents,
                "peerClassComparison", peerClasses, "history", history, "problemErrorPoints", problemErrorPoints,
                "errorStatusSummary", errorStatusSummary, "evidence", evidence,
                "dataCoverage", coverage(experiments, peerClasses));
        metrics.put("learningDiagnosis", learningDiagnosis("CLASS", metrics));
        metrics.put("teachingSignals", teachingSignals("CLASS", metrics));
        metrics.put("teachingContext", teachingContext("CLASS", metrics));
        return metrics;
    }

    private Map<String, Object> courseMetrics(Long teacherId, ScopeAnchor anchor, boolean includeHistory) {
        List<Map<String, Object>> evidence = new ArrayList<>();
        List<Map<String, Object>> classes = courseClassComparison(teacherId, anchor, false, 1, evidence);
        int index = classes.size() + 1;
        String predicate = courseTermPredicate(anchor, false);
        Map<String, Object> params = courseTermParams(anchor, false);
        params.put("teacherId", teacherId);
        List<Map<String, Object>> experiments = new ArrayList<>();
        for (Object[] row : rows(
                "SELECT CAST(at.id AS SIGNED), at.title, COUNT(DISTINCT ao.class_id), COUNT(DISTINCT sa.student_id), " +
                "ROUND(100 * SUM(CASE WHEN " + COMPLETED + " THEN 1 ELSE 0 END) / NULLIF(COUNT(sa.id), 0), 1), " +
                "ROUND(AVG(COALESCE(sa.best_total_score, sa.latest_total_score)), 1) " +
                "FROM assignment_offering ao JOIN assignment_template at ON at.id = ao.template_id " +
                "JOIN teaching_class tc ON tc.id = ao.class_id LEFT JOIN course c ON c.id = tc.course_id " +
                "LEFT JOIN student_assignment sa ON sa.offering_id = ao.id " +
                "WHERE ao.teacher_id = :teacherId AND " + predicate + " " +
                "GROUP BY at.id, at.title ORDER BY MIN(ao.seq_no), at.id",
                params
        )) {
            String evidenceId = evidenceId(index++);
            Map<String, Object> item = mapOf(
                    "evidenceId", evidenceId, "templateId", toLong(row[0]), "name", row[1],
                    "classCount", toInt(row[2]), "studentCount", toInt(row[3]),
                    "completionRate", toDouble(row[4]), "averageScore", toDouble(row[5])
            );
            experiments.add(item);
            evidence.add(evidence(evidenceId, "课程实验汇总", item));
        }
        List<Map<String, Object>> history = includeHistory
                ? courseHistory(teacherId, anchor, index, evidence)
                : List.of();
        index += history.size();
        Map<String, Object> scoreDistribution = courseScoreDistribution(teacherId, anchor);
        String distributionEvidenceId = evidenceId(index++);
        scoreDistribution.put("evidenceId", distributionEvidenceId);
        evidence.add(evidence(distributionEvidenceId, "课程成绩分布", scoreDistribution));

        List<Map<String, Object>> focusStudents = courseFocusStudents(teacherId, anchor);
        String focusEvidenceId = evidenceId(index++);
        Map<String, Object> focusSnapshot = mapOf("evidenceId", focusEvidenceId, "students", focusStudents);
        evidence.add(evidence(focusEvidenceId, "课程重点关注学生", focusSnapshot));

        List<Map<String, Object>> problemErrorPoints = problemErrorPoints(
                "ao.teacher_id = :teacherId AND " + courseTermPredicate(anchor, false),
                params
        );
        String pointEvidenceId = evidenceId(index++);
        attachEvidenceRef(problemErrorPoints, pointEvidenceId);
        Map<String, Object> pointSnapshot = mapOf("evidenceId", pointEvidenceId, "items", problemErrorPoints);
        evidence.add(evidence(pointEvidenceId, "课程题目错误点诊断", pointSnapshot));

        List<Map<String, Object>> errorStatusSummary = problemErrorStatusSummary(
                "ao.teacher_id = :teacherId AND " + courseTermPredicate(anchor, false),
                params
        );
        String errorEvidenceId = evidenceId(index++);
        Map<String, Object> errorSnapshot = mapOf("evidenceId", errorEvidenceId, "items", errorStatusSummary);
        evidence.add(evidence(errorEvidenceId, "课程题目错误类型", errorSnapshot));

        Map<String, Object> metrics = mapOf("classComparison", classes, "experimentSummary", experiments,
                "scoreDistribution", scoreDistribution, "focusStudents", focusStudents,
                "problemErrorPoints", problemErrorPoints, "errorStatusSummary", errorStatusSummary,
                "history", history, "evidence", evidence, "dataCoverage", coverage(classes, experiments));
        metrics.put("learningDiagnosis", learningDiagnosis("COURSE", metrics));
        metrics.put("teachingSignals", teachingSignals("COURSE", metrics));
        metrics.put("teachingContext", teachingContext("COURSE", metrics));
        return metrics;
    }

    private Map<String, Object> learningDiagnosis(String level, Map<String, Object> metrics) {
        List<Map<String, Object>> problemErrorPoints = problemErrorPointSignals(metrics);
        List<Map<String, Object>> weakProblemSignals = weakProblemSignals(metrics);
        List<Map<String, Object>> trendSignals = trendSignals(level, metrics);
        List<Map<String, Object>> errorTypeSignals = errorTypeSignals(metrics);
        List<Map<String, Object>> studentPatternSignals = studentPatternSignals(metrics);
        List<Map<String, Object>> inferredKnowledgeSignals = inferredKnowledgeSignals(level, metrics, problemErrorPoints, weakProblemSignals, trendSignals);
        List<Map<String, Object>> dataQualityIssues = dataQualityIssues(metrics, errorTypeSignals);

        String conclusion = diagnosisConclusion(level, problemErrorPoints, weakProblemSignals, trendSignals, errorTypeSignals, studentPatternSignals);
        String nextAction = nextTeachingAction(problemErrorPoints, weakProblemSignals, trendSignals, errorTypeSignals, studentPatternSignals);
        String reliability = dataQualityIssues.isEmpty() ? "HIGH" : "MEDIUM";
        if (problemErrorPoints.isEmpty() && weakProblemSignals.isEmpty() && trendSignals.isEmpty() && errorTypeSignals.isEmpty()) reliability = "LOW";

        return mapOf(
                "conclusion", conclusion,
                "nextTeachingAction", nextAction,
                "reliability", reliability,
                "problemErrorPoints", problemErrorPoints,
                "weakProblemSignals", weakProblemSignals,
                "trendSignals", trendSignals,
                "errorTypeSignals", errorTypeSignals,
                "studentPatternSignals", studentPatternSignals,
                "inferredKnowledgeSignals", inferredKnowledgeSignals,
                "dataQualityIssues", dataQualityIssues,
                "usageRule", "AI 必须先根据 problemErrorPoints 写出具体题目错误点，再给教学建议；知识点字段为推断时，需要展示依据和置信度，不能伪装成数据库标签。"
        );
    }

    private List<Map<String, Object>> problemErrorPointSignals(Map<String, Object> metrics) {
        if (!(metrics.get("problemErrorPoints") instanceof List<?> rows)) return List.of();
        return rows.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::castGenericMap)
                .filter(row -> !isProblemDataNoise(row))
                .filter(row -> toInt(row.get("affectedStudentCount")) > 0)
                .limit(6)
                .toList();
    }

    private List<Map<String, Object>> weakProblemSignals(Map<String, Object> metrics) {
        if (!(metrics.get("problemPerformance") instanceof List<?> rows)) return List.of();
        return rows.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::castGenericMap)
                .filter(row -> !isProblemDataNoise(row))
                .filter(row -> toInt(row.get("studentCount")) > 0)
                .sorted((left, right) -> {
                    int acceptance = Double.compare(toDouble(left.get("acceptanceRate")), toDouble(right.get("acceptanceRate")));
                    return acceptance != 0 ? acceptance : Double.compare(toDouble(right.get("averageAttempts")), toDouble(left.get("averageAttempts")));
                })
                .limit(5)
                .map(row -> {
                    String title = mapText(row, "title", "未知题目");
                    String knowledge = inferKnowledge(title);
                    double acceptance = toDouble(row.get("acceptanceRate"));
                    double attempts = toDouble(row.get("averageAttempts"));
                    return mapOf(
                            "evidenceRefs", evidenceRefs(row.get("evidenceId")),
                            "problemNo", row.get("problemNo"),
                            "title", title,
                            "acceptanceRate", acceptance,
                            "averageAttempts", attempts,
                            "inferredKnowledge", knowledge,
                            "confidence", knowledge.equals("待人工确认") ? "LOW" : "MEDIUM",
                            "diagnosis", problemDiagnosis(title, knowledge, acceptance, attempts),
                            "teachingAdvice", problemTeachingAdvice(knowledge, title)
                    );
                })
                .toList();
    }

    private List<Map<String, Object>> trendSignals(String level, Map<String, Object> metrics) {
        String field = switch (level) {
            case "EXPERIMENT" -> "classComparison";
            case "CLASS" -> "experiments";
            default -> "experimentSummary";
        };
        if (!(metrics.get(field) instanceof List<?> rows)) return List.of();
        List<Map<String, Object>> items = rows.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::castGenericMap)
                .filter(row -> !isNonRequiredExperiment(row))
                .toList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> current = items.get(i);
            double completion = toDouble(current.get("completionRate"));
            double score = toDouble(current.get("averageScore"));
            boolean absoluteWeak = completion > 0 && (completion < 70 || score < 60);
            boolean drop = false;
            double completionDelta = 0;
            double scoreDelta = 0;
            String previousEvidenceId = null;
            if (i > 0) {
                Map<String, Object> previous = items.get(i - 1);
                completionDelta = round1(completion - toDouble(previous.get("completionRate")));
                scoreDelta = round1(score - toDouble(previous.get("averageScore")));
                previousEvidenceId = asText(previous.get("evidenceId"));
                drop = completionDelta <= -15 || scoreDelta <= -10;
            }
            if (!absoluteWeak && !drop) continue;
            String name = mapText(current, "name", mapText(current, "className", "当前对象"));
            String knowledge = inferKnowledge(name);
            result.add(mapOf(
                    "evidenceRefs", evidenceRefs(previousEvidenceId, current.get("evidenceId")),
                    "objectName", name,
                    "completionRate", completion,
                    "averageScore", score,
                    "completionDelta", completionDelta,
                    "scoreDelta", scoreDelta,
                    "inferredKnowledge", knowledge,
                    "confidence", knowledge.equals("待人工确认") ? "LOW" : "MEDIUM",
                    "diagnosis", trendDiagnosis(name, completion, score, completionDelta, scoreDelta),
                    "teachingAdvice", trendTeachingAdvice(knowledge, name)
            ));
        }
        result.sort((left, right) -> Double.compare(trendSeverity(right), trendSeverity(left)));
        return result.stream().limit(5).toList();
    }

    private List<Map<String, Object>> errorTypeSignals(Map<String, Object> metrics) {
        if (!(metrics.get("errorStatusSummary") instanceof List<?> rows)) return List.of();
        return rows.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::castGenericMap)
                .map(row -> {
                    String status = mapText(row, "status", "UNKNOWN").toUpperCase(Locale.ROOT);
                    return mapOf(
                            "status", status,
                            "recordCount", toInt(row.get("recordCount")),
                            "studentCount", toInt(row.get("studentCount")),
                            "problemCount", toInt(row.get("problemCount")),
                            "averageAttempts", toDouble(row.get("averageAttempts")),
                            "diagnosis", statusDiagnosis(status),
                            "teachingAdvice", statusTeachingAdvice(status),
                            "dataCaution", statusDataCaution(status)
                    );
                })
                .toList();
    }

    private List<Map<String, Object>> studentPatternSignals(Map<String, Object> metrics) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Object> distribution = metrics.get("scoreDistribution") instanceof Map<?, ?> map ? castGenericMap(map) : Map.of();
        int incomplete = toInt(distribution.get("incomplete"));
        int highRisk = toInt(distribution.get("highRisk"));
        int middle = toInt(distribution.get("middle"));
        int excellent = toInt(distribution.get("excellent"));
        if (incomplete > 0) {
            result.add(mapOf(
                    "pattern", "MULTI_INCOMPLETE",
                    "studentCount", incomplete,
                    "diagnosis", "存在未完成或未提交学生，先排查缺勤、账号绑定、PTA 同步和提交路径，再判断学习能力问题。",
                    "teachingAdvice", "给这类学生设置补交路径和最小任务复测，不要只在群里提醒补交。"
            ));
        }
        if (highRisk > 0) {
            result.add(mapOf(
                    "pattern", "PERSISTENT_LOW_SCORE",
                    "studentCount", highRisk,
                    "diagnosis", "低分学生需要先定位基础语法、关键步骤或报告分析中的具体卡点。",
                    "teachingAdvice", "使用最小可运行样例和同类短练，要求学生说出一个自查点后再进入综合题。"
            ));
        }
        if (middle > excellent) {
            result.add(mapOf(
                    "pattern", "MIDDLE_IMPROVE",
                    "studentCount", middle,
                    "diagnosis", "中等层人数较多，说明主体逻辑可能能完成，但迁移、边界和表达还不稳定。",
                    "teachingAdvice", "增加边界样例清单、代码自查清单和报告结果解释任务。"
            ));
        }
        List<?> focusStudents = metrics.get("focusStudents") instanceof List<?> list ? list : List.of();
        if (!focusStudents.isEmpty()) {
            result.add(mapOf(
                    "pattern", "FOCUS_STUDENTS",
                    "studentCount", focusStudents.size(),
                    "diagnosis", "系统已筛出需要短周期跟进的学生，应按原因分组处理，而不是统一要求重做。",
                    "teachingAdvice", "把重点学生分成未完成、低分、关键题未通过三类，分别核对提交、补基础、做同类复测。"
            ));
        }
        return result;
    }

    private List<Map<String, Object>> inferredKnowledgeSignals(
            String level,
            Map<String, Object> metrics,
            List<Map<String, Object>> problemErrorPoints,
            List<Map<String, Object>> weakProblemSignals,
            List<Map<String, Object>> trendSignals
    ) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> item : problemErrorPoints) {
            mergeKnowledgeSignal(grouped, mapText(item, "inferredKnowledge", "待人工确认"), item, "题目错误点");
        }
        for (Map<String, Object> item : weakProblemSignals) {
            mergeKnowledgeSignal(grouped, mapText(item, "inferredKnowledge", "待人工确认"), item, "薄弱题目");
        }
        for (Map<String, Object> item : trendSignals) {
            mergeKnowledgeSignal(grouped, mapText(item, "inferredKnowledge", "待人工确认"), item, "趋势下滑");
        }
        if (grouped.isEmpty()) {
            String field = switch (level) {
                case "EXPERIMENT" -> "problemPerformance";
                case "CLASS" -> "experiments";
                default -> "experimentSummary";
            };
            if (metrics.get(field) instanceof List<?> rows) {
                rows.stream()
                        .filter(Map.class::isInstance)
                        .map(Map.class::cast)
                        .map(this::castGenericMap)
                        .limit(3)
                        .forEach(row -> {
                            String text = mapText(row, "title", mapText(row, "name", ""));
                            String knowledge = inferKnowledge(text);
                            if (!knowledge.equals("待人工确认")) mergeKnowledgeSignal(grouped, knowledge, row, "名称推断");
                        });
            }
        }
        return grouped.values().stream().limit(6).toList();
    }

    private List<Map<String, Object>> dataQualityIssues(Map<String, Object> metrics, List<Map<String, Object>> errorTypeSignals) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (containsNoisyProblem(metrics)) {
            result.add(mapOf(
                    "type", "UNRESOLVED_PROBLEM_TITLE",
                    "level", "MEDIUM",
                    "message", "发现 problemNo=0 或 PTA Problem 0 这类未解析题目，不能把它展示为具体知识点。",
                    "action", "生成建议时应过滤该类题目，或先回填 assignment_problem/pta_problem_detail 的题目标题。"
            ));
        }
        int waiting = errorTypeSignals.stream()
                .filter(item -> "WAITING".equals(mapText(item, "status", "")))
                .mapToInt(item -> toInt(item.get("recordCount")))
                .sum();
        if (waiting > 0) {
            result.add(mapOf(
                    "type", "JUDGE_OR_SYNC_PENDING",
                    "level", "MEDIUM",
                    "message", "存在 WAITING 状态记录，应优先视为判题或同步状态，不直接判定学生知识薄弱。",
                    "action", "AI 只能把它写入数据可靠性提醒，不能据此生成知识点补救建议。"
            ));
        }
        if (containsNonRequiredExperiment(metrics)) {
            result.add(mapOf(
                    "type", "NON_REQUIRED_EXPERIMENT",
                    "level", "LOW",
                    "message", "发现测试或不用完成的实验名称，课程/班级趋势分析应排除它们。",
                    "action", "仅作为数据质量说明，不进入薄弱实验排序。"
            ));
        }
        if (!hasDirectKnowledgeTags(metrics)) {
            result.add(mapOf(
                    "type", "KNOWLEDGE_TAG_COVERAGE",
                    "level", "MEDIUM",
                    "message", "当前诊断暂未匹配到 knowledge_leaf 直接标签；知识点主要来自题目名、题干摘要和错误类型推断。",
                    "action", "前端展示为“推断知识点”，同时显示依据和置信度；生成建议时不能把它写成数据库已标注知识点。"
            ));
        }
        return result;
    }

    private void mergeKnowledgeSignal(
            Map<String, Map<String, Object>> grouped,
            String knowledge,
            Map<String, Object> item,
            String sourceType
    ) {
        if (knowledge == null || knowledge.isBlank() || "待人工确认".equals(knowledge)) return;
        Map<String, Object> current = grouped.computeIfAbsent(knowledge, key -> mapOf(
                "knowledge", key,
                "confidence", "MEDIUM",
                "sourceTypes", new ArrayList<String>(),
                "evidenceRefs", new ArrayList<String>(),
                "diagnosis", "该知识点由题目/实验名称和表现指标推断，需要结合原题核对。",
                "teachingAdvice", knowledgeTeachingAdvice(key)
        ));
        @SuppressWarnings("unchecked")
        List<String> sourceTypes = (List<String>) current.get("sourceTypes");
        if (!sourceTypes.contains(sourceType)) sourceTypes.add(sourceType);
        @SuppressWarnings("unchecked")
        List<String> refs = (List<String>) current.get("evidenceRefs");
        Object rawRefs = item.get("evidenceRefs");
        if (rawRefs instanceof List<?> list) {
            list.forEach(ref -> {
                String value = asText(ref);
                if (value != null && !value.isBlank() && !refs.contains(value)) refs.add(value);
            });
        } else {
            String value = asText(item.get("evidenceId"));
            if (value != null && !value.isBlank() && !refs.contains(value)) refs.add(value);
        }
    }

    private String diagnosisConclusion(
            String level,
            List<Map<String, Object>> problemErrorPoints,
            List<Map<String, Object>> weakProblems,
            List<Map<String, Object>> trends,
            List<Map<String, Object>> errors,
            List<Map<String, Object>> studentPatterns
    ) {
        if (!problemErrorPoints.isEmpty()) {
            Map<String, Object> top = problemErrorPoints.get(0);
            return "优先处理题目“" + mapText(top, "title", "当前题目") + "”的“"
                    + mapText(top, "errorPoint", "主要错误点") + "”：影响 " + toInt(top.get("affectedStudentCount"))
                    + " 名学生，下一节课应先按该错误点讲清判断方法、修正顺序和复测题。";
        }
        if (!weakProblems.isEmpty()) {
            Map<String, Object> top = weakProblems.get(0);
            return "优先补“" + mapText(top, "inferredKnowledge", "当前薄弱题型") + "”："
                    + mapText(top, "title", "关键题目") + "表现最弱，下一节课应先讲解该题型的判断路径和边界处理。";
        }
        if (!trends.isEmpty()) {
            Map<String, Object> top = trends.get(0);
            return "优先处理“" + mapText(top, "objectName", "当前薄弱环节") + "”的阶段性下滑，先补前置步骤再推进新实验。";
        }
        if (!errors.isEmpty()) {
            Map<String, Object> top = errors.get(0);
            return "优先按错误类型处理“" + mapText(top, "status", "高频错误") + "”，用短练验证学生是否真正会改。";
        }
        if (!studentPatterns.isEmpty()) return mapText(studentPatterns.get(0), "diagnosis", "先按学生分层进行短周期跟进。");
        return switch (level) {
            case "EXPERIMENT" -> "当前实验缺少足够题目级异常，建议继续用短练复测核对是否存在局部卡点。";
            case "CLASS" -> "当前班级没有明显断崖点，建议按分层学生继续做低成本跟进。";
            default -> "当前课程层面没有明显共性断崖，建议优先维护数据质量并观察下一轮实验表现。";
        };
    }

    private String nextTeachingAction(
            List<Map<String, Object>> problemErrorPoints,
            List<Map<String, Object>> weakProblems,
            List<Map<String, Object>> trends,
            List<Map<String, Object>> errors,
            List<Map<String, Object>> studentPatterns
    ) {
        if (!problemErrorPoints.isEmpty()) return mapText(problemErrorPoints.get(0), "teachingAdvice", "围绕主要题目错误点做讲解和短练复测。");
        if (!weakProblems.isEmpty()) return mapText(weakProblems.get(0), "teachingAdvice", "围绕薄弱题目做讲解和短练复测。");
        if (!trends.isEmpty()) return mapText(trends.get(0), "teachingAdvice", "围绕下滑实验补前置步骤并复测。");
        if (!errors.isEmpty()) return mapText(errors.get(0), "teachingAdvice", "围绕高频错误类型设计短练。");
        if (!studentPatterns.isEmpty()) return mapText(studentPatterns.get(0), "teachingAdvice", "按学生分层安排跟进。");
        return "先核对数据覆盖，再选择一个同类短练验证当前判断。";
    }

    private Map<String, Object> teachingSignals(String level, Map<String, Object> metrics) {
        List<Map<String, Object>> weakItems = switch (level) {
            case "EXPERIMENT" -> weakestRows(metrics, "problemPerformance", "acceptanceRate", 3);
            case "CLASS" -> weakestRows(metrics, "experiments", "completionRate", 3);
            default -> weakestRows(metrics, "experimentSummary", "completionRate", 3);
        };
        List<Map<String, Object>> focusStudents = metrics.get("focusStudents") instanceof List<?> list
                ? list.stream().filter(Map.class::isInstance).map(Map.class::cast).map(this::castGenericMap).limit(5).toList()
                : List.of();
        Map<String, Object> scoreDistribution = metrics.get("scoreDistribution") instanceof Map<?, ?> map
                ? castGenericMap(map)
                : Map.of();
        List<String> riskTypes = new ArrayList<>();
        int highRisk = toInt(scoreDistribution.get("highRisk"));
        int incomplete = toInt(scoreDistribution.get("incomplete"));
        int middle = toInt(scoreDistribution.get("middle"));
        int excellent = toInt(scoreDistribution.get("excellent"));
        if (incomplete > 0) riskTypes.add("存在未完成或未提交学生，优先判断是否是基础步骤、环境或提交流程问题");
        if (highRisk > 0) riskTypes.add("存在低于及格线的重点帮扶层，适合用最小样例短练复测");
        if (middle > excellent) riskTypes.add("中等提升层人数较多，适合补报告分析、代码自查和迁移题方法");
        if (riskTypes.isEmpty()) riskTypes.add("当前主要风险不明显，应关注是否存在局部题目或个别学生波动");
        return mapOf(
                "weakestTeachingObjects", weakItems,
                "studentFollowUpCandidates", focusStudents,
                "riskTypes", riskTypes,
                "decisionHint", switch (level) {
                    case "EXPERIMENT" -> "优先把薄弱题目转化为下一节课讲解和短练复测，不要复述题目通过率。";
                    case "CLASS" -> "优先判断哪些实验反复拖低班级表现，并给出本学期后续节奏调整。";
                    default -> "优先判断课程结构和实验梯度问题，并给出跨班级教学调整。";
                }
        );
    }

    private Map<String, Object> teachingContext(String level, Map<String, Object> metrics) {
        Map<String, Object> diagnosis = metrics.get("learningDiagnosis") instanceof Map<?, ?> map ? castGenericMap(map) : Map.of();
        List<Map<String, Object>> priorityProblems = priorityProblems(diagnosis);
        List<Map<String, Object>> priorityKnowledgePoints = priorityKnowledgePoints(diagnosis);
        List<Map<String, Object>> experimentWeaknessRanking = experimentWeaknessRanking(level, metrics, diagnosis);
        Map<String, Object> studentLayerSummary = studentLayerSummary(metrics);
        return mapOf(
                "usageRule", "这是 AI 生成“下一步怎么教”的优先输入。先用 priorityProblems 定讲解内容，再用 priorityKnowledgePoints 定补救知识点，用 studentLayerSummary 定分层动作；不要把这些原样堆到前端。",
                "nextTeachingPlanInput", mapOf(
                        "summary", mapText(diagnosis, "nextTeachingAction", "请根据优先错题、知识点和分层摘要生成具体课堂步骤。"),
                        "firstStepMustUse", priorityProblems.isEmpty() ? null : priorityProblems.get(0),
                        "studentLayerSummary", studentLayerSummary
                ),
                "priorityProblems", priorityProblems,
                "priorityKnowledgePoints", priorityKnowledgePoints,
                "experimentWeaknessRanking", experimentWeaknessRanking,
                "studentLayerSummary", studentLayerSummary
        );
    }

    private List<Map<String, Object>> priorityProblems(Map<String, Object> diagnosis) {
        List<Map<String, Object>> result = new ArrayList<>();
        Object rawProblems = diagnosis.get("problemErrorPoints");
        if (rawProblems instanceof List<?> rows) {
            rows.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(this::castGenericMap)
                    .limit(5)
                    .forEach(row -> result.add(mapOf(
                            "source", "problemErrorPoints",
                            "problemNo", row.get("problemNo"),
                            "title", row.get("title"),
                            "problemStatementSummary", row.get("problemStatementSummary"),
                            "errorPoint", row.get("errorPoint"),
                            "dominantStatus", row.get("dominantStatus"),
                            "affectedStudentCount", toInt(row.get("affectedStudentCount")),
                            "averageAttempts", toDouble(row.get("averageAttempts")),
                            "acceptanceRate", toDouble(row.get("acceptanceRate")),
                            "inferredKnowledge", row.get("inferredKnowledge"),
                            "knowledgePath", row.get("knowledgePath"),
                            "difficultyLabel", row.get("difficultyLabel"),
                            "knowledgeSource", row.get("knowledgeSource"),
                            "teachingAdvice", row.get("teachingAdvice"),
                            "validation", row.get("validation"),
                            "confidence", row.get("confidence"),
                            "evidenceRefs", normalizedEvidenceRefs(row)
                    )));
        }
        if (result.size() >= 5) return result;
        Object rawWeak = diagnosis.get("weakProblemSignals");
        if (rawWeak instanceof List<?> rows) {
            rows.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(this::castGenericMap)
                    .limit(5 - result.size())
                    .forEach(row -> result.add(mapOf(
                            "source", "weakProblemSignals",
                            "problemNo", row.get("problemNo"),
                            "title", row.get("title"),
                            "errorPoint", row.get("diagnosis"),
                            "averageAttempts", toDouble(row.get("averageAttempts")),
                            "acceptanceRate", toDouble(row.get("acceptanceRate")),
                            "inferredKnowledge", row.get("inferredKnowledge"),
                            "teachingAdvice", row.get("teachingAdvice"),
                            "confidence", row.get("confidence"),
                            "evidenceRefs", normalizedEvidenceRefs(row)
                    )));
        }
        return result;
    }

    private List<Map<String, Object>> priorityKnowledgePoints(Map<String, Object> diagnosis) {
        Object raw = diagnosis.get("inferredKnowledgeSignals");
        if (!(raw instanceof List<?> rows)) return List.of();
        return rows.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::castGenericMap)
                .limit(6)
                .map(row -> mapOf(
                        "knowledge", row.get("knowledge"),
                        "confidence", row.get("confidence"),
                        "sourceTypes", row.get("sourceTypes"),
                        "diagnosis", row.get("diagnosis"),
                        "teachingAdvice", row.get("teachingAdvice"),
                        "evidenceRefs", normalizedEvidenceRefs(row)
                ))
                .toList();
    }

    private List<Map<String, Object>> experimentWeaknessRanking(String level, Map<String, Object> metrics, Map<String, Object> diagnosis) {
        if ("EXPERIMENT".equals(level)) return List.of();
        Object raw = diagnosis.get("trendSignals");
        List<Map<String, Object>> trendRows = raw instanceof List<?> rows
                ? rows.stream().filter(Map.class::isInstance).map(Map.class::cast).map(this::castGenericMap).limit(5).toList()
                : List.of();
        if (!trendRows.isEmpty()) return trendRows.stream()
                .map(row -> mapOf(
                        "name", row.get("objectName"),
                        "riskScore", round1(trendSeverity(row)),
                        "reason", row.get("diagnosis"),
                        "mainWeakKnowledge", row.get("inferredKnowledge"),
                        "teachingAdvice", row.get("teachingAdvice"),
                        "evidenceRefs", normalizedEvidenceRefs(row)
                ))
                .toList();

        String field = "CLASS".equals(level) ? "experiments" : "experimentSummary";
        if (!(metrics.get(field) instanceof List<?> rows)) return List.of();
        return rows.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::castGenericMap)
                .filter(row -> !isNonRequiredExperiment(row))
                .sorted((left, right) -> {
                    int completion = Double.compare(toDouble(left.get("completionRate")), toDouble(right.get("completionRate")));
                    return completion != 0 ? completion : Double.compare(toDouble(left.get("averageScore")), toDouble(right.get("averageScore")));
                })
                .limit(5)
                .map(row -> {
                    String name = mapText(row, "name", "当前实验");
                    String knowledge = inferKnowledge(name);
                    return mapOf(
                            "name", name,
                            "riskScore", round1(Math.max(0, 100 - toDouble(row.get("completionRate"))) + Math.max(0, 60 - toDouble(row.get("averageScore")))),
                            "completionRate", toDouble(row.get("completionRate")),
                            "averageScore", toDouble(row.get("averageScore")),
                            "mainWeakKnowledge", knowledge,
                            "reason", "该实验完成率或均分在当前范围内相对靠后，应优先核对是否存在共性卡点。",
                            "teachingAdvice", trendTeachingAdvice(knowledge, name),
                            "evidenceRefs", evidenceRefs(row.get("evidenceId"))
                    );
                })
                .toList();
    }

    private Map<String, Object> studentLayerSummary(Map<String, Object> metrics) {
        Map<String, Object> distribution = metrics.get("scoreDistribution") instanceof Map<?, ?> map ? castGenericMap(map) : Map.of();
        Map<String, Object> segments = metrics.get("studentSegments") instanceof Map<?, ?> map ? castGenericMap(map) : Map.of();
        int support = Math.max(toInt(distribution.get("highRisk")) + toInt(distribution.get("incomplete")), toInt(segments.get("highRisk")));
        int improve = Math.max(toInt(distribution.get("middle")) + toInt(distribution.get("risk")), toInt(segments.get("regular")));
        int extend = Math.max(toInt(distribution.get("excellent")), toInt(segments.get("strong")));
        Object evidenceId = distribution.get("evidenceId") != null ? distribution.get("evidenceId") : segments.get("evidenceId");
        return mapOf(
                "supportCount", support,
                "improveCount", improve,
                "extendCount", extend,
                "supportAction", support > 0 ? "先用最小样例和同类短练确认关键步骤、实验环境和提交路径。" : "暂无明显重点帮扶层，保留观察。",
                "improveAction", improve > 0 ? "用边界样例清单和代码/报告自查清单提升迁移稳定性。" : "中等提升层数量不突出，可按常规节奏推进。",
                "extendAction", extend > 0 ? "安排异常样例、代码结构优化或原理解释任务，并参与共性错误复盘。" : "拓展层暂不突出，先保证共性薄弱点闭环。",
                "jumpTarget", "student-layer-analysis",
                "evidenceRefs", evidenceRefs(evidenceId)
        );
    }

    private List<Map<String, Object>> weakestRows(Map<String, Object> metrics, String field, String scoreField, int limit) {
        if (!(metrics.get(field) instanceof List<?> rows)) return List.of();
        return rows.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::castGenericMap)
                .sorted((left, right) -> Double.compare(toDouble(left.get(scoreField)), toDouble(right.get(scoreField))))
                .limit(limit)
                .toList();
    }

    private List<Map<String, Object>> problemErrorStatusSummary(String predicate, Map<String, Object> params) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows(
                "SELECT UPPER(COALESCE(NULLIF(TRIM(sps.latest_status), ''), 'UNKNOWN')) AS latest_status, " +
                "COUNT(sps.id), COUNT(DISTINCT sps.student_id), COUNT(DISTINCT sps.problem_id), " +
                "ROUND(AVG(COALESCE(sps.attempt_count, 0)), 1), ROUND(AVG(COALESCE(sps.best_score, 0)), 1) " +
                "FROM student_problem_state sps " +
                "JOIN assignment_offering ao ON ao.id = sps.offering_id " +
                "JOIN teaching_class tc ON tc.id = ao.class_id " +
                "LEFT JOIN course c ON c.id = tc.course_id " +
                "WHERE " + predicate + " " +
                "GROUP BY latest_status ORDER BY COUNT(sps.id) DESC, latest_status LIMIT 8",
                params
        )) {
            result.add(mapOf(
                    "status", row[0],
                    "recordCount", toInt(row[1]),
                    "studentCount", toInt(row[2]),
                    "problemCount", toInt(row[3]),
                    "averageAttempts", toDouble(row[4]),
                    "averageBestScore", toDouble(row[5])
            ));
        }
        return result;
    }

    private List<Map<String, Object>> problemErrorPoints(String predicate, Map<String, Object> params) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows(
                "SELECT CAST(ap.id AS SIGNED), ap.problem_no, ap.title, ap.statement_md, ap.max_score, " +
                "apd.knowledge_leaf, apd.knowledge_path, apd.difficulty_label, apd.content, " +
                "COUNT(DISTINCT sps.student_id) AS attempted_students, " +
                "SUM(CASE WHEN sps.accepted_at IS NULL THEN 1 ELSE 0 END) AS not_accepted_count, " +
                "SUM(CASE WHEN UPPER(COALESCE(sps.latest_status, '')) = 'COMPILE_ERROR' THEN 1 ELSE 0 END) AS compile_count, " +
                "SUM(CASE WHEN UPPER(COALESCE(sps.latest_status, '')) = 'WRONG_ANSWER' THEN 1 ELSE 0 END) AS wrong_count, " +
                "SUM(CASE WHEN UPPER(COALESCE(sps.latest_status, '')) = 'PARTIAL_ACCEPTED' THEN 1 ELSE 0 END) AS partial_count, " +
                "SUM(CASE WHEN UPPER(COALESCE(sps.latest_status, '')) IN ('TIME_LIMIT_EXCEEDED', 'MEMORY_LIMIT_EXCEEDED') THEN 1 ELSE 0 END) AS limit_count, " +
                "SUM(CASE WHEN UPPER(COALESCE(sps.latest_status, '')) IN ('SEGMENTATION_FAULT', 'RUNTIME_ERROR') THEN 1 ELSE 0 END) AS runtime_count, " +
                "SUM(CASE WHEN UPPER(COALESCE(sps.latest_status, '')) = 'PRESENTATION_ERROR' THEN 1 ELSE 0 END) AS presentation_count, " +
                "SUM(CASE WHEN UPPER(COALESCE(sps.latest_status, '')) = 'WAITING' THEN 1 ELSE 0 END) AS waiting_count, " +
                "ROUND(AVG(COALESCE(sps.attempt_count, 0)), 1) AS avg_attempts, " +
                "ROUND(AVG(COALESCE(sps.best_score, 0)), 1) AS avg_best_score, " +
                "ROUND(100 * SUM(CASE WHEN sps.accepted_at IS NOT NULL THEN 1 ELSE 0 END) / NULLIF(COUNT(sps.id), 0), 1) AS acceptance_rate " +
                "FROM student_problem_state sps " +
                "JOIN assignment_problem ap ON ap.id = sps.problem_id AND ap.offering_id = sps.offering_id AND ap.status = 'ACTIVE' " +
                "JOIN assignment_offering ao ON ao.id = sps.offering_id " +
                "JOIN teaching_class tc ON tc.id = ao.class_id " +
                "LEFT JOIN pta_problem_detail apd ON apd.id = (" +
                "SELECT pd.id FROM pta_problem_detail pd " +
                "WHERE (pd.problem_set_problem_id COLLATE utf8mb4_unicode_ci = ap.problem_no COLLATE utf8mb4_unicode_ci " +
                "OR (ap.source_problem_id IS NOT NULL AND pd.problem_set_problem_id COLLATE utf8mb4_unicode_ci = ap.source_problem_id COLLATE utf8mb4_unicode_ci) " +
                "OR (ap.source_problem_id IS NOT NULL AND pd.pta_global_problem_id COLLATE utf8mb4_unicode_ci = ap.source_problem_id COLLATE utf8mb4_unicode_ci)) " +
                "ORDER BY CASE " +
                "WHEN ao.pta_problem_set_id IS NOT NULL AND pd.problem_set_id COLLATE utf8mb4_unicode_ci = ao.pta_problem_set_id COLLATE utf8mb4_unicode_ci THEN 0 " +
                "WHEN CAST(pd.experiment_id AS CHAR) = CAST(ao.id AS CHAR) THEN 1 " +
                "ELSE 2 END, pd.updated_at DESC, pd.id DESC LIMIT 1" +
                ") " +
                "LEFT JOIN course c ON c.id = tc.course_id " +
                "WHERE " + predicate + " " +
                "GROUP BY ap.id, ap.problem_no, ap.title, ap.statement_md, ap.max_score, " +
                "apd.knowledge_leaf, apd.knowledge_path, apd.difficulty_label, apd.content " +
                "HAVING not_accepted_count > 0 " +
                "ORDER BY not_accepted_count DESC, avg_attempts DESC, acceptance_rate ASC LIMIT 12",
                params
        )) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("COMPILE_ERROR", toInt(row[11]));
            counts.put("WRONG_ANSWER", toInt(row[12]));
            counts.put("PARTIAL_ACCEPTED", toInt(row[13]));
            counts.put("TIME_OR_MEMORY_LIMIT", toInt(row[14]));
            counts.put("RUNTIME_OR_SEGMENTATION", toInt(row[15]));
            counts.put("PRESENTATION_ERROR", toInt(row[16]));
            counts.put("WAITING", toInt(row[17]));

            String dominantStatus = dominantStatus(counts);
            String title = textOr(asText(row[2]), "未知题目");
            String statementSummary = summarizeMarkdown(textOr(asText(row[3]), asText(row[8])), 240);
            String directKnowledge = textOr(asText(row[5]), "");
            String knowledge = !directKnowledge.isBlank()
                    ? directKnowledge
                    : inferKnowledge(title + " " + statementSummary);
            String knowledgeSource = !directKnowledge.isBlank() ? "PTA_KNOWLEDGE_LEAF" : "TITLE_AND_STATUS_INFERENCE";
            int affected = toInt(row[10]);
            double attempts = toDouble(row[18]);
            double acceptance = toDouble(row[20]);
            String errorPoint = errorPointFor(title, knowledge, dominantStatus, attempts, acceptance);
            result.add(mapOf(
                    "problemId", toLong(row[0]),
                    "problemNo", row[1],
                    "title", title,
                    "problemStatementSummary", statementSummary,
                    "statementAvailable", !statementSummary.isBlank(),
                    "maxScore", toDouble(row[4]),
                    "knowledgePath", row[6],
                    "difficultyLabel", row[7],
                    "knowledgeSource", knowledgeSource,
                    "attemptedStudentCount", toInt(row[9]),
                    "affectedStudentCount", affected,
                    "dominantStatus", dominantStatus,
                    "statusCounts", counts,
                    "averageAttempts", attempts,
                    "averageBestScore", toDouble(row[19]),
                    "acceptanceRate", acceptance,
                    "inferredKnowledge", knowledge,
                    "errorPoint", errorPoint,
                    "evidence", problemErrorEvidence(title, dominantStatus, affected, attempts, acceptance),
                    "teachingAdvice", errorPointTeachingAdvice(title, knowledge, dominantStatus, errorPoint),
                    "validation", errorPointValidation(knowledge, dominantStatus),
                    "confidence", errorPointConfidence(knowledge, dominantStatus, affected, knowledgeSource)
            ));
        }
        return result;
    }

    private void attachEvidenceRef(List<Map<String, Object>> rows, String evidenceId) {
        for (Map<String, Object> row : rows) {
            row.put("evidenceRefs", evidenceRefs(evidenceId));
        }
    }

    private String dominantStatus(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .filter(entry -> !"WAITING".equals(entry.getKey()))
                .max((left, right) -> Integer.compare(left.getValue(), right.getValue()))
                .filter(entry -> entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .orElseGet(() -> counts.getOrDefault("WAITING", 0) > 0 ? "WAITING" : "UNKNOWN");
    }

    private String errorPointFor(String title, String knowledge, String status, double attempts, double acceptance) {
        if ("COMPILE_ERROR".equals(status)) return "代码骨架/函数签名/结构体字段使用错误";
        if ("PARTIAL_ACCEPTED".equals(status)) return knowledgeSpecificBoundaryPoint(knowledge);
        if ("WRONG_ANSWER".equals(status)) return knowledgeSpecificLogicPoint(title, knowledge);
        if ("TIME_OR_MEMORY_LIMIT".equals(status)) return "复杂度估算或数据结构选择不当";
        if ("RUNTIME_OR_SEGMENTATION".equals(status)) return "指针、下标、递归出口或空结构处理错误";
        if ("PRESENTATION_ERROR".equals(status)) return "输出格式、空格或换行不符合题目要求";
        if ("WAITING".equals(status)) return "判题/同步状态异常，暂不能判定知识错误点";
        if (acceptance <= 30 && attempts >= 3) return knowledgeSpecificLogicPoint(title, knowledge);
        return knowledge.equals("待人工确认") ? "题意理解、边界处理或代码实现稳定性不足" : knowledge + "掌握不稳定";
    }

    private String knowledgeSpecificBoundaryPoint(String knowledge) {
        if (knowledge.contains("链表")) return "链表空表、首尾结点、重复元素等边界处理不足";
        if (knowledge.contains("AVL")) return "旋转类型判断、高度更新或平衡因子边界处理不足";
        if (knowledge.contains("散列表")) return "冲突处理、删除标记和查找终止条件边界不足";
        if (knowledge.contains("队列") || knowledge.contains("堆")) return "结构状态变化和临界容量处理不足";
        if (knowledge.contains("Huffman")) return "权值合并顺序或编码生成边界处理不足";
        return "边界样例覆盖不足";
    }

    private String knowledgeSpecificLogicPoint(String title, String knowledge) {
        if (knowledge.contains("链表前驱")) return "前驱指针维护和当前结点移动顺序错误";
        if (knowledge.contains("链表") && title.contains("合并")) return "双链同步遍历、去重和尾指针维护逻辑错误";
        if (knowledge.contains("链表") && title.contains("删除")) return "删除区间判断、前驱连接和头结点边界逻辑错误";
        if (knowledge.contains("AVL")) return "LL/RR/LR/RL 旋转判断和旋转后连接关系错误";
        if (knowledge.contains("二叉查找树")) return "查找路径、插入位置或删除替换结点选择错误";
        if (knowledge.contains("Huffman")) return "最小权值合并顺序和编码路径生成逻辑错误";
        if (knowledge.contains("散列表")) return "线性探测冲突链维护和删除后再插入逻辑错误";
        return knowledge.equals("待人工确认") ? "题意条件转代码分支时出现逻辑错误" : knowledge + "关键步骤逻辑错误";
    }

    private String problemErrorEvidence(String title, String status, int affected, double attempts, double acceptance) {
        return "题目“" + title + "”有 " + affected + " 名学生未通过，主要状态为 " + status
                + "，平均尝试 " + attempts + " 次，通过率 " + acceptance + "%。";
    }

    private String errorPointTeachingAdvice(String title, String knowledge, String status, String errorPoint) {
        if ("WAITING".equals(status)) return "先核对题目映射、PTA 判题和同步状态；确认数据正常后再做知识点诊断。";
        if ("COMPILE_ERROR".equals(status)) return "围绕题目“" + title + "”先统一检查函数签名、返回值、结构体字段和输入输出模板，再让学生改一个最小可编译样例。";
        if ("PARTIAL_ACCEPTED".equals(status)) return "针对“" + errorPoint + "”设计 3 个边界样例，当堂要求学生先写预期输出，再修改代码。";
        if ("WRONG_ANSWER".equals(status)) return "用题目“" + title + "”讲清“" + errorPoint + "”，先画状态变化/流程图，再让学生写同类变式题。";
        if ("TIME_OR_MEMORY_LIMIT".equals(status)) return "先让学生估算数据规模和复杂度，再对比当前做法与合适数据结构，最后用一组大数据样例复测。";
        if ("RUNTIME_OR_SEGMENTATION".equals(status)) return "用最小崩溃样例定位“" + errorPoint + "”，要求学生按空结构、边界下标、递归出口顺序自查。";
        return problemTeachingAdvice(knowledge, title);
    }

    private String errorPointValidation(String knowledge, String status) {
        if ("COMPILE_ERROR".equals(status)) return "学生能独立提交一个通过编译的最小函数/程序。";
        if ("PARTIAL_ACCEPTED".equals(status)) return "学生能补齐至少 3 个边界样例并通过同类小题。";
        if ("WRONG_ANSWER".equals(status)) return "学生能口头说明关键判断条件，并在同类变式题中正确实现。";
        if ("TIME_OR_MEMORY_LIMIT".equals(status)) return "学生能写出复杂度估算，并选择更合适的数据结构或算法。";
        if ("RUNTIME_OR_SEGMENTATION".equals(status)) return "学生能用最小样例复现并修复越界/空指针/递归出口问题。";
        if ("WAITING".equals(status)) return "同步状态恢复后重新计算题目结果。";
        return knowledge.contains("链表") ? "学生能画出指针移动过程并通过同类小题。" : "学生能完成同类短练并说出一个自查点。";
    }

    private String errorPointConfidence(String knowledge, String status, int affected, String knowledgeSource) {
        if ("WAITING".equals(status) || affected <= 1) return "LOW";
        if ("PTA_KNOWLEDGE_LEAF".equals(knowledgeSource)) return "HIGH";
        if ("待人工确认".equals(knowledge)) return "MEDIUM";
        return "HIGH";
    }

    private boolean isProblemDataNoise(Map<String, Object> row) {
        String title = mapText(row, "title", "");
        String problemNo = mapText(row, "problemNo", "");
        return "0".equals(problemNo)
                || title.equalsIgnoreCase("PTA Problem 0")
                || title.equals("未知题目")
                || title.isBlank();
    }

    private boolean isNonRequiredExperiment(Map<String, Object> row) {
        String name = (mapText(row, "name", "") + mapText(row, "title", "")).toLowerCase(Locale.ROOT);
        return name.contains("测试") || name.contains("不用完成") || name.contains("不需完成") || name.contains("无需完成");
    }

    private boolean containsNoisyProblem(Map<String, Object> metrics) {
        Object raw = metrics.get("problemPerformance");
        if (!(raw instanceof List<?> rows)) return false;
        return rows.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::castGenericMap)
                .anyMatch(this::isProblemDataNoise);
    }

    private boolean hasDirectKnowledgeTags(Map<String, Object> metrics) {
        Object raw = metrics.get("problemErrorPoints");
        if (!(raw instanceof List<?> rows)) return false;
        return rows.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::castGenericMap)
                .anyMatch(row -> "PTA_KNOWLEDGE_LEAF".equals(row.get("knowledgeSource"))
                        || !mapText(row, "knowledgePath", "").isBlank());
    }

    private boolean containsNonRequiredExperiment(Map<String, Object> metrics) {
        for (String field : List.of("experiments", "experimentSummary")) {
            Object raw = metrics.get(field);
            if (!(raw instanceof List<?> rows)) continue;
            boolean found = rows.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(this::castGenericMap)
                    .anyMatch(this::isNonRequiredExperiment);
            if (found) return true;
        }
        return false;
    }

    private String inferKnowledge(String text) {
        String value = textOr(text, "").toLowerCase(Locale.ROOT);
        if (value.isBlank()) return "待人工确认";
        if (value.contains("avl")) return "AVL 树旋转与平衡调整";
        if (value.contains("huffman") || value.contains("哈夫曼")) return "Huffman 树与编码构造";
        if (value.contains("优先级队列") || value.contains("priority queue") || value.contains("堆")) return "优先级队列/堆调整";
        if (value.contains("并查集")) return "并查集查找与合并";
        if (value.contains("散列") || value.contains("哈希") || value.contains("hash")) return "散列表冲突处理";
        if (value.contains("二叉查找树") || value.contains("bst")) return "二叉查找树插入/查找/删除";
        if (value.contains("二叉树") || value.contains("遍历")) return "二叉树遍历与递归";
        if (value.contains("链表") || value.contains("结点") || value.contains("节点") || value.contains("多项式")) {
            if (value.contains("前驱")) return "链表前驱定位";
            if (value.contains("合并")) return "有序链表合并与去重";
            if (value.contains("删除")) return "链表删除与边界处理";
            if (value.contains("多项式")) return "链表建模与多项式操作";
            return "链表指针维护与边界处理";
        }
        if (value.contains("队列")) return "队列状态维护与模拟";
        if (value.contains("栈")) return "栈操作与表达式/括号匹配";
        if (value.contains("顺序表")) return "顺序表查找、插入与合并";
        if (value.contains("复杂度")) return "算法时间复杂度分析";
        if (value.contains("排序") || value.contains("sort")) return "排序过程与边界处理";
        return "待人工确认";
    }

    private String problemDiagnosis(String title, String knowledge, double acceptance, double attempts) {
        if (acceptance <= 30 && attempts >= 3) {
            return "题目“" + title + "”通过率低且平均尝试次数偏高，说明学生反复修改后仍未稳定掌握“" + knowledge + "”。";
        }
        if (acceptance <= 60) return "题目“" + title + "”通过率偏低，需要把“" + knowledge + "”拆成步骤讲解和同类短练。";
        return "题目“" + title + "”虽有通过基础，但尝试次数偏高，建议补充边界样例和自查方法。";
    }

    private String problemTeachingAdvice(String knowledge, String title) {
        if (knowledge.contains("链表")) return "用题目“" + title + "”现场画出 pre/cur/next 移动过程，要求学生补写空表、单结点、首尾结点三个边界样例。";
        if (knowledge.contains("AVL")) return "先用 2 个插入序列手推 LL/RR/LR/RL 旋转，再安排一道只判断旋转类型的小题复测。";
        if (knowledge.contains("队列") || knowledge.contains("堆") || knowledge.contains("优先级队列")) return "先讲状态变化表和出入队/堆调整过程，再让学生按步骤模拟一轮后写代码。";
        if (knowledge.contains("Huffman")) return "先让学生手工构造一次权值合并过程，再写编码表，不要直接进入完整编码程序。";
        if (knowledge.contains("散列表")) return "用线性探测删除再插入的冲突链演示，要求学生说明删除标记和查找终止条件。";
        if (knowledge.contains("复杂度")) return "用两段代码对比循环层数和数据规模，先判断复杂度再写优化方案。";
        return "围绕题目“" + title + "”拆解判断条件、边界样例和调试顺序，讲完立即安排同类短练。";
    }

    private String trendDiagnosis(String name, double completion, double score, double completionDelta, double scoreDelta) {
        List<String> parts = new ArrayList<>();
        if (completion > 0 && completion < 70) parts.add("完成率偏低");
        if (score < 60) parts.add("均分偏低");
        if (completionDelta <= -15) parts.add("完成率较上一环节明显下降");
        if (scoreDelta <= -10) parts.add("均分较上一环节明显下降");
        String reason = parts.isEmpty() ? "出现阶段性波动" : String.join("、", parts);
        return "“" + name + "”" + reason + "，应先判断是否存在前置知识断点或实验难度陡增。";
    }

    private String trendTeachingAdvice(String knowledge, String name) {
        if (!"待人工确认".equals(knowledge)) {
            return "围绕“" + knowledge + "”为“" + name + "”补一节短讲：先讲前置概念，再做 1 道最小题，最后用同类小题复测。";
        }
        return "把“" + name + "”中完成率或均分下滑的步骤拆出来，先做教师示范和同类短练，再推进下一次实验。";
    }

    private double trendSeverity(Map<String, Object> row) {
        double completion = toDouble(row.get("completionRate"));
        double score = toDouble(row.get("averageScore"));
        double completionDelta = Math.abs(Math.min(0, toDouble(row.get("completionDelta"))));
        double scoreDelta = Math.abs(Math.min(0, toDouble(row.get("scoreDelta"))));
        return Math.max(0, 100 - completion) + Math.max(0, 60 - score) + completionDelta + scoreDelta;
    }

    private String statusDiagnosis(String status) {
        return switch (status) {
            case "COMPILE_ERROR" -> "编译错误集中，优先怀疑函数签名、结构体字段、输入输出格式或 PTA 模板适配问题。";
            case "WRONG_ANSWER" -> "答案错误集中，主体逻辑或条件分支可能没有想清楚。";
            case "PARTIAL_ACCEPTED" -> "部分正确集中，说明主流程能写出，但边界样例、特殊情况或测试覆盖不足。";
            case "TIME_LIMIT_EXCEEDED" -> "超时集中，说明复杂度判断或数据结构选择需要补讲。";
            case "SEGMENTATION_FAULT", "RUNTIME_ERROR" -> "运行异常集中，应检查指针、数组越界、递归出口或空结构处理。";
            case "PRESENTATION_ERROR" -> "格式错误集中，应统一输入输出格式和空格换行要求。";
            case "WAITING" -> "等待状态不能直接代表学习薄弱，可能是判题或同步状态异常。";
            case "ACCEPTED" -> "已通过记录较多，可作为正向样例来源。";
            default -> "该状态需要结合原始提交进一步核对。";
        };
    }

    private String statusTeachingAdvice(String status) {
        return switch (status) {
            case "COMPILE_ERROR" -> "课前 5 分钟统一检查函数原型、返回值、结构体字段和样例输入输出，再进入算法讲解。";
            case "WRONG_ANSWER" -> "用一题演示从题意条件到分支判断的推导过程，要求学生写出反例再改代码。";
            case "PARTIAL_ACCEPTED" -> "每道题补一张边界样例清单，至少覆盖空结构、重复元素、首尾位置和极值规模。";
            case "TIME_LIMIT_EXCEEDED" -> "先让学生估算复杂度，再对比暴力解和合适数据结构的运行规模。";
            case "SEGMENTATION_FAULT", "RUNTIME_ERROR" -> "用最小崩溃样例讲指针/下标/递归出口检查顺序，要求学生先定位再修改。";
            case "PRESENTATION_ERROR" -> "统一输出格式检查清单，提交前先跑样例并逐字符核对空格换行。";
            case "WAITING" -> "先核对 PTA 判题、同步任务和题目映射，不把该状态作为知识点补救依据。";
            case "ACCEPTED" -> "从高质量通过代码中抽 1 个结构清晰的样例，给中低层学生做对照阅读。";
            default -> "抽样查看原始提交后再决定是补概念、补边界还是补环境。";
        };
    }

    private String statusDataCaution(String status) {
        if ("WAITING".equals(status)) return "该状态优先视为数据/判题状态，不能单独证明学生不会。";
        if ("ACCEPTED".equals(status)) return "通过记录是正向证据，不应被当作风险。";
        return "可作为学习薄弱证据，但仍需结合题目名称和尝试次数。";
    }

    private String knowledgeTeachingAdvice(String knowledge) {
        if (knowledge.contains("链表")) return "用图示统一讲指针移动、前驱维护和首尾边界，再用 1 道同类小题复测。";
        if (knowledge.contains("AVL")) return "先补四类旋转的手推过程，再让学生判断旋转类型和更新高度。";
        if (knowledge.contains("Huffman")) return "先补权值合并和编码表生成过程，再进入完整编码实现。";
        if (knowledge.contains("散列表")) return "先讲冲突处理、删除标记和查找终止条件，再做线性探测短练。";
        if (knowledge.contains("队列") || knowledge.contains("堆")) return "先让学生画出结构状态变化，再写关键操作代码。";
        if (knowledge.contains("复杂度")) return "先用数据规模反推可接受复杂度，再对照代码循环层数。";
        return "围绕该知识点补判断路径、边界样例和同类短练。";
    }

    private List<String> evidenceRefs(Object... refs) {
        List<String> result = new ArrayList<>();
        for (Object ref : refs) {
            String value = asText(ref);
            if (value != null && !value.isBlank() && !result.contains(value)) result.add(value);
        }
        return result;
    }

    private List<String> normalizedEvidenceRefs(Map<String, Object> row) {
        Object rawRefs = row.get("evidenceRefs");
        if (rawRefs instanceof List<?> refs) {
            List<String> result = new ArrayList<>();
            refs.forEach(ref -> {
                String value = asText(ref);
                if (value != null && !value.isBlank() && !result.contains(value)) result.add(value);
            });
            return result;
        }
        return evidenceRefs(row.get("evidenceId"));
    }

    private List<Map<String, Object>> courseClassComparison(
            Long teacherId,
            ScopeAnchor anchor,
            boolean includeHistory,
            int startIndex,
            List<Map<String, Object>> evidence
    ) {
        String predicate = courseTermPredicate(anchor, includeHistory);
        Map<String, Object> params = courseTermParams(anchor, includeHistory);
        params.put("teacherId", teacherId);
        List<Map<String, Object>> result = new ArrayList<>();
        int index = startIndex;
        for (Object[] row : rows(
                "SELECT CAST(tc.id AS SIGNED), tc.name, COUNT(DISTINCT ao.id), COUNT(DISTINCT sa.student_id), " +
                "ROUND(100 * SUM(CASE WHEN " + COMPLETED + " THEN 1 ELSE 0 END) / NULLIF(COUNT(sa.id), 0), 1), " +
                "ROUND(AVG(COALESCE(sa.best_total_score, sa.latest_total_score)), 1) " +
                "FROM teaching_class tc LEFT JOIN course c ON c.id = tc.course_id " +
                "JOIN assignment_offering ao ON ao.class_id = tc.id AND ao.teacher_id = :teacherId " +
                "LEFT JOIN student_assignment sa ON sa.offering_id = ao.id " +
                "WHERE tc.teacher_id = :teacherId AND " + predicate + " " +
                "GROUP BY tc.id, tc.name ORDER BY tc.name",
                params
        )) {
            String evidenceId = evidenceId(index++);
            Map<String, Object> item = mapOf(
                    "evidenceId", evidenceId, "classId", toLong(row[0]), "className", row[1],
                    "experimentCount", toInt(row[2]), "studentCount", toInt(row[3]),
                    "completionRate", toDouble(row[4]), "averageScore", toDouble(row[5])
            );
            result.add(item);
            evidence.add(evidence(evidenceId, "同课程班级比较", item));
        }
        return result;
    }

    private List<Map<String, Object>> courseHistory(
            Long teacherId,
            ScopeAnchor anchor,
            int startIndex,
            List<Map<String, Object>> evidence
    ) {
        Map<String, Object> params = courseTermParams(anchor, true);
        params.put("teacherId", teacherId);
        List<Map<String, Object>> result = new ArrayList<>();
        int index = startIndex;
        for (Object[] row : rows(
                "SELECT CAST(tc.term_id AS SIGNED), COALESCE(t.name, '未标注学期'), COUNT(DISTINCT tc.id), " +
                "COUNT(DISTINCT ao.id), COUNT(DISTINCT sa.student_id), " +
                "ROUND(100 * SUM(CASE WHEN " + COMPLETED + " THEN 1 ELSE 0 END) / NULLIF(COUNT(sa.id), 0), 1), " +
                "ROUND(AVG(COALESCE(sa.best_total_score, sa.latest_total_score)), 1) " +
                "FROM teaching_class tc LEFT JOIN course c ON c.id = tc.course_id " +
                "LEFT JOIN academic_term t ON t.id = tc.term_id " +
                "JOIN assignment_offering ao ON ao.class_id = tc.id AND ao.teacher_id = :teacherId " +
                "LEFT JOIN student_assignment sa ON sa.offering_id = ao.id " +
                "WHERE tc.teacher_id = :teacherId AND " + courseTermPredicate(anchor, true) + " " +
                "GROUP BY tc.term_id, t.name, t.start_date ORDER BY t.start_date DESC, tc.term_id DESC",
                params
        )) {
            String evidenceId = evidenceId(index++);
            Map<String, Object> item = mapOf(
                    "evidenceId", evidenceId, "termId", toLong(row[0]), "termName", row[1],
                    "classCount", toInt(row[2]), "experimentCount", toInt(row[3]),
                    "studentCount", toInt(row[4]), "completionRate", toDouble(row[5]), "averageScore", toDouble(row[6])
            );
            result.add(item);
            evidence.add(evidence(evidenceId, "同课程历史学期", item));
        }
        return result;
    }

    private Map<String, Object> experimentScoreDistribution(Long teacherId, ScopeAnchor anchor) {
        List<Object[]> result = rows(
                "SELECT COUNT(sa.id), " +
                "SUM(CASE WHEN COALESCE(sa.best_total_score, sa.latest_total_score) >= 85 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN COALESCE(sa.best_total_score, sa.latest_total_score) >= 70 " +
                "AND COALESCE(sa.best_total_score, sa.latest_total_score) < 85 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN COALESCE(sa.best_total_score, sa.latest_total_score) >= 60 " +
                "AND COALESCE(sa.best_total_score, sa.latest_total_score) < 70 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN COALESCE(sa.best_total_score, sa.latest_total_score) < 60 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN sa.id IS NOT NULL AND NOT (" + COMPLETED + ") THEN 1 ELSE 0 END) " +
                "FROM assignment_offering ao LEFT JOIN student_assignment sa ON sa.offering_id = ao.id " +
                "WHERE ao.teacher_id = :teacherId AND ao.id = :experimentId",
                Map.of("teacherId", teacherId, "experimentId", anchor.experimentId())
        );
        return distributionMap(result);
    }

    private Map<String, Object> classScoreDistribution(Long teacherId, Long classId) {
        List<Object[]> result = rows(
                "SELECT COUNT(*), " +
                "SUM(CASE WHEN avg_score >= 85 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN avg_score >= 70 AND avg_score < 85 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN avg_score >= 60 AND avg_score < 70 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN avg_score < 60 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN completion_rate < 60 THEN 1 ELSE 0 END) " +
                "FROM (SELECT sa.student_id, AVG(COALESCE(sa.best_total_score, sa.latest_total_score, 0)) avg_score, " +
                "100 * AVG(CASE WHEN " + COMPLETED + " THEN 1 ELSE 0 END) completion_rate " +
                "FROM assignment_offering ao JOIN student_assignment sa ON sa.offering_id = ao.id " +
                "WHERE ao.teacher_id = :teacherId AND ao.class_id = :classId GROUP BY sa.student_id) student_stats",
                Map.of("teacherId", teacherId, "classId", classId)
        );
        return distributionMap(result);
    }

    private Map<String, Object> courseScoreDistribution(Long teacherId, ScopeAnchor anchor) {
        String predicate = courseTermPredicate(anchor, false);
        Map<String, Object> params = courseTermParams(anchor, false);
        params.put("teacherId", teacherId);
        List<Object[]> result = rows(
                "SELECT COUNT(*), " +
                "SUM(CASE WHEN avg_score >= 85 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN avg_score >= 70 AND avg_score < 85 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN avg_score >= 60 AND avg_score < 70 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN avg_score < 60 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN completion_rate < 60 THEN 1 ELSE 0 END) " +
                "FROM (SELECT sa.student_id, AVG(COALESCE(sa.best_total_score, sa.latest_total_score, 0)) avg_score, " +
                "100 * AVG(CASE WHEN " + COMPLETED + " THEN 1 ELSE 0 END) completion_rate " +
                "FROM teaching_class tc LEFT JOIN course c ON c.id = tc.course_id " +
                "JOIN assignment_offering ao ON ao.class_id = tc.id AND ao.teacher_id = :teacherId " +
                "JOIN student_assignment sa ON sa.offering_id = ao.id " +
                "WHERE tc.teacher_id = :teacherId AND " + predicate + " GROUP BY sa.student_id) student_stats",
                params
        );
        return distributionMap(result);
    }

    private List<Map<String, Object>> experimentFocusStudents(Long teacherId, ScopeAnchor anchor) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows(
                "SELECT sp.student_no, sp.real_name, COALESCE(sa.best_total_score, sa.latest_total_score), " +
                "sa.submission_status, sa.accepted_problem_count, sa.problem_count, " +
                "COUNT(sps.id) AS attempted_problem_count, " +
                "SUM(CASE WHEN sps.id IS NOT NULL AND sps.accepted_at IS NULL THEN 1 ELSE 0 END) AS failed_problem_count, " +
                "ROUND(AVG(COALESCE(sps.attempt_count, 0)), 1) AS average_attempts, " +
                "CASE WHEN NOT (" + COMPLETED + ") THEN '未完成或未提交' " +
                "WHEN COALESCE(sa.best_total_score, sa.latest_total_score, 0) < 60 THEN '低分风险' " +
                "WHEN sa.accepted_problem_count < sa.problem_count THEN '关键题未完全通过' " +
                "ELSE '需要跟进' END " +
                "FROM assignment_offering ao JOIN student_assignment sa ON sa.offering_id = ao.id " +
                "JOIN student_profile sp ON sp.id = sa.student_id " +
                "LEFT JOIN student_problem_state sps ON sps.offering_id = ao.id AND sps.student_id = sa.student_id " +
                "WHERE ao.teacher_id = :teacherId AND ao.id = :experimentId " +
                "AND (NOT (" + COMPLETED + ") OR COALESCE(sa.best_total_score, sa.latest_total_score, 0) < 70 " +
                "OR sa.accepted_problem_count < sa.problem_count OR COALESCE(sps.attempt_count, 0) >= 3) " +
                "GROUP BY sp.student_no, sp.real_name, sa.best_total_score, sa.latest_total_score, sa.submission_status, " +
                "sa.accepted_problem_count, sa.problem_count, sa.submission_status, sa.completion_evidence " +
                "ORDER BY COALESCE(sa.best_total_score, sa.latest_total_score, 0), sp.student_no",
                Map.of("teacherId", teacherId, "experimentId", anchor.experimentId())
        )) {
            Map<String, Object> item = mapOf(
                    "studentNo", row[0], "studentName", row[1], "score", toDouble(row[2]),
                    "submissionStatus", row[3], "acceptedProblemCount", toInt(row[4]),
                    "problemCount", toInt(row[5]), "attemptedProblemCount", toInt(row[6]),
                    "failedProblemCount", toInt(row[7]), "averageAttempts", toDouble(row[8]), "reason", row[9],
                    "suggestionHint", "优先检查本次实验关键题、基础语法和实验步骤理解情况"
            );
            enrichStudentFollowUp(item, true);
            result.add(item);
        }
        result.sort(this::compareStudentRisk);
        return diversifiedFocusStudents(result, 8);
    }

    private List<Map<String, Object>> classFocusStudents(Long teacherId, Long classId) {
        return focusStudentsByPredicate(
                "ao.teacher_id = :teacherId AND ao.class_id = :classId",
                Map.of("teacherId", teacherId, "classId", classId)
        );
    }

    private List<Map<String, Object>> courseFocusStudents(Long teacherId, ScopeAnchor anchor) {
        Map<String, Object> params = courseTermParams(anchor, false);
        params.put("teacherId", teacherId);
        return focusStudentsByPredicate("tc.teacher_id = :teacherId AND " + courseTermPredicate(anchor, false), params);
    }

    private List<Map<String, Object>> focusStudentsByPredicate(String predicate, Map<String, Object> params) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows(
                "SELECT sp.student_no, sp.real_name, ss.avg_score, ss.completion_rate, ss.experiment_count, " +
                "ss.risk_count, ss.lowest_score, ss.incomplete_count, ss.low_score_count, " +
                "COALESCE(ps.failed_problem_count, 0), COALESCE(ps.average_attempts, 0), COALESCE(ps.problem_state_count, 0), " +
                "CASE WHEN ss.incomplete_count > 0 THEN '存在未完成实验' " +
                "WHEN ss.avg_score < 60 THEN '持续低分风险' " +
                "WHEN COALESCE(ps.failed_problem_count, 0) > 0 THEN '关键题未完全通过' " +
                "WHEN ss.low_score_count > 0 THEN '阶段性低分风险' " +
                "ELSE '表现波动，建议观察' END " +
                "FROM (SELECT sa.student_id, ROUND(AVG(COALESCE(sa.best_total_score, sa.latest_total_score, 0)), 1) AS avg_score, " +
                "ROUND(100 * AVG(CASE WHEN " + COMPLETED + " THEN 1 ELSE 0 END), 1) AS completion_rate, COUNT(sa.id) AS experiment_count, " +
                "SUM(CASE WHEN COALESCE(sa.best_total_score, sa.latest_total_score, 0) < 60 OR NOT (" + COMPLETED + ") THEN 1 ELSE 0 END) AS risk_count, " +
                "MIN(COALESCE(sa.best_total_score, sa.latest_total_score, 0)) AS lowest_score, " +
                "SUM(CASE WHEN NOT (" + COMPLETED + ") THEN 1 ELSE 0 END) AS incomplete_count, " +
                "SUM(CASE WHEN COALESCE(sa.best_total_score, sa.latest_total_score, 0) < 60 THEN 1 ELSE 0 END) AS low_score_count " +
                "FROM teaching_class tc LEFT JOIN course c ON c.id = tc.course_id " +
                "JOIN assignment_offering ao ON ao.class_id = tc.id " +
                "JOIN student_assignment sa ON sa.offering_id = ao.id " +
                "WHERE " + predicate + " GROUP BY sa.student_id) ss " +
                "JOIN student_profile sp ON sp.id = ss.student_id " +
                "LEFT JOIN (SELECT sps.student_id, " +
                "SUM(CASE WHEN sps.accepted_at IS NULL THEN 1 ELSE 0 END) AS failed_problem_count, " +
                "ROUND(AVG(COALESCE(sps.attempt_count, 0)), 1) AS average_attempts, COUNT(sps.id) AS problem_state_count " +
                "FROM teaching_class tc LEFT JOIN course c ON c.id = tc.course_id " +
                "JOIN assignment_offering ao ON ao.class_id = tc.id " +
                "JOIN student_problem_state sps ON sps.offering_id = ao.id " +
                "WHERE " + predicate + " GROUP BY sps.student_id) ps ON ps.student_id = ss.student_id " +
                "WHERE ss.risk_count > 0 OR ss.avg_score < 70 OR ss.completion_rate < 80 " +
                "OR COALESCE(ps.failed_problem_count, 0) > 0 OR COALESCE(ps.average_attempts, 0) >= 3 " +
                "ORDER BY ss.risk_count DESC, ss.avg_score ASC, ss.completion_rate ASC, sp.student_no",
                params
        )) {
            Map<String, Object> item = mapOf(
                    "studentNo", row[0], "studentName", row[1], "averageScore", toDouble(row[2]),
                    "completionRate", toDouble(row[3]), "experimentCount", toInt(row[4]),
                    "riskExperimentCount", toInt(row[5]), "lowestScore", toDouble(row[6]),
                    "incompleteExperimentCount", toInt(row[7]), "lowScoreExperimentCount", toInt(row[8]),
                    "failedProblemCount", toInt(row[9]), "averageAttempts", toDouble(row[10]),
                    "problemStateCount", toInt(row[11]), "reason", row[12],
                    "suggestionHint", "建议安排一次短周期跟进，确认基础知识、实验环境和报告分析问题"
            );
            enrichStudentPortraitFromTable(item, predicate, params);
            enrichStudentFollowUp(item, false);
            result.add(item);
        }
        result.sort(this::compareStudentRisk);
        return diversifiedFocusStudents(result, 8);
    }

    private void enrichStudentPortraitFromTable(Map<String, Object> item, String predicate, Map<String, Object> params) {
        String studentNo = mapText(item, "studentNo", "");
        double averageScore = toDouble(item.get("averageScore"));
        double completionRate = toDouble(item.get("completionRate"));
        List<Double> recentScores = recentScoresForStudent(predicate, params, studentNo);
        double recentAverageScore = recentScores.isEmpty()
                ? 0
                : round1(recentScores.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        String trend = "stable";
        if (recentScores.size() >= 2 && averageScore > 0) {
            if (recentAverageScore > averageScore + 3) {
                trend = "up";
            } else if (recentAverageScore < averageScore - 5) {
                trend = "down";
            }
        }
        String trendLabel = switch (trend) {
            case "up" -> "上升";
            case "down" -> "下降";
            default -> "稳定";
        };

        String riskLevel;
        String riskLabel;
        if (completionRate < 50 || averageScore < 50) {
            riskLevel = "HIGH";
            riskLabel = "高风险";
        } else if (completionRate < 70 || averageScore < 60 || "down".equals(trend)) {
            riskLevel = "MEDIUM";
            riskLabel = "中风险";
        } else if (completionRate < 80 || averageScore < 70) {
            riskLevel = "LOW";
            riskLabel = "低风险";
        } else {
            riskLevel = "NONE";
            riskLabel = "无风险";
        }

        item.put("abilityTrend", trend);
        item.put("abilityTrendLabel", trendLabel);
        item.put("recentAverageScore", recentAverageScore);
        item.put("recentScoreCount", recentScores.size());
        item.put("studentPortraitRiskLevel", riskLevel);
        item.put("studentPortraitRiskLabel", riskLabel);
        item.put("studentPortraitSummary", "学生画像表：完成率 " + formatMetric(completionRate)
                + "%，均分 " + formatMetric(averageScore)
                + "，趋势" + trendLabel
                + "，判定为" + riskLabel);
    }

    private List<Double> recentScoresForStudent(String predicate, Map<String, Object> params, String studentNo) {
        if (studentNo == null || studentNo.isBlank()) {
            return List.of();
        }
        Map<String, Object> queryParams = new LinkedHashMap<>(params);
        queryParams.put("studentNoForTrend", studentNo);
        String scoreExpr = "COALESCE(sa.best_total_score, sa.latest_total_score, 0)";
        List<Double> scores = new ArrayList<>();
        for (Object[] row : rows(
                "SELECT recent.score, recent.offering_id FROM (" +
                "SELECT " + scoreExpr + " AS score, ao.id AS offering_id, ao.deadline_at, ao.created_at " +
                "FROM teaching_class tc LEFT JOIN course c ON c.id = tc.course_id " +
                "JOIN assignment_offering ao ON ao.class_id = tc.id " +
                "JOIN student_assignment sa ON sa.offering_id = ao.id " +
                "JOIN student_profile sp ON sp.id = sa.student_id " +
                "WHERE " + predicate + " AND sp.student_no = :studentNoForTrend AND " + scoreExpr + " > 0 " +
                "ORDER BY COALESCE(ao.deadline_at, ao.created_at) DESC, ao.id DESC LIMIT 3" +
                ") recent",
                queryParams
        )) {
            scores.add(toDouble(row[0]));
        }
        return scores;
    }

    private List<Map<String, Object>> diversifiedFocusStudents(List<Map<String, Object>> students, int limit) {
        if (students == null || students.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<Map<String, Object>> sorted = new ArrayList<>(students);
        sorted.sort(this::compareStudentRisk);

        List<Map<String, Object>> selected = new ArrayList<>();
        Set<Map<String, Object>> selectedSet = Collections.newSetFromMap(new IdentityHashMap<>());
        List<String> groups = List.of(
                "SUBMISSION_BLOCKED",
                "REPEATED_FAILED_ATTEMPTS",
                "PROBLEM_NOT_PASSED",
                "LOW_SCORE",
                "VOLATILE"
        );

        for (String group : groups) {
            for (Map<String, Object> student : sorted) {
                if (selected.size() >= limit) {
                    return selected;
                }
                if (!selectedSet.contains(student) && group.equals(mapText(student, "followUpGroup", ""))) {
                    selected.add(student);
                    selectedSet.add(student);
                    break;
                }
            }
        }

        for (Map<String, Object> student : sorted) {
            if (selected.size() >= limit) {
                break;
            }
            if (selectedSet.add(student)) {
                selected.add(student);
            }
        }
        selected.sort(this::compareStudentRisk);
        return selected;
    }

    private void enrichStudentFollowUp(Map<String, Object> item, boolean experimentScope) {
        String reason = mapText(item, "reason", "需要进一步观察");
        double score = toDouble(item.get("score"));
        double averageScore = toDouble(item.get("averageScore"));
        double lowestScore = toDouble(item.get("lowestScore"));
        double completionRate = toDouble(item.get("completionRate"));
        int accepted = toInt(item.get("acceptedProblemCount"));
        int total = toInt(item.get("problemCount"));
        int riskCount = toInt(item.get("riskExperimentCount"));
        int experimentCount = toInt(item.get("experimentCount"));
        int incompleteCount = toInt(item.get("incompleteExperimentCount"));
        int lowScoreCount = toInt(item.get("lowScoreExperimentCount"));
        int failedProblemCount = toInt(item.get("failedProblemCount"));
        double averageAttempts = toDouble(item.get("averageAttempts"));
        boolean incomplete = reason.contains("未完成") || reason.contains("未提交")
                || (!experimentScope && (incompleteCount > 0 || completionRate > 0 && completionRate < 80));
        boolean lowScore = reason.contains("低分")
                || (experimentScope && score > 0 && score < 70)
                || (!experimentScope && (averageScore > 0 && averageScore < 70 || lowestScore > 0 && lowestScore < 60 || lowScoreCount > 0));
        boolean problemNotPassed = failedProblemCount > 0 || experimentScope && total > 0 && accepted < total;

        applyStudentRiskProfile(item, experimentScope, incomplete, lowScore, problemNotPassed);
        String followUpGroup = mapText(item, "followUpGroup", "");

        if ("SUBMISSION_BLOCKED".equals(followUpGroup)) {
            item.put("problem", experimentScope
                    ? "本次实验未完成或未提交，需要先判断是提交链路问题还是关键题不会做。"
                    : "存在未完成实验，先不要直接判定为知识薄弱，要先核对缺交、PTA 同步和提交路径。");
            item.put("cause", "可能是缺勤、账号绑定/PTA 同步异常、环境配置失败，或遇到第一道关键题后放弃。");
            item.put("teacherAction", "课后先让学生现场打开实验提交页和 PTA 记录：若无提交，补交一个最小可运行样例；若有提交但未同步，核对账号绑定；若卡在题目，指定最低通过率题重做 1 次。");
            item.put("validation", "下一次实验前必须补齐一次有效提交，并能说清楚自己卡在“环境/提交/题目步骤”中的哪一类。");
            item.put("followUpType", "INCOMPLETE");
        } else if ("REPEATED_FAILED_ATTEMPTS".equals(followUpGroup)) {
            item.put("problem", "已提交但反复尝试仍未通过，说明主要问题不是补交，而是解题路径或调试方法没有打通。");
            item.put("cause", "平均尝试次数偏高，可能卡在题意转代码、边界样例、调试顺序或某个核心知识点迁移。");
            item.put("teacherAction", "让学生展示最后一次失败提交和一次最接近通过的提交，教师只追问一个关键分支或边界样例，再安排同知识点最小变式题。");
            item.put("validation", "学生能指出失败提交中的一个具体错误，并在同知识点小题中一次性通过或明显减少尝试次数。");
            item.put("followUpType", "REPEATED_FAILED_ATTEMPTS");
        } else if ("PROBLEM_NOT_PASSED".equals(followUpGroup)) {
            String progress = experimentScope && total > 0 ? "本次实验只通过 " + accepted + "/" + total + " 题" : "存在 " + failedProblemCount + " 个未通过题目状态";
            item.put("problem", progress + "，说明不是单纯未交，而是关键题没有完全打通。");
            item.put("cause", "可能卡在题意转代码、边界样例、调试顺序或某个核心知识点迁移。");
            item.put("teacherAction", "让学生拿出未通过题的最后一次代码，先口头说明输入、输出和关键判断条件；教师只追问一个错误点，再布置同知识点的最小变式题。");
            item.put("validation", "当场完成 1 道同知识点小题，且能指出原题中一个具体自查点。");
            item.put("followUpType", "PROBLEM_NOT_PASSED");
        } else if ("LOW_SCORE".equals(followUpGroup)) {
            String scoreText = experimentScope ? "本次得分 " + score : "平均分 " + averageScore + "，最低分 " + lowestScore;
            item.put("problem", scoreText + "，需要定位是基础步骤不会、报告分析弱，还是某次实验断点明显。");
            item.put("cause", riskCount > 0
                    ? "该生在 " + riskCount + "/" + Math.max(experimentCount, 1) + " 次实验中出现低分或风险记录，可能存在连续薄弱环节。"
                    : "可能存在阶段性低分，需结合最低分实验和错题记录核对。");
            item.put("teacherAction", "不要泛泛要求重做；先选最低分实验的一道代表题，让学生复述解题步骤，再补一张“错因—修改—验证”三列表。");
            item.put("validation", "学生能把最低分题的错因写成一句话，并用一次重新提交或同类短练证明已修正。");
            item.put("followUpType", "LOW_SCORE");
        } else {
            item.put("problem", "表现波动，需要确认是否只是偶发失误，还是某类实验持续不稳定。");
            item.put("cause", "可能是边界样例、报告表达或提交节奏不稳定。");
            item.put("teacherAction", "抽查最近一次波动实验，让学生说明一次成功提交和一次失败提交的差异，再补一个边界样例。");
            item.put("validation", "后续一次同类实验不再出现相同错误类型，并能主动写出自查点。");
            item.put("followUpType", "VOLATILE");
        }
        item.put("suggestionHint", item.get("teacherAction"));
    }

    private void applyStudentRiskProfile(
            Map<String, Object> item,
            boolean experimentScope,
            boolean incomplete,
            boolean lowScore,
            boolean problemNotPassed
    ) {
        double score = toDouble(item.get("score"));
        double averageScore = toDouble(item.get("averageScore"));
        double lowestScore = toDouble(item.get("lowestScore"));
        double completionRate = toDouble(item.get("completionRate"));
        int riskCount = toInt(item.get("riskExperimentCount"));
        int experimentCount = toInt(item.get("experimentCount"));
        int incompleteCount = toInt(item.get("incompleteExperimentCount"));
        int lowScoreCount = toInt(item.get("lowScoreExperimentCount"));
        int failedProblemCount = toInt(item.get("failedProblemCount"));
        double averageAttempts = toDouble(item.get("averageAttempts"));
        String abilityTrend = mapText(item, "abilityTrend", "stable");
        String portraitRiskLevel = mapText(item, "studentPortraitRiskLevel", "");
        List<String> reasons = new ArrayList<>();
        int scoreValue = 0;

        if ("HIGH".equals(portraitRiskLevel)) {
            scoreValue += 18;
            reasons.add(mapText(item, "studentPortraitSummary", "学生画像表判定为高风险"));
        } else if ("MEDIUM".equals(portraitRiskLevel)) {
            scoreValue += 10;
            reasons.add(mapText(item, "studentPortraitSummary", "学生画像表判定为中风险"));
        } else if ("LOW".equals(portraitRiskLevel)) {
            scoreValue += 4;
            reasons.add(mapText(item, "studentPortraitSummary", "学生画像表判定为低风险"));
        }
        if ("down".equals(abilityTrend)) {
            scoreValue += 12;
            reasons.add("能力趋势下降，近期表现低于个人平均水平");
        }
        if (incomplete) {
            int weight = experimentScope ? 42 : 28 + Math.min(18, incompleteCount * 6);
            scoreValue += weight;
            reasons.add(experimentScope ? "本次实验未完成/未提交" : "存在 " + Math.max(incompleteCount, 1) + " 次未完成实验");
        }
        if (averageAttempts >= 4) {
            scoreValue += 22;
            reasons.add("平均尝试次数 " + averageAttempts + " 次，存在反复试错");
        } else if (averageAttempts >= 3) {
            scoreValue += 12;
            reasons.add("平均尝试次数偏高");
        }
        if (problemNotPassed) {
            int failed = experimentScope ? Math.max(0, toInt(item.get("problemCount")) - toInt(item.get("acceptedProblemCount"))) : failedProblemCount;
            scoreValue += Math.min(28, 12 + Math.max(failed, 1) * 5);
            reasons.add(failed > 0 ? failed + " 个题目未完全通过" : "关键题未完全通过");
        }
        if (lowScore) {
            if (experimentScope && score > 0 && score < 60 || !experimentScope && averageScore > 0 && averageScore < 60) {
                scoreValue += 30;
                reasons.add(experimentScope ? "本次得分低于 60" : "平均分低于 60");
            } else {
                scoreValue += 18;
                reasons.add(lowScoreCount > 0 ? lowScoreCount + " 次低分记录" : "分数低于稳定线");
            }
        }
        if (!experimentScope && riskCount >= 2) {
            scoreValue += Math.min(20, riskCount * 4);
            reasons.add("多次实验出现低分或未完成风险");
        }
        if (!experimentScope && completionRate > 0 && completionRate < 70) {
            scoreValue += 14;
            reasons.add("完成率低于 70%");
        }
        if (!experimentScope && lowestScore > 0 && lowestScore < 50) {
            scoreValue += 10;
            reasons.add("最低分低于 50");
        }
        if (scoreValue == 0) {
            scoreValue = 18;
            reasons.add("表现波动，需短周期观察");
        }
        if ("HIGH".equals(portraitRiskLevel)) {
            scoreValue = Math.max(scoreValue, 70);
        } else if ("MEDIUM".equals(portraitRiskLevel)) {
            scoreValue = Math.max(scoreValue, 40);
        } else if ("LOW".equals(portraitRiskLevel)) {
            scoreValue = Math.max(scoreValue, 24);
        }

        String level = scoreValue >= 70 ? "HIGH" : scoreValue >= 40 ? "MEDIUM" : "LOW";
        String priority = scoreValue >= 70 ? "P1" : scoreValue >= 40 ? "P2" : "P3";
        String group;
        if (incomplete && (experimentScope || incompleteCount > 0)) {
            group = "SUBMISSION_BLOCKED";
        } else if (averageAttempts >= 3 && problemNotPassed) {
            group = "REPEATED_FAILED_ATTEMPTS";
        } else if (problemNotPassed) {
            group = "PROBLEM_NOT_PASSED";
        } else if (lowScore) {
            group = "LOW_SCORE";
        } else {
            group = "VOLATILE";
        }
        item.put("riskScore", scoreValue);
        item.put("riskLevel", level);
        item.put("followUpPriority", priority);
        item.put("followUpGroup", group);
        item.put("riskReasons", reasons);
        item.put("riskSummary", String.join("；", reasons));
    }

    private int compareStudentRisk(Map<String, Object> left, Map<String, Object> right) {
        int risk = Integer.compare(toInt(right.get("riskScore")), toInt(left.get("riskScore")));
        if (risk != 0) return risk;
        int score = Double.compare(toDouble(left.get("averageScore")) + toDouble(left.get("score")), toDouble(right.get("averageScore")) + toDouble(right.get("score")));
        if (score != 0) return score;
        return mapText(left, "studentNo", "").compareTo(mapText(right, "studentNo", ""));
    }

    private Map<String, Object> studentSegments(Long teacherId, Long classId) {
        List<Object[]> result = rows(
                "SELECT COUNT(*), " +
                "SUM(CASE WHEN avg_score < 60 OR completion_rate < 60 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN avg_score >= 80 AND completion_rate >= 80 THEN 1 ELSE 0 END) " +
                "FROM (SELECT sa.student_id, AVG(COALESCE(sa.best_total_score, sa.latest_total_score, 0)) avg_score, " +
                "100 * AVG(CASE WHEN " + COMPLETED + " THEN 1 ELSE 0 END) completion_rate " +
                "FROM assignment_offering ao JOIN student_assignment sa ON sa.offering_id = ao.id " +
                "WHERE ao.teacher_id = :teacherId AND ao.class_id = :classId GROUP BY sa.student_id) student_stats",
                Map.of("teacherId", teacherId, "classId", classId)
        );
        Object[] row = result.isEmpty() ? new Object[]{0, 0, 0} : result.get(0);
        int total = toInt(row[0]);
        int risk = toInt(row[1]);
        int strong = toInt(row[2]);
        return mapOf("total", total, "highRisk", risk, "strong", strong, "regular", Math.max(0, total - risk - strong));
    }

    private ScopeAnchor requireClassAnchor(Long teacherId, Long classId) {
        if (classId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "classId is required");
        }
        TeachingClassEntity item = classRepository.findById(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "class not found"));
        if (!teacherId.equals(item.getTeacherId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "class not found");
        }
        return new ScopeAnchor(
                item.getId(), item.getCourseId(), item.getTermId(), item.getName(),
                textOr(resolveCourseName(item.getCourseId()), item.getCourseName()), resolveTermName(item.getTermId()),
                null, null, null
        );
    }

    private ScopeAnchor requireExperimentAnchor(Long teacherId, Long experimentId) {
        if (experimentId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "experimentId is required");
        }
        List<Object[]> result = rows(
                "SELECT CAST(ao.class_id AS SIGNED), CAST(tc.course_id AS SIGNED), CAST(tc.term_id AS SIGNED), tc.name, " +
                "COALESCE(c.name, tc.course_name), t.name, CAST(ao.id AS SIGNED), CAST(ao.template_id AS SIGNED), " +
                "COALESCE(NULLIF(TRIM(ao.title_override), ''), at.title) " +
                "FROM assignment_offering ao JOIN teaching_class tc ON tc.id = ao.class_id " +
                "JOIN assignment_template at ON at.id = ao.template_id LEFT JOIN course c ON c.id = tc.course_id " +
                "LEFT JOIN academic_term t ON t.id = tc.term_id " +
                "WHERE ao.id = :experimentId AND ao.teacher_id = :teacherId",
                Map.of("experimentId", experimentId, "teacherId", teacherId)
        );
        if (result.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "experiment not found");
        }
        Object[] row = result.get(0);
        return new ScopeAnchor(
                toLong(row[0]), toLong(row[1]), toLong(row[2]), String.valueOf(row[3]),
                textOr(asText(row[4]), "课程待补充"), textOr(asText(row[5]), "学期待补充"),
                toLong(row[6]), toLong(row[7]), String.valueOf(row[8])
        );
    }

    private Map<String, Object> scopeMap(String level, ScopeAnchor anchor, boolean includeHistory) {
        List<String> warnings = new ArrayList<>();
        if (anchor.courseId() == null) warnings.add("教学班未绑定 course_id，课程范围使用课程名称精确匹配");
        if (anchor.termId() == null) warnings.add("教学班未绑定 term_id，无法执行严格学期隔离");
        return mapOf(
                "level", level, "classId", anchor.classId(), "className", anchor.className(),
                "courseId", anchor.courseId(), "courseName", anchor.courseName(),
                "termId", anchor.termId(), "termName", anchor.termName(),
                "experimentId", anchor.experimentId(), "experimentName", anchor.experimentName(),
                "includeHistory", includeHistory, "warnings", warnings
        );
    }

    private TeachingAdviceReportEntity newReport(
            Long teacherId,
            String level,
            Map<String, Object> scope,
            Map<String, Object> metrics
    ) {
        TeachingAdviceReportEntity report = new TeachingAdviceReportEntity();
        report.setTeacherId(teacherId);
        report.setScopeLevel(level);
        report.setCourseId(toLong(scope.get("courseId")));
        report.setTermId(toLong(scope.get("termId")));
        report.setClassId(toLong(scope.get("classId")));
        report.setExperimentId(toLong(scope.get("experimentId")));
        report.setScopeJson(writeJson(scope));
        report.setMetricsJson(writeJson(metrics));
        report.setPromptVersion(TeachingAdvicePromptFactory.VERSION);
        report.setModel(aiProvider.model());
        return report;
    }

    private ObjectNode fallbackAdvice(String level, Map<String, Object> metrics) {
        List<String> ids = new ArrayList<>(evidenceIds(metrics));
        String primary = ids.isEmpty() ? null : ids.get(0);
        String focus = ids.size() > 1 ? ids.get(ids.size() - 1) : primary;
        Map<String, Object> diagnosis = metrics.get("learningDiagnosis") instanceof Map<?, ?> map ? castGenericMap(map) : Map.of();
        String diagnosisConclusion = mapText(diagnosis, "conclusion", "");
        String diagnosisAction = mapText(diagnosis, "nextTeachingAction", "");
        String inferredKnowledge = firstDiagnosisText(diagnosis, "inferredKnowledgeSignals", "knowledge");
        Map<String, Object> primaryProblem = primaryProblemForPlan(diagnosis, metrics);
        String problemLabel = problemLabel(primaryProblem);
        String problemKnowledge = problemKnowledge(primaryProblem, inferredKnowledge);
        String problemErrorPoint = problemErrorPoint(primaryProblem);
        String problemStatement = problemStatement(primaryProblem);
        String focusTarget = focusTargetForPlan(level, metrics);
        String focusDeliverable = focusDeliverableForPlan(metrics);
        String firstPlanEvidence = firstEvidenceRef(primaryProblem, primary);
        ObjectNode root = objectMapper.createObjectNode();
        String summary = !diagnosisAction.isBlank() ? diagnosisAction : switch (level) {
            case "EXPERIMENT" -> "下节课先围绕本实验低通过率环节做一次短讲和同类短练，再跟进未完成关键题的学生。";
            case "CLASS" -> "下一阶段先补班级反复薄弱的实验步骤和报告分析能力，再对低完成率学生做短周期跟进。";
            default -> "课程后续应先调整难度梯度和前置铺垫，把高频薄弱实验拆成讲解、短练、复测三个环节。";
        };
        root.put("summary", summary);
        ObjectNode nextTeachingPlanObject = root.putObject("nextTeachingPlan");
        nextTeachingPlanObject.put("summary", summary);
        nextTeachingPlanObject.put("priority", ids.isEmpty() ? "LOW" : "MEDIUM");
        ArrayNode nextTeachingSteps = nextTeachingPlanObject.putArray("steps");
        ObjectNode conclusion = root.putObject("teachingConclusion");
        conclusion.put("problem", !diagnosisConclusion.isBlank() ? diagnosisConclusion : switch (level) {
            case "EXPERIMENT" -> "学生不是单纯分数波动，而是在本实验关键题目或关键步骤上没有形成稳定迁移。";
            case "CLASS" -> "班级当前主要问题是基础完成能力和实验报告分析能力没有同步提升。";
            default -> "课程层面主要问题是实验难度梯度和前置铺垫不足，导致薄弱学生持续掉队。";
        });
        conclusion.put("cause", inferredKnowledge.isBlank()
                ? "可能原因是教师讲解、学生短练和复测验证之间没有形成闭环，部分学生卡在基础步骤后没有被及时拉回。"
                : "后端诊断层推断薄弱点集中在“" + inferredKnowledge + "”，需要结合证据题目和错误类型核对。");
        conclusion.put("impact", "如果继续只展示数据或提醒补交，后续同类实验仍会出现低完成、低通过和报告分析薄弱。");
        conclusion.put("priority", ids.isEmpty() ? "LOW" : "MEDIUM");
        ArrayNode conclusionRefs = conclusion.putArray("evidenceRefs");
        if (primary != null) conclusionRefs.add(primary);
        ArrayNode risks = root.putArray("risks");
        ObjectNode risk = risks.addObject();
        risk.put("level", "MEDIUM");
        risk.put("title", ids.isEmpty() ? "当前有效数据不足" : "需要优先关注低分、未完成或题目通过率较低的群体");
        ArrayNode riskRefs = risk.putArray("evidenceRefs");
        if (primary != null) riskRefs.add(primary);
        ArrayNode teacherFocus = root.putArray("teacherFocus");
        ObjectNode focusOne = teacherFocus.addObject();
        focusOne.put("title", inferredKnowledge.isBlank() ? "教师重点讲什么" : "优先补：" + inferredKnowledge);
        focusOne.put("instruction", !diagnosisAction.isBlank()
                ? diagnosisAction
                : "先讲最薄弱题目或实验步骤的判断方法，再演示一次从错误定位到修正的完整过程。不要从平均分讲起。要让学生知道卡点在哪里、为什么错、下一次如何自查。");
        focusOne.put("target", switch (level) {
            case "EXPERIMENT" -> "本实验未完成关键题或低通过率题目的学生，全班同步听关键步骤";
            case "CLASS" -> "班级中未完成、低分和报告分析薄弱学生，全班同步补共性方法";
            default -> "课程中薄弱班级和持续风险学生，同时给其他班级形成预防性提醒";
        });
        focusOne.put("when", "下节课前 15 分钟");
        ArrayNode focusOneRefs = focusOne.putArray("evidenceRefs");
        if (primary != null) focusOneRefs.add(primary);
        focusOne.put("successMetric", "同类短练中风险学生能独立完成关键步骤，并说出至少一个自查点");

        ObjectNode focusTwo = teacherFocus.addObject();
        focusTwo.put("title", "班级薄弱点怎么补");
        focusTwo.put("instruction", "把共性薄弱点拆成教师示范、学生当堂短练、教师点名复盘三步，避免只讲概念不看学生是否会做。分层学生使用不同任务要求。");
        focusTwo.put("target", "重点帮扶层和中等提升层");
        focusTwo.put("when", "讲解后当堂 10 分钟");
        ArrayNode focusTwoRefs = focusTwo.putArray("evidenceRefs");
        if (focus != null) focusTwoRefs.add(focus);
        focusTwo.put("successMetric", "短练完成后能区分基础错误、步骤错误和报告表达错误");

        ArrayNode quickActions = root.putArray("quickActions");
        ObjectNode firstAction = quickActions.addObject();
        firstAction.put("title", "先讲共性薄弱点");
        firstAction.put("target", switch (level) {
            case "EXPERIMENT" -> "本实验低通过率题目和低分学生";
            case "CLASS" -> "班级历次实验中低分与未完成学生";
            default -> "课程中表现偏弱的班级、实验和学生群体";
        });
        firstAction.put("when", "下节课或下一次实验前");
        ArrayNode firstRefs = firstAction.putArray("evidenceRefs");
        if (primary != null) firstRefs.add(primary);
        firstAction.put("successMetric", "下一次同类任务的完成率或平均分较当前关键指标提升至少 5 个百分点");
        ObjectNode secondAction = quickActions.addObject();
        secondAction.put("title", "安排同类短练复测");
        secondAction.put("target", "未完成关键题、低分或表现波动的学生");
        secondAction.put("when", "讲解结束后当堂或课后当天");
        ArrayNode secondRefs = secondAction.putArray("evidenceRefs");
        if (focus != null) secondRefs.add(focus);
        secondAction.put("successMetric", "重点学生能完成同类小题并说明关键步骤");
        ObjectNode thirdAction = quickActions.addObject();
        thirdAction.put("title", "调整下一次实验铺垫");
        thirdAction.put("target", "下一次实验前置说明、示例和检查清单");
        thirdAction.put("when", "下一次实验发布前");
        ArrayNode thirdRefs = thirdAction.putArray("evidenceRefs");
        if (primary != null) thirdRefs.add(primary);
        thirdAction.put("successMetric", "下一次实验中同类错误和未完成情况减少");
        ArrayNode nextClassPlan = root.putArray("nextClassPlan");
        addPlanStep(
                nextClassPlan,
                1,
                "8 分钟",
                "先打开" + problemLabel + "，围绕“" + problemErrorPoint + "”做现场拆解；" + problemStatementInstruction(problemStatement),
                "全班先写出题目输入条件、关键判断步骤和 1 个边界样例，再改代码。",
                "随机抽 2 名学生能说清“" + problemErrorPoint + "”的修正顺序，并指出自己的自查点。",
                firstPlanEvidence,
                problemLabel,
                "全班，尤其是该题未通过或多次尝试学生",
                "纸面/白板上的输入条件、关键判断、边界样例",
                "抽问 + 看关键步骤是否能对应到题目要求",
                "第一步直接处理学生做题结果暴露的最高优先错误点，不再从平均分或泛泛概念讲起。"
        );
        addPlanStep(
                nextClassPlan,
                2,
                "10 分钟",
                "给" + focusTarget + "发 1 道“" + problemKnowledge + "”最小变式题；教师只看最后一次失败代码或提交记录，现场追问一个卡点。",
                "学生只交 3 样：关键判断条件、一次运行/提交截图、1 个自查点。",
                "重点学生能当堂完成最小变式；未完成学生能说清自己属于提交/PTA 同步/题目步骤哪一类问题。",
                focus,
                "同知识点最小变式题",
                focusTarget,
                "关键判断条件 + 提交截图 + 自查点",
                "现场看提交结果和口头解释，不以“听懂了”作为通过标准",
                "讲完马上复测，确认薄弱点是否真正能迁移到同类题。"
        );
        addPlanStep(
                nextClassPlan,
                3,
                "5 分钟",
                "课后只收“" + focusDeliverable + "”；下一次实验前按学生类型验收，未完成先核对提交链路，低分先看最低分题错因，关键题未过先看最后一次代码。",
                "重点学生提交补交记录或“错因—修改—验证”三列表，并标出对应的题目/知识点。",
                "下一次实验前能查到有效补交或同类短练通过记录；没有记录的学生进入单独跟进名单。",
                primary,
                "补交记录 / 错因—修改—验证表",
                focusTarget,
                focusDeliverable,
                "下一次实验前核对提交记录、短练结果和错因表是否三者一致",
                "把教学建议落到可收、可查、可复测的闭环，避免只提醒学生补交。"
        );
        copyPlanToTeachingSteps(nextClassPlan, nextTeachingSteps);

        ObjectNode differentiated = root.putObject("differentiatedTeaching");
        differentiated.put("support", "重点帮扶层先不要加新任务，教师用最小样例检查环境、基础语法和关键步骤，要求下一次实验前完成一次同类短练复测。");
        differentiated.put("improve", "中等提升层重点补报告分析和代码自查，要求说明实验结果、错误原因和修正依据之间的对应关系。");
        differentiated.put("extend", "拓展提升层安排优化代码结构、补充异常样例或解释实验原理的任务，让优秀学生参与共性错误复盘。");
        ArrayNode actions = root.putArray("actions");
        ObjectNode action = actions.addObject();
        action.put("priority", 1);
        action.put("action", "根据低完成率、低得分和重点学生快照安排一次针对性讲解，并在下一次实验中复测。");
        action.put("target", "当前分析范围内的薄弱知识点、风险学生和需要提升的学生层次");
        ArrayNode actionRefs = action.putArray("evidenceRefs");
        if (primary != null) actionRefs.add(primary);
        action.put("successMetric", "下一次同类实验完成率或平均分较当前指标提升至少 5 个百分点");
        ArrayNode focusStudents = root.putArray("focusStudents");
        List<?> students = metrics.get("focusStudents") instanceof List<?> list ? list : List.of();
        for (Object item : students.stream().limit(5).toList()) {
            if (!(item instanceof Map<?, ?> student)) continue;
            ObjectNode studentNode = focusStudents.addObject();
            studentNode.put("studentNo", mapText(student, "studentNo", ""));
            studentNode.put("studentName", mapText(student, "studentName", ""));
            String reason = mapText(student, "reason", "需要结合实验表现进一步观察");
            studentNode.put("problem", mapText(student, "problem", reason));
            studentNode.put("cause", mapText(student, "cause", "需要教师结合提交记录、得分和错题状态做一次短定位。"));
            studentNode.put("teacherAction", mapText(student, "teacherAction", mapText(student, "suggestionHint", "安排一次短周期跟进，确认基础知识、实验环境和报告分析问题")));
            studentNode.put("followUpTime", "下一次实验前");
            studentNode.put("validation", mapText(student, "validation", "让学生独立完成一个同类最小任务，并口头说明关键步骤和自查点"));
            studentNode.put("followUpType", mapText(student, "followUpType", ""));
            studentNode.put("riskLevel", mapText(student, "riskLevel", "LOW"));
            studentNode.put("riskScore", toInt(student.get("riskScore")));
            studentNode.put("followUpPriority", mapText(student, "followUpPriority", "P3"));
            studentNode.put("studentPortraitRiskLabel", mapText(student, "studentPortraitRiskLabel", ""));
            studentNode.put("studentPortraitRiskLevel", mapText(student, "studentPortraitRiskLevel", ""));
            studentNode.put("studentPortraitSummary", mapText(student, "studentPortraitSummary", ""));
            studentNode.put("abilityTrend", mapText(student, "abilityTrend", ""));
            studentNode.put("abilityTrendLabel", mapText(student, "abilityTrendLabel", ""));
            studentNode.put("recentAverageScore", toDouble(student.get("recentAverageScore")));
            studentNode.put("completionRate", toDouble(student.get("completionRate")));
            studentNode.put("averageScore", toDouble(student.get("averageScore")));
            studentNode.put("lowestScore", toDouble(student.get("lowestScore")));
            studentNode.put("failedProblemCount", toInt(student.get("failedProblemCount")));
            studentNode.put("averageAttempts", toDouble(student.get("averageAttempts")));
            ArrayNode riskReasons = studentNode.putArray("riskReasons");
            Object rawReasons = student.get("riskReasons");
            if (rawReasons instanceof List<?> reasons) {
                reasons.forEach(reasonItem -> riskReasons.add(String.valueOf(reasonItem)));
            }
            studentNode.put("reason", reason);
            studentNode.put("suggestion", studentNode.path("teacherAction").asText());
            ArrayNode studentRefs = studentNode.putArray("evidenceRefs");
            if (focus != null) studentRefs.add(focus);
        }
        root.put("experimentAdjustment", "下一次发布同类实验前，把高频错误步骤拆成示例、短练、检查清单，不再只给完整任务说明。 ");
        root.put("termAdjustment", "本学期后续每次实验后保留一次 5-10 分钟复测，把未完成和持续低分学生从提醒补交改为短周期验证。 ");
        root.put("courseAdjustment", "课程整体应把基础步骤、调试方法和报告分析要求前移，并用同类复测确认薄弱点是否真正改善。 ");
        root.put("evidenceSummary", ids.isEmpty() ? "当前缺少可引用证据，结论只能作为低置信度兜底建议。" : "判断依据来自证据 " + String.join("、", ids) + "；主报告不展开原始数据，只保留证据编号供核对。");
        ArrayNode limitations = root.putArray("limitations");
        if (ids.isEmpty()) limitations.add("当前范围没有形成可引用的有效指标");
        limitations.add("本地环境使用 mock 模型，正式环境会基于同一数据快照生成更细化的 Markdown 教学建议报告");
        root.put("markdown", fallbackMarkdown(level, ids, metrics));
        return root;
    }

    private ObjectNode parseAndValidateAdvice(String raw, Set<String> allowedEvidenceIds) {
        try {
            String normalized = raw == null ? "" : raw.trim();
            if (normalized.startsWith("```")) {
                int firstLine = normalized.indexOf('\n');
                int lastFence = normalized.lastIndexOf("```");
                if (firstLine > 0 && lastFence > firstLine) normalized = normalized.substring(firstLine + 1, lastFence).trim();
            }
            int start = normalized.indexOf('{');
            int end = normalized.lastIndexOf('}');
            if (start < 0 || end <= start) throw new IllegalArgumentException("AI output is not JSON");
            JsonNode parsed = objectMapper.readTree(normalized.substring(start, end + 1));
            if (!(parsed instanceof ObjectNode root) || !root.hasNonNull("summary")) {
                throw new IllegalArgumentException("AI output does not match teaching advice schema");
            }
            ensureArray(root, "risks");
            ensureArray(root, "actions");
            ensureArray(root, "quickActions");
            ensureArray(root, "teacherFocus");
            ensureArray(root, "nextClassPlan");
            ensureArray(root, "focusStudents");
            ensureArray(root, "limitations");
            ensureV3Fields(root);
            ensureNextTeachingPlan(root);
            if (!root.hasNonNull("markdown") || root.path("markdown").asText().isBlank()) {
                root.put("markdown", synthesizeMarkdown(root));
            }
            validateReferences(root.path("teachingConclusion"), allowedEvidenceIds);
            validateReferences(root.path("nextTeachingPlan").path("steps"), allowedEvidenceIds);
            validateReferences(root.path("teacherFocus"), allowedEvidenceIds);
            validateReferences(root.path("nextClassPlan"), allowedEvidenceIds);
            validateReferences(root.path("risks"), allowedEvidenceIds);
            validateReferences(root.path("actions"), allowedEvidenceIds);
            validateReferences(root.path("quickActions"), allowedEvidenceIds);
            validateReferences(root.path("focusStudents"), allowedEvidenceIds);
            return root;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("AI output is not valid JSON", e);
        }
    }

    private void ensureArray(ObjectNode root, String field) {
        if (!root.path(field).isArray()) root.set(field, objectMapper.createArrayNode());
    }

    private Map<String, Object> primaryProblemForPlan(Map<String, Object> diagnosis, Map<String, Object> metrics) {
        for (String field : List.of("problemErrorPoints", "priorityProblems", "weakProblemSignals")) {
            Object raw = diagnosis.get(field);
            if (raw instanceof List<?> rows) {
                for (Object row : rows) {
                    if (row instanceof Map<?, ?> map) return castGenericMap(map);
                }
            }
        }
        Object context = metrics.get("teachingContext");
        if (context instanceof Map<?, ?> contextMap) {
            Object raw = contextMap.get("priorityProblems");
            if (raw instanceof List<?> rows) {
                for (Object row : rows) {
                    if (row instanceof Map<?, ?> map) return castGenericMap(map);
                }
            }
        }
        Object rawPerformance = metrics.get("problemPerformance");
        if (rawPerformance instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) return castGenericMap(map);
            }
        }
        return Map.of();
    }

    private String problemLabel(Map<String, Object> problem) {
        String title = mapText(problem, "title", "");
        String problemNo = mapText(problem, "problemNo", "");
        if (!problemNo.isBlank() && !title.isBlank()) return "第 " + problemNo + " 题《" + title + "》";
        if (!title.isBlank()) return "题目《" + title + "》";
        if (!problemNo.isBlank()) return "第 " + problemNo + " 题";
        return "当前最低通过率题目";
    }

    private String problemKnowledge(Map<String, Object> problem, String fallbackKnowledge) {
        String knowledge = mapText(problem, "inferredKnowledge", "");
        if (knowledge.isBlank()) knowledge = mapText(problem, "knowledge", "");
        if (knowledge.isBlank()) knowledge = mapText(problem, "knowledgePath", "");
        if (knowledge.isBlank()) knowledge = fallbackKnowledge;
        if (knowledge == null || knowledge.isBlank()) return "同一薄弱知识点";
        return knowledge;
    }

    private String problemErrorPoint(Map<String, Object> problem) {
        String errorPoint = mapText(problem, "errorPoint", "");
        if (!errorPoint.isBlank()) return errorPoint;
        String diagnosis = mapText(problem, "diagnosis", "");
        if (!diagnosis.isBlank()) return diagnosis;
        String status = mapText(problem, "dominantStatus", "");
        if (!status.isBlank()) return "主要错误状态 " + status;
        return "题意转代码、边界样例或调试顺序不稳定";
    }

    private String problemStatement(Map<String, Object> problem) {
        String summary = mapText(problem, "problemStatementSummary", "");
        if (!summary.isBlank()) return summary;
        String content = mapText(problem, "content", "");
        if (!content.isBlank()) return summarizeMarkdown(content, 70);
        return "";
    }

    private String problemStatementInstruction(String statement) {
        if (statement == null || statement.isBlank()) {
            return "先把题意拆成“输入条件—处理步骤—输出要求”，再演示错误定位。";
        }
        return "先带学生圈出题干要点“" + limit(statement, 54) + "”，再拆“输入条件—处理步骤—输出要求”。";
    }

    private String focusTargetForPlan(String level, Map<String, Object> metrics) {
        List<String> namedStudents = new ArrayList<>();
        Object rawStudents = metrics.get("focusStudents");
        if (rawStudents instanceof List<?> rows) {
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> map)) continue;
                String name = mapText(map, "studentName", "");
                String no = mapText(map, "studentNo", "");
                if (!name.isBlank() && !no.isBlank()) namedStudents.add(name + "(" + no + ")");
                else if (!name.isBlank()) namedStudents.add(name);
                else if (!no.isBlank()) namedStudents.add(no);
                if (namedStudents.size() >= 3) break;
            }
            if (!namedStudents.isEmpty()) {
                int total = rows.size();
                return String.join("、", namedStudents) + (total > namedStudents.size() ? " 等 " + total + " 名重点学生" : " 这类重点学生");
            }
        }
        Map<String, Object> distribution = metrics.get("scoreDistribution") instanceof Map<?, ?> map ? castGenericMap(map) : Map.of();
        int incomplete = toInt(distribution.get("incomplete"));
        int highRisk = toInt(distribution.get("highRisk"));
        int risk = toInt(distribution.get("risk"));
        if (incomplete > 0 || highRisk > 0) return "未完成学生 " + incomplete + " 人、低分高风险学生 " + highRisk + " 人";
        if (risk > 0) return "60-69 分风险学生 " + risk + " 人";
        return switch (level) {
            case "EXPERIMENT" -> "本实验该题未通过、低分或多次尝试学生";
            case "CLASS" -> "班级未完成、低分和阶段性波动学生";
            default -> "课程中薄弱班级和持续风险学生";
        };
    }

    private String focusDeliverableForPlan(Map<String, Object> metrics) {
        Object rawStudents = metrics.get("focusStudents");
        if (rawStudents instanceof List<?> rows) {
            boolean hasIncomplete = false;
            boolean hasProblemNotPassed = false;
            boolean hasLowScore = false;
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> map)) continue;
                String type = mapText(map, "followUpType", "");
                String reason = mapText(map, "reason", "");
                hasIncomplete = hasIncomplete || "INCOMPLETE".equals(type) || reason.contains("未完成") || reason.contains("未提交");
                hasProblemNotPassed = hasProblemNotPassed || "PROBLEM_NOT_PASSED".equals(type);
                hasLowScore = hasLowScore || "LOW_SCORE".equals(type) || reason.contains("低分");
            }
            List<String> deliverables = new ArrayList<>();
            if (hasIncomplete) deliverables.add("有效补交截图");
            if (hasProblemNotPassed) deliverables.add("最后一次失败代码的错因说明");
            if (hasLowScore) deliverables.add("错因—修改—验证三列表");
            if (!deliverables.isEmpty()) return String.join(" + ", deliverables);
        }
        return "补交截图 + 错因—修改—验证三列表";
    }

    private String firstEvidenceRef(Map<String, Object> item, String fallback) {
        List<String> refs = normalizedEvidenceRefs(item);
        return refs.isEmpty() ? fallback : refs.get(0);
    }

    private void addPlanStep(
            ArrayNode target,
            int step,
            String duration,
            String teacherAction,
            String studentTask,
            String expectedChange,
            String evidenceRef,
            String material,
            String targetStudents,
            String deliverable,
            String checkMethod,
            String reason
    ) {
        ObjectNode node = target.addObject();
        node.put("step", step);
        node.put("duration", duration);
        node.put("teacherAction", teacherAction);
        node.put("studentTask", studentTask);
        node.put("expectedChange", expectedChange);
        node.put("material", material);
        node.put("targetStudents", targetStudents);
        node.put("deliverable", deliverable);
        node.put("checkMethod", checkMethod);
        node.put("reason", reason);
        ArrayNode refs = node.putArray("evidenceRefs");
        if (evidenceRef != null) refs.add(evidenceRef);
    }

    private void copyPlanToTeachingSteps(ArrayNode source, ArrayNode target) {
        for (JsonNode item : source) {
            ObjectNode step = target.addObject();
            step.put("title", switch (item.path("step").asInt()) {
                case 1 -> "课堂先讲什么";
                case 2 -> "马上练什么";
                default -> "怎么判断有效";
            });
            step.put("duration", item.path("duration").asText(""));
            step.put("teacherAction", item.path("teacherAction").asText(""));
            step.put("reason", item.path("reason").asText("该步骤来自同一数据快照的下一节课安排。"));
            step.put("howToTeach", item.path("teacherAction").asText(""));
            step.put("studentTask", item.path("studentTask").asText(""));
            step.put("successMetric", item.path("expectedChange").asText(""));
            if (item.hasNonNull("material")) step.put("material", item.path("material").asText(""));
            if (item.hasNonNull("targetStudents")) step.put("targetStudents", item.path("targetStudents").asText(""));
            if (item.hasNonNull("deliverable")) step.put("deliverable", item.path("deliverable").asText(""));
            if (item.hasNonNull("checkMethod")) step.put("checkMethod", item.path("checkMethod").asText(""));
            step.set("evidenceRefs", item.path("evidenceRefs").isArray() ? item.path("evidenceRefs").deepCopy() : objectMapper.createArrayNode());
        }
    }

    private void ensureV3Fields(ObjectNode root) {
        if (!root.path("teachingConclusion").isObject()) {
            ObjectNode conclusion = root.putObject("teachingConclusion");
            conclusion.put("problem", root.path("summary").asText("需要形成更明确的教学结论"));
            conclusion.put("cause", "AI 未返回独立原因字段，建议教师结合证据编号核对。 ");
            conclusion.put("impact", "如果不及时处理，后续同类实验可能继续出现相同薄弱点。 ");
            conclusion.put("priority", firstRiskLevel(root));
            copyFirstRefs(root, conclusion, "risks");
        }
        if (!root.path("differentiatedTeaching").isObject()) {
            ObjectNode differentiated = root.putObject("differentiatedTeaching");
            differentiated.put("support", "重点帮扶层先做最小样例复测，教师确认基础步骤和提交问题。 ");
            differentiated.put("improve", "中等提升层用检查清单补报告分析和代码自查。 ");
            differentiated.put("extend", "拓展提升层承担异常样例、结构优化或原理解释任务。 ");
        }
        if (!root.hasNonNull("experimentAdjustment")) root.put("experimentAdjustment", "把本实验薄弱步骤拆成示例、短练、复盘三段处理。");
        if (!root.hasNonNull("termAdjustment")) root.put("termAdjustment", "本学期后续用短练复测跟踪薄弱学生是否真正改善。");
        if (!root.hasNonNull("courseAdjustment")) root.put("courseAdjustment", "课程整体前移基础步骤、调试方法和报告分析要求。");
        if (!root.hasNonNull("evidenceSummary")) root.put("evidenceSummary", "依据证据编号形成教学判断，原始数据保留在折叠区核对。 ");
        if (root.path("teacherFocus").isArray() && root.path("teacherFocus").isEmpty()) {
            for (JsonNode item : root.path("quickActions")) {
                ObjectNode focus = root.withArray("teacherFocus").addObject();
                focus.put("title", item.path("title").asText(item.path("action").asText("教师重点动作")));
                focus.put("instruction", item.path("action").asText(item.path("title").asText("执行针对性讲解和短练复测")));
                focus.put("target", item.path("target").asText("当前分析对象"));
                focus.put("when", item.path("when").asText("下节课"));
                focus.put("successMetric", item.path("successMetric").asText("下一次同类任务表现改善"));
                focus.set("evidenceRefs", item.path("evidenceRefs").isArray() ? item.path("evidenceRefs").deepCopy() : objectMapper.createArrayNode());
            }
        }
        if (root.path("nextClassPlan").isArray() && root.path("nextClassPlan").isEmpty()) {
            int index = 1;
            for (JsonNode item : root.path("quickActions")) {
                ObjectNode plan = root.withArray("nextClassPlan").addObject();
                plan.put("step", index++);
                plan.put("duration", item.path("when").asText("10 分钟"));
                plan.put("teacherAction", item.path("title").asText(item.path("action").asText("执行针对性教学动作")));
                plan.put("studentTask", item.path("target").asText("完成同类短练"));
                plan.put("expectedChange", item.path("successMetric").asText("学生能独立完成关键步骤"));
                plan.set("evidenceRefs", item.path("evidenceRefs").isArray() ? item.path("evidenceRefs").deepCopy() : objectMapper.createArrayNode());
            }
        }
    }

    private void ensureNextTeachingPlan(ObjectNode root) {
        if (!root.path("nextTeachingPlan").isObject()) {
            ObjectNode plan = root.putObject("nextTeachingPlan");
            plan.put("summary", root.path("summary").asText(""));
            plan.put("priority", root.path("teachingConclusion").path("priority").asText("MEDIUM"));
            plan.putArray("steps");
        }
        ObjectNode plan = (ObjectNode) root.path("nextTeachingPlan");
        if (!plan.hasNonNull("summary")) plan.put("summary", root.path("summary").asText(""));
        if (!plan.hasNonNull("priority")) plan.put("priority", root.path("teachingConclusion").path("priority").asText("MEDIUM"));
        if (!plan.path("steps").isArray()) plan.putArray("steps");
        ArrayNode steps = (ArrayNode) plan.path("steps");
        if (steps.isEmpty() && root.path("nextClassPlan").isArray() && root.path("nextClassPlan").size() > 0) {
            copyPlanToTeachingSteps((ArrayNode) root.path("nextClassPlan"), steps);
        }
        for (JsonNode item : steps) {
            if (!(item instanceof ObjectNode step)) continue;
            if (!step.hasNonNull("title")) step.put("title", "下一步教学动作");
            if (!step.hasNonNull("teacherAction")) step.put("teacherAction", step.path("howToTeach").asText(step.path("title").asText("")));
            if (!step.hasNonNull("reason")) step.put("reason", "AI 未单独返回原因字段，请结合证据编号核对。");
            if (!step.hasNonNull("howToTeach")) step.put("howToTeach", step.path("teacherAction").asText(""));
            if (!step.hasNonNull("studentTask")) step.put("studentTask", "AI 未单独返回学生任务字段，请查看完整建议原文。");
            if (!step.hasNonNull("successMetric")) step.put("successMetric", "AI 未单独返回验收指标，请查看完整建议原文。");
            if (!step.path("evidenceRefs").isArray()) step.putArray("evidenceRefs");
        }
    }

    private String firstRiskLevel(ObjectNode root) {
        JsonNode risks = root.path("risks");
        return risks.isArray() && risks.size() > 0 ? risks.get(0).path("level").asText("MEDIUM") : "MEDIUM";
    }

    private void copyFirstRefs(ObjectNode source, ObjectNode target, String arrayField) {
        ArrayNode refs = target.putArray("evidenceRefs");
        JsonNode rows = source.path(arrayField);
        if (rows.isArray() && rows.size() > 0 && rows.get(0).path("evidenceRefs").isArray()) {
            rows.get(0).path("evidenceRefs").forEach(refs::add);
        }
    }

    private String fallbackMarkdown(String level, List<String> ids, Map<String, Object> metrics) {
        String evidence = ids.isEmpty() ? "暂无可引用证据" : String.join("、", ids);
        List<?> focusStudents = metrics.get("focusStudents") instanceof List<?> list ? list : List.of();
        Map<String, Object> diagnosis = metrics.get("learningDiagnosis") instanceof Map<?, ?> map ? castGenericMap(map) : Map.of();
        String diagnosisConclusion = mapText(diagnosis, "conclusion", "");
        String diagnosisAction = mapText(diagnosis, "nextTeachingAction", "");
        String inferredKnowledge = firstDiagnosisText(diagnosis, "inferredKnowledgeSignals", "knowledge");
        String topError = firstDiagnosisText(diagnosis, "errorTypeSignals", "status");
        Map<String, Object> primaryProblem = primaryProblemForPlan(diagnosis, metrics);
        String problemLabel = problemLabel(primaryProblem);
        String problemKnowledge = problemKnowledge(primaryProblem, inferredKnowledge);
        String problemErrorPoint = problemErrorPoint(primaryProblem);
        String problemStatement = problemStatement(primaryProblem);
        String focusTarget = focusTargetForPlan(level, metrics);
        String focusDeliverable = focusDeliverableForPlan(metrics);
        StringBuilder markdown = new StringBuilder();
        markdown.append("## 核心教学结论\n\n");
        markdown.append(!diagnosisConclusion.isBlank() ? diagnosisConclusion : switch (level) {
            case "EXPERIMENT" -> "下节课不要再重复展示本实验统计表，先围绕低通过率环节做一次短讲，再安排同类短练复测。";
            case "CLASS" -> "下一阶段不要从班级平均分讲起，先补反复薄弱的实验步骤和报告分析方法，再跟进未完成或持续低分学生。";
            default -> "课程后续不要只比较班级数据，先调整高频薄弱实验的前置铺垫和难度梯度，再用复测验证改动效果。";
        }).append("\n\n");
        if (!inferredKnowledge.isBlank()) {
            markdown.append("> 推断知识点：").append(inferredKnowledge)
                    .append("。该结论来自题目/实验名称、通过情况和错误状态，需要结合原题核对。\n\n");
        }
        markdown.append("## 下一节课怎么教\n\n");
        markdown.append("1. ").append(!diagnosisAction.isBlank()
                ? diagnosisAction
                : "用 8-10 分钟打开" + problemLabel + "，围绕“" + problemErrorPoint + "”拆题干、画步骤、讲修正顺序；" + problemStatementInstruction(problemStatement)).append("\n");
        markdown.append("2. 讲完立刻给").append(focusTarget)
                .append("做 1 道“").append(problemKnowledge)
                .append("”最小变式题，只收关键判断条件、一次提交截图和 1 个自查点。\n");
        markdown.append("3. 课后只收“").append(focusDeliverable)
                .append("”，下一次实验前核对提交记录、短练结果和错因表是否能互相对应。");
        if (!topError.isBlank()) markdown.append(" 高频错误类型优先核对：").append(topError).append("。");
        markdown.append("\n\n");
        markdown.append("## 分层教学安排\n\n");
        markdown.append("- 重点帮扶层：不要只提醒补交，先检查实验环境、基础语法和关键步骤理解，必要时一对一过一遍最小样例。\n");
        markdown.append("- 中等提升层：给他们报告分析和代码自查清单，要求说明实验结果与结论之间的对应关系。\n");
        markdown.append("- 拓展提升层：安排优化代码结构、补充异常样例或解释实验原理的拓展任务，避免只停留在完成层面。\n\n");
        if (!focusStudents.isEmpty()) {
            markdown.append("## 重点学生跟进\n\n");
            for (Object item : focusStudents.stream().limit(5).toList()) {
                if (!(item instanceof Map<?, ?> student)) continue;
                markdown.append("- ").append(mapText(student, "studentNo", "未知学号"))
                        .append("：").append(mapText(student, "reason", "需要进一步观察"))
                        .append("。建议：").append(mapText(student, "suggestionHint", "安排短周期跟进")).append("。\n");
            }
            markdown.append("\n");
        }
        markdown.append("## 实验/学期/课程调整\n\n");
        markdown.append("- 把高频出错步骤拆成“教师示范、学生短练、结果复盘”三个环节。\n");
        markdown.append("- 下一次实验发布前加入前置检查清单，先确认环境、输入输出样例和关键概念。\n");
        markdown.append("- 如果同类问题连续出现，应调整实验顺序或增加一次基础诊断练习。\n\n");
        markdown.append("## 依据与局限\n\n");
        markdown.append("- 依据：").append(evidence).append("。原始指标只建议折叠核对，不应作为主内容展开。\n");
        markdown.append("- 局限：当前为本地兜底建议，正式 AI 模型会基于同一数据快照生成更细化表述。\n");
        return markdown.toString();
    }

    private String synthesizeMarkdown(ObjectNode root) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("## 核心教学结论\n\n");
        markdown.append(root.path("summary").asText("已生成教学建议。")).append("\n\n");
        JsonNode conclusion = root.path("teachingConclusion");
        if (conclusion.isObject()) {
            markdown.append("- 教学问题：").append(conclusion.path("problem").asText("需要进一步判断核心问题")).append("\n");
            markdown.append("- 可能原因：").append(conclusion.path("cause").asText("需要结合证据核对原因")).append("\n");
            markdown.append("- 教学影响：").append(conclusion.path("impact").asText("可能影响后续同类实验表现")).append("\n\n");
        }
        if (root.path("quickActions").isArray() && root.path("quickActions").size() > 0) {
            markdown.append("## 下一节课怎么教\n\n");
            int index = 1;
            for (JsonNode item : root.path("quickActions")) {
                markdown.append(index++).append(". ")
                        .append(item.path("title").asText(item.path("action").asText("执行一项针对性教学动作")))
                        .append("：").append(item.path("target").asText("当前分析对象"));
                if (item.hasNonNull("when")) markdown.append("，时间：").append(item.path("when").asText());
                markdown.append("。\n");
            }
            markdown.append("\n");
        } else if (root.path("actions").isArray() && root.path("actions").size() > 0) {
            markdown.append("## 下一节课怎么教\n\n");
            int index = 1;
            for (JsonNode item : root.path("actions")) {
                markdown.append(index++).append(". ")
                        .append(item.path("action").asText("执行一项针对性教学动作"))
                        .append("。验证方式：").append(item.path("successMetric").asText("观察下一次同类任务表现")).append("。\n");
            }
            markdown.append("\n");
        }
        JsonNode differentiated = root.path("differentiatedTeaching");
        if (differentiated.isObject()) {
            markdown.append("## 分层教学安排\n\n");
            markdown.append("- 重点帮扶层：").append(differentiated.path("support").asText("安排最小样例短练复测")).append("\n");
            markdown.append("- 中等提升层：").append(differentiated.path("improve").asText("补报告分析和代码自查")).append("\n");
            markdown.append("- 拓展提升层：").append(differentiated.path("extend").asText("安排拓展任务和共性复盘")).append("\n\n");
        }
        if (root.path("focusStudents").isArray() && root.path("focusStudents").size() > 0) {
            markdown.append("## 重点学生跟进\n\n");
            for (JsonNode item : root.path("focusStudents")) {
                markdown.append("- ").append(item.path("studentNo").asText("未知学号"))
                        .append("：").append(item.path("problem").asText(item.path("reason").asText("需要进一步观察")))
                        .append("。教师动作：").append(item.path("teacherAction").asText(item.path("suggestion").asText("安排短周期跟进")))
                        .append("。验证：").append(item.path("validation").asText("完成同类短练")).append("。\n");
            }
            markdown.append("\n");
        }
        markdown.append("## 实验/学期/课程调整\n\n");
        markdown.append("- 本实验：").append(root.path("experimentAdjustment").asText("把薄弱步骤拆成示例、短练、复盘。 ")).append("\n");
        markdown.append("- 本学期：").append(root.path("termAdjustment").asText("用短练复测跟踪薄弱学生是否改善。 ")).append("\n");
        markdown.append("- 课程整体：").append(root.path("courseAdjustment").asText("前移基础步骤、调试方法和报告分析要求。 ")).append("\n\n");
        markdown.append("## 依据与局限\n\n");
        markdown.append("- 依据：").append(root.path("evidenceSummary").asText("依据证据编号形成教学判断。 ")).append("\n");
        if (root.path("limitations").isArray() && root.path("limitations").size() > 0) {
            for (JsonNode item : root.path("limitations")) {
                markdown.append("- ").append(item.asText()).append("\n");
            }
        }
        return markdown.toString();
    }

    private void validateReferences(JsonNode items, Set<String> allowedEvidenceIds) {
        if (items.isObject()) {
            validateReferenceNode(items, allowedEvidenceIds);
            return;
        }
        for (JsonNode item : items) {
            validateReferenceNode(item, allowedEvidenceIds);
        }
    }

    private void validateReferenceNode(JsonNode item, Set<String> allowedEvidenceIds) {
        JsonNode refs = item.path("evidenceRefs");
        if (!refs.isArray()) throw new IllegalArgumentException("AI advice item is missing evidenceRefs");
        for (JsonNode ref : refs) {
            if (!allowedEvidenceIds.contains(ref.asText())) {
                throw new IllegalArgumentException("AI advice references unknown evidence: " + ref.asText());
            }
        }
    }

    private Map<String, Object> reportMap(TeachingAdviceReportEntity report) {
        return mapOf(
                "id", report.getId(), "scopeLevel", report.getScopeLevel(),
                "scope", readJson(report.getScopeJson()), "metrics", readJson(report.getMetricsJson()),
                "advice", readJson(report.getAdviceJson()), "promptVersion", report.getPromptVersion(),
                "model", report.getModel(), "status", report.getStatus(), "errorMessage", report.getErrorMessage(),
                "createdAt", report.getCreatedAt()
        );
    }

    private String courseTermPredicate(ScopeAnchor anchor, boolean includeHistory) {
        String course = anchor.courseId() != null
                ? "tc.course_id = :courseId"
                : "CAST(LOWER(TRIM(COALESCE(c.name, tc.course_name, ''))) AS BINARY) " +
                "= CAST(LOWER(:courseName) AS BINARY)";
        if (!includeHistory && anchor.termId() != null) return course + " AND tc.term_id = :termId";
        return course;
    }

    private Map<String, Object> courseTermParams(ScopeAnchor anchor, boolean includeHistory) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (anchor.courseId() != null) params.put("courseId", anchor.courseId());
        else params.put("courseName", anchor.courseName());
        if (!includeHistory && anchor.termId() != null) params.put("termId", anchor.termId());
        return params;
    }

    private String resolveCourseName(Long courseId) {
        if (courseId == null) return null;
        List<?> result = entityManager.createNativeQuery("SELECT name FROM course WHERE id = :id")
                .setParameter("id", courseId).getResultList();
        return result.isEmpty() ? null : asText(result.get(0));
    }

    private String resolveTermName(Long termId) {
        if (termId == null) return "学期待补充";
        List<?> result = entityManager.createNativeQuery("SELECT name FROM academic_term WHERE id = :id")
                .setParameter("id", termId).getResultList();
        return result.isEmpty() ? "学期待补充" : textOr(asText(result.get(0)), "学期待补充");
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(String sql, Map<String, Object> params) {
        Query query = entityManager.createNativeQuery(sql);
        params.forEach(query::setParameter);
        return query.getResultList();
    }

    private Set<String> evidenceIds(Map<String, Object> metrics) {
        Set<String> ids = new LinkedHashSet<>();
        Object rawEvidence = metrics.get("evidence");
        if (rawEvidence instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && map.get("evidenceId") != null) ids.add(String.valueOf(map.get("evidenceId")));
            }
        }
        return ids;
    }

    private Map<String, Object> evidence(String id, String label, Map<String, Object> value) {
        return mapOf("evidenceId", id, "label", label, "value", value);
    }

    private Map<String, Object> coverage(List<?> primary, List<?> secondary) {
        return mapOf(
                "primaryRows", primary.size(), "secondaryRows", secondary.size(),
                "status", primary.isEmpty() ? "INSUFFICIENT" : "AVAILABLE"
        );
    }

    private String normalizeLevel(String value) {
        String level = value == null ? "CLASS" : value.trim().toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(level)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid scopeLevel");
        return level;
    }

    private String evidenceId(int index) { return "M%02d".formatted(index); }
    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("failed to serialize teaching advice report", e); }
    }
    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) return null;
        try { return objectMapper.readTree(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("stored teaching advice JSON is invalid", e); }
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new IllegalStateException("expected map value");
    }
    private Map<String, Object> castGenericMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
    @SuppressWarnings("unchecked")
    private String firstDiagnosisText(Map<String, Object> diagnosis, String arrayField, String valueField) {
        Object raw = diagnosis.get(arrayField);
        if (!(raw instanceof List<?> rows) || rows.isEmpty() || !(rows.get(0) instanceof Map<?, ?> first)) return "";
        return mapText((Map<?, ?>) first, valueField, "");
    }
    private static String mapText(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }
    private static String asText(Object value) { return value == null ? null : String.valueOf(value).trim(); }
    private static String textOr(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? (fallback == null || fallback.isBlank() ? "" : fallback.trim()) : preferred.trim();
    }
    private static String limit(String value, int max) {
        if (value == null) return "unknown error";
        return value.length() <= max ? value : value.substring(0, max);
    }
    private static String summarizeMarkdown(String value, int max) {
        String text = asText(value);
        if (text == null || text.isBlank()) return "";
        text = text
                .replaceAll("(?s)```.*?```", " ")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", " ")
                .replaceAll("\\[[^]]+]\\(([^)]+)\\)", " ")
                .replaceAll("[#>*_`|\\-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (text.length() <= max) return text;
        return text.substring(0, max) + "…";
    }
    private static Long toLong(Object value) {
        if (value == null) return null;
        return value instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(value));
    }
    private static int toInt(Object value) {
        if (value == null) return 0;
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }
    private static double toDouble(Object value) {
        if (value == null) return 0;
        return value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value));
    }
    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
    private static String formatMetric(double value) {
        return Math.abs(value - Math.rint(value)) < 0.000001
                ? String.valueOf((long) Math.rint(value))
                : String.valueOf(round1(value));
    }
    private Map<String, Object> distributionMap(List<Object[]> rows) {
        Object[] row = rows.isEmpty() ? new Object[]{0, 0, 0, 0, 0, 0} : rows.get(0);
        return mapOf(
                "total", toInt(row[0]),
                "excellent", toInt(row[1]),
                "middle", toInt(row[2]),
                "risk", toInt(row[3]),
                "highRisk", toInt(row[4]),
                "incomplete", toInt(row[5]),
                "description", "excellent=85分及以上，middle=70-84分，risk=60-69分，highRisk=60分以下，incomplete=未完成或未提交"
        );
    }
    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return result;
    }

    private record ScopeAnchor(
            Long classId,
            Long courseId,
            Long termId,
            String className,
            String courseName,
            String termName,
            Long experimentId,
            Long templateId,
            String experimentName
    ) {}
}
