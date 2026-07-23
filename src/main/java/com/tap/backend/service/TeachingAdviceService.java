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
        return mapOf("classComparison", comparisons, "problemPerformance", problems, "evidence", evidence,
                "dataCoverage", coverage(comparisons, problems));
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

        List<Map<String, Object>> peerClasses = courseClassComparison(teacherId, anchor, false, index, evidence);
        index += peerClasses.size();
        List<Map<String, Object>> history = includeHistory
                ? courseHistory(teacherId, anchor, index, evidence)
                : List.of();
        return mapOf("experiments", experiments, "studentSegments", segments, "peerClassComparison", peerClasses,
                "history", history, "evidence", evidence, "dataCoverage", coverage(experiments, peerClasses));
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
        return mapOf("classComparison", classes, "experimentSummary", experiments, "history", history,
                "evidence", evidence, "dataCoverage", coverage(classes, experiments));
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
        ObjectNode root = objectMapper.createObjectNode();
        root.put("summary", switch (level) {
            case "EXPERIMENT" -> "已根据同一实验的班级横向数据生成调整建议。";
            case "CLASS" -> "已根据班级历次实验与学生分层生成调整建议。";
            default -> "已根据课程多班级与历史数据生成调整建议。";
        });
        ArrayNode risks = root.putArray("risks");
        ObjectNode risk = risks.addObject();
        risk.put("level", "MEDIUM");
        risk.put("title", ids.isEmpty() ? "当前有效数据不足" : "需要优先关注完成率或得分较低的指标");
        ArrayNode riskRefs = risk.putArray("evidenceRefs");
        if (primary != null) riskRefs.add(primary);
        ArrayNode actions = root.putArray("actions");
        ObjectNode action = actions.addObject();
        action.put("priority", 1);
        action.put("action", "根据低完成率和低得分指标安排一次针对性讲解，并在下一次实验中复测。 ");
        action.put("target", "当前分析范围内的薄弱知识点和学生层次");
        ArrayNode actionRefs = action.putArray("evidenceRefs");
        if (primary != null) actionRefs.add(primary);
        action.put("successMetric", "下一次同类实验完成率或平均分较当前指标提升至少 5 个百分点");
        ArrayNode limitations = root.putArray("limitations");
        if (ids.isEmpty()) limitations.add("当前范围没有形成可引用的有效指标");
        limitations.add("本地环境使用 mock 模型，正式环境会基于同一数据快照生成更细化建议");
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
            if (!(parsed instanceof ObjectNode root) || !root.hasNonNull("summary") || !root.path("risks").isArray()
                    || !root.path("actions").isArray() || !root.path("limitations").isArray()) {
                throw new IllegalArgumentException("AI output does not match teaching advice schema");
            }
            validateReferences(root.path("risks"), allowedEvidenceIds);
            validateReferences(root.path("actions"), allowedEvidenceIds);
            return root;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("AI output is not valid JSON", e);
        }
    }

    private void validateReferences(JsonNode items, Set<String> allowedEvidenceIds) {
        for (JsonNode item : items) {
            JsonNode refs = item.path("evidenceRefs");
            if (!refs.isArray()) throw new IllegalArgumentException("AI advice item is missing evidenceRefs");
            for (JsonNode ref : refs) {
                if (!allowedEvidenceIds.contains(ref.asText())) {
                    throw new IllegalArgumentException("AI advice references unknown evidence: " + ref.asText());
                }
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
    private static String asText(Object value) { return value == null ? null : String.valueOf(value).trim(); }
    private static String textOr(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? (fallback == null || fallback.isBlank() ? "" : fallback.trim()) : preferred.trim();
    }
    private static String limit(String value, int max) {
        if (value == null) return "unknown error";
        return value.length() <= max ? value : value.substring(0, max);
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
