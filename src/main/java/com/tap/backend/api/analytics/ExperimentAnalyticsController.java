package com.tap.backend.api.analytics;

import com.tap.backend.academic.entity.teacher.Teacher;
import com.tap.backend.academic.security.TeacherSessionResolver;
import com.tap.common.api.ApiResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Teacher experiment analytics backed by unified assignment tables.
 *
 * Scope rules:
 * - only experiments owned by the current teacher
 * - when supplied, restrict results to the current teaching class/course
 * - never widen a scoped request to experiments from another course
 */
@RestController
@RequestMapping("/api/analytics")
public class ExperimentAnalyticsController {

    private static final String DATA_STRUCTURE_KEYWORD = "\u6570\u636e\u7ed3\u6784";
    private static final String C_LANGUAGE_KEYWORD = "C\u8bed\u8a00";
    private static final List<List<String>> COURSE_SUBJECT_ALIASES = List.of(
            List.of(DATA_STRUCTURE_KEYWORD),
            List.of(C_LANGUAGE_KEYWORD, "C\u7a0b\u5e8f\u8bbe\u8ba1"),
            List.of("Java"),
            List.of("Python"),
            List.of("\u8ba1\u7b97\u673a\u7f51\u7edc"),
            List.of("\u64cd\u4f5c\u7cfb\u7edf"),
            List.of("\u6570\u636e\u5e93"),
            List.of("\u8f6f\u4ef6\u5de5\u7a0b"),
            List.of("\u8ba1\u7b97\u673a\u7ec4\u6210")
    );

    private static final String COLL = " COLLATE utf8mb4_0900_ai_ci";
    private static final String EMPTY_STR = "_utf8mb4'' COLLATE utf8mb4_0900_ai_ci";
    private static final String EXPERIMENT_NAME_EXPR =
            "COALESCE(NULLIF(TRIM(ao.title_override), " + EMPTY_STR + "), at.title)";
    private static final String COURSE_NAME_EXPR =
            "COALESCE(NULLIF(TRIM(c.name), " + EMPTY_STR + "), NULLIF(TRIM(tc.course_name), " + EMPTY_STR + "), '')";
    private static final String PREFIX_SOURCE_EXPR =
            "COALESCE(NULLIF(TRIM(tc.pta_keyword), " + EMPTY_STR + "), " + EXPERIMENT_NAME_EXPR + ", tc.name)";
    private static final String DATA_STRUCTURE_SQL_LITERAL =
            "_utf8mb4'" + DATA_STRUCTURE_KEYWORD + "' COLLATE utf8mb4_0900_ai_ci";
    private static final String DATA_STRUCTURE_LIKE_PATTERN =
            "CONCAT('%', " + DATA_STRUCTURE_SQL_LITERAL + ", '%')";

    private static final String EXPERIMENT_NAME_EXPR_COLL = EXPERIMENT_NAME_EXPR + COLL;
    private static final String PREFIX_SOURCE_EXPR_COLL = PREFIX_SOURCE_EXPR + COLL;

    private static final String CLASS_PREFIX_EXPR =
            "TRIM(CASE " +
                    "WHEN " + PREFIX_SOURCE_EXPR_COLL + " LIKE " + DATA_STRUCTURE_LIKE_PATTERN + " " +
                    "THEN SUBSTRING_INDEX(" + PREFIX_SOURCE_EXPR_COLL + ", " + DATA_STRUCTURE_SQL_LITERAL + ", 1) " +
                    "ELSE tc.name COLLATE utf8mb4_0900_ai_ci " +
                    "END COLLATE utf8mb4_0900_ai_ci)";

    private static final String SUBMISSION_ACTIVITY_PREDICATE =
            "LOWER(COALESCE(sa.submission_status, '')) IN ('graded', 'submitted') " +
                    "OR COALESCE(sa.completion_evidence, 'NONE') IN ('TRANSCRIPT_SCORE', 'ANSWER_SHEET', 'SCORED_CODE')";
    private static final String SCORED_ASSIGNMENT_PREDICATE =
            "LOWER(COALESCE(sa.submission_status, '')) = 'graded'";

    @PersistenceContext
    private EntityManager em;

    private final TeacherSessionResolver teacherSessionResolver;

    public ExperimentAnalyticsController(TeacherSessionResolver teacherSessionResolver) {
        this.teacherSessionResolver = teacherSessionResolver;
    }

    @GetMapping("/experiments")
    public ApiResponse<List<Map<String, Object>>> listExperiments(
            @RequestParam(required = false) String classPrefix,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) String courseName,
            HttpServletRequest request) {
        Integer teacherId = requireCurrentTeacherId(request);

        StringBuilder sql = new StringBuilder()
                .append("SELECT ")
                .append("  CAST(ao.id AS SIGNED) AS experimentId, ")
                .append("  ").append(EXPERIMENT_NAME_EXPR).append(" AS name, ")
                .append("  ").append(CLASS_PREFIX_EXPR).append(" AS classPrefix, ")
                .append("  CAST(ao.class_id AS SIGNED) AS classId, ")
                .append("  tc.name AS className, ")
                .append("  CAST(tc.course_id AS SIGNED) AS courseId, ")
                .append("  NULLIF(TRIM(").append(COURSE_NAME_EXPR).append("), ").append(EMPTY_STR).append(") AS courseName, ")
                .append("  COUNT(DISTINCT sa.id) AS rosterCount, ")
                .append("  COUNT(DISTINCT CASE WHEN ").append(SUBMISSION_ACTIVITY_PREDICATE).append(" THEN sa.id END) AS submittedCount, ")
                .append("  COUNT(DISTINCT ap.id) AS topicSum ")
                .append("FROM assignment_offering ao ")
                .append("JOIN assignment_template at ON at.id = ao.template_id ")
                .append("JOIN teaching_class tc ON tc.id = ao.class_id ")
                .append("LEFT JOIN course c ON c.id = tc.course_id ")
                .append("LEFT JOIN student_assignment sa ON sa.offering_id = ao.id ")
                .append("LEFT JOIN assignment_problem ap ON ap.offering_id = ao.id AND ap.status = 'ACTIVE' ")
                .append("WHERE ao.teacher_id = :teacherId ")
                .append("  AND tc.teacher_id = :teacherId ");

        boolean hasPrefix = hasText(classPrefix);
        if (hasPrefix) {
            sql.append("  AND (")
                    .append(CLASS_PREFIX_EXPR).append(" = :classPrefix ")
                    .append("   OR tc.name").append(COLL).append(" LIKE :classPrefixLike ")
                    .append("   OR ").append(EXPERIMENT_NAME_EXPR_COLL).append(" LIKE :classPrefixLike ")
                    .append("   OR COALESCE(NULLIF(TRIM(tc.pta_keyword), ").append(EMPTY_STR).append("), '')").append(COLL).append(" LIKE :classPrefixLike")
                    .append(") ");
        }
        if (classId != null) {
            sql.append("  AND ao.class_id = :classId ");
        }
        if (courseId != null) {
            sql.append("  AND tc.course_id = :courseId ");
        } else if (hasText(courseName)) {
            sql.append("  AND ").append(COURSE_NAME_EXPR).append(COLL).append(" = :courseName ");
        }

        sql.append("GROUP BY ao.id, ")
                .append(EXPERIMENT_NAME_EXPR).append(", ")
                .append(CLASS_PREFIX_EXPR).append(", ")
                .append("ao.class_id, tc.name, tc.course_id, NULLIF(TRIM(").append(COURSE_NAME_EXPR).append("), ").append(EMPTY_STR).append("), ao.seq_no ")
                .append("ORDER BY CASE WHEN ao.seq_no IS NULL THEN 1 ELSE 0 END, ao.seq_no, ao.id");

        var query = em.createNativeQuery(sql.toString());
        query.setParameter("teacherId", teacherId);
        if (hasPrefix) {
            String normalizedPrefix = classPrefix.trim();
            query.setParameter("classPrefix", normalizedPrefix);
            query.setParameter("classPrefixLike", normalizedPrefix + "%");
        }
        if (classId != null) {
            query.setParameter("classId", classId);
        }
        if (courseId != null) {
            query.setParameter("courseId", courseId);
        } else if (hasText(courseName)) {
            query.setParameter("courseName", courseName.trim());
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            if (!belongsToCourse(row[6], row[1])) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("experimentId", toInt(row[0]));
            item.put("name", row[1]);
            item.put("classPrefix", normalizeText(row[2]));
            item.put("classId", toNullableLong(row[3]));
            item.put("className", normalizeText(row[4]));
            item.put("courseId", toNullableLong(row[5]));
            item.put("courseName", normalizeText(row[6]));
            item.put("rosterCount", toInt(row[7]));
            item.put("submittedCount", toInt(row[8]));
            item.put("topicSum", toInt(row[9]));
            result.add(item);
        }
        return ApiResponse.of(result);
    }

    @GetMapping("/class-prefixes")
    public ApiResponse<List<Map<String, Object>>> getClassPrefixes(HttpServletRequest request) {
        Integer teacherId = requireCurrentTeacherId(request);
        String sql = "SELECT tc.id AS classId, tc.name AS className " +
                "FROM teaching_class tc " +
                "WHERE tc.teacher_id = :teacherId " +
                "  AND tc.status = 'ACTIVE' " +
                "  AND EXISTS (SELECT 1 FROM assignment_offering ao WHERE ao.class_id = tc.id AND ao.teacher_id = :teacherId) " +
                "ORDER BY tc.name";

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("teacherId", teacherId)
                .getResultList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("classId", toNullableLong(row[0]));
            item.put("name", normalizeText(row[1]));
            result.add(item);
        }
        return ApiResponse.of(result);
    }

    @GetMapping("/experiments/{experimentId}")
    public ApiResponse<Map<String, Object>> getExperimentAnalytics(
            @PathVariable int experimentId,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) String courseName,
            HttpServletRequest request) {
        Integer teacherId = requireCurrentTeacherId(request);
        Map<String, Object> experimentMeta = requireScopedExperiment(
                experimentId, teacherId, classId, courseId, courseName);

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> overview = computeOverview(experimentId);
        result.put("overview", overview);
        result.put("scoreDistribution", computeScoreDistribution(experimentId));
        result.put("problemAccuracy", computeProblemAccuracy(experimentId));
        result.put("experimentName", experimentMeta.get("experimentName"));
        result.put("scope", buildScope(experimentMeta, overview));
        return ApiResponse.of(result);
    }

    @GetMapping("/comparison")
    public ApiResponse<List<Map<String, Object>>> getComparison(
            @RequestParam(required = false) String classPrefix,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) String courseName,
            HttpServletRequest request) {
        List<Map<String, Object>> experiments = listExperiments(
                classPrefix, classId, courseId, courseName, request).data();
        if (experiments == null || experiments.isEmpty()) {
            return ApiResponse.of(Collections.emptyList());
        }

        List<Integer> ids = new ArrayList<>();
        for (Map<String, Object> experiment : experiments) {
            Integer experimentId = toInt(experiment.get("experimentId"));
            if (experimentId != null) {
                ids.add(experimentId);
            }
        }
        Map<Integer, Map<String, Object>> overviewBatch = computeOverviewBatch(ids);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> experiment : experiments) {
            Integer experimentId = toInt(experiment.get("experimentId"));
            if (experimentId == null) {
                continue;
            }
            Map<String, Object> overview = overviewBatch.getOrDefault(experimentId, Collections.emptyMap());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("experimentId", experimentId);
            item.put("name", experiment.get("name"));
            item.put("avgScore", overview.get("avgScore"));
            item.put("difficulty", overview.getOrDefault("difficulty", 0));
            item.put("discrimination", overview.getOrDefault("discrimination", 0));
            item.put("totalStudents", overview.getOrDefault("totalStudents", 0L));
            item.put("submittedCount", overview.getOrDefault("submittedCount", 0));
            result.add(item);
        }
        return ApiResponse.of(result);
    }

    private Integer requireCurrentTeacherId(HttpServletRequest request) {
        Teacher teacher = teacherSessionResolver.requireCurrentTeacher(request);
        if (teacher == null) {
            return null;
        }
        Integer userId = resolveTapUserId(teacher.getUsername());
        return userId == null ? teacher.getTeacher_id() : userId;
    }

    private Integer resolveTapUserId(String username) {
        if (!hasText(username)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<Number> rows = em.createNativeQuery(
                "SELECT CAST(id AS SIGNED) " +
                        "FROM tap_user " +
                        "WHERE username COLLATE utf8mb4_unicode_ci = ?1 COLLATE utf8mb4_unicode_ci " +
                        "LIMIT 1"
        ).setParameter(1, username.trim()).getResultList();

        if (rows.isEmpty() || rows.get(0) == null) {
            return null;
        }
        return rows.get(0).intValue();
    }

    private Map<String, Object> requireScopedExperiment(
            int experimentId,
            Integer teacherId,
            Long classId,
            Long courseId,
            String courseName) {
        StringBuilder sql = new StringBuilder("SELECT " +
                "  CAST(ao.id AS SIGNED) AS experimentId, " +
                "  " + EXPERIMENT_NAME_EXPR + " AS experimentName, " +
                "  " + CLASS_PREFIX_EXPR + " AS classPrefix, " +
                "  CAST(ao.class_id AS SIGNED) AS classId, " +
                "  tc.name AS className, " +
                "  CAST(tc.course_id AS SIGNED) AS courseId, " +
                "  NULLIF(TRIM(" + COURSE_NAME_EXPR + "), " + EMPTY_STR + ") AS courseName, " +
                "  COUNT(DISTINCT ap.id) AS problemCount " +
                "FROM assignment_offering ao " +
                "JOIN assignment_template at ON at.id = ao.template_id " +
                "JOIN teaching_class tc ON tc.id = ao.class_id " +
                "LEFT JOIN course c ON c.id = tc.course_id " +
                "LEFT JOIN assignment_problem ap ON ap.offering_id = ao.id AND ap.status = 'ACTIVE' " +
                "WHERE ao.id = :experimentId " +
                "  AND ao.teacher_id = :teacherId " +
                "  AND tc.teacher_id = :teacherId ");
        if (classId != null) {
            sql.append("  AND ao.class_id = :classId ");
        }
        if (courseId != null) {
            sql.append("  AND tc.course_id = :courseId ");
        } else if (hasText(courseName)) {
            sql.append("  AND ").append(COURSE_NAME_EXPR).append(COLL).append(" = :courseName ");
        }
        sql.append("GROUP BY ao.id, ").append(EXPERIMENT_NAME_EXPR).append(", ")
                .append(CLASS_PREFIX_EXPR)
                .append(", ao.class_id, tc.name, tc.course_id, NULLIF(TRIM(")
                .append(COURSE_NAME_EXPR).append("), ").append(EMPTY_STR).append(")");

        @SuppressWarnings("unchecked")
        var query = em.createNativeQuery(sql.toString())
                .setParameter("experimentId", experimentId)
                .setParameter("teacherId", teacherId);
        if (classId != null) {
            query.setParameter("classId", classId);
        }
        if (courseId != null) {
            query.setParameter("courseId", courseId);
        } else if (hasText(courseName)) {
            query.setParameter("courseName", courseName.trim());
        }
        List<Object[]> rows = query.getResultList();

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "experiment analytics not found");
        }

        Object[] row = rows.get(0);
        if (!belongsToCourse(row[6], row[1])) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "experiment analytics not found");
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("experimentId", toInt(row[0]));
        meta.put("experimentName", row[1]);
        meta.put("classPrefix", normalizeText(row[2]));
        meta.put("classId", toNullableLong(row[3]));
        meta.put("className", normalizeText(row[4]));
        meta.put("courseId", toNullableLong(row[5]));
        meta.put("courseName", normalizeText(row[6]));
        meta.put("problemCount", toInt(row[7]));
        return meta;
    }

    private Map<String, Object> buildScope(Map<String, Object> experimentMeta, Map<String, Object> overview) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("classPrefix", experimentMeta.get("classPrefix"));
        scope.put("classId", experimentMeta.get("classId"));
        scope.put("className", experimentMeta.get("className"));
        scope.put("courseId", experimentMeta.get("courseId"));
        scope.put("courseName", experimentMeta.get("courseName"));
        scope.put("problemCount", experimentMeta.get("problemCount"));
        scope.put("dataScope", "current course");
        scope.put("rosterCount", overview.get("rosterCount"));
        scope.put("submittedCount", overview.get("submittedCount"));
        scope.put("scoredCount", overview.get("scoredCount"));
        scope.put(
                "scopeNote",
                "Headcount is deduplicated from student_assignment roster rows instead of legacy score detail rows."
        );
        return scope;
    }

    /**
     * Single-query overview: roster counts + score statistics + full score,
     * all computed in MySQL via CTEs and window functions.
     */
    private Map<String, Object> computeOverview(int experimentId) {
        String sql = "WITH "
                + "roster AS ("
                + "  SELECT "
                + "    COUNT(*) AS roster_count, "
                + "    SUM(CASE WHEN " + SUBMISSION_ACTIVITY_PREDICATE + " THEN 1 ELSE 0 END) AS submitted_count, "
                + "    SUM(CASE WHEN " + SCORED_ASSIGNMENT_PREDICATE + " THEN 1 ELSE 0 END) AS scored_count "
                + "  FROM student_assignment sa "
                + "  WHERE sa.offering_id = ?1"
                + "), "
                + "scores AS ("
                + "  SELECT COALESCE(sa.best_total_score, sa.latest_total_score, 0) AS score "
                + "  FROM student_assignment sa "
                + "  WHERE sa.offering_id = ?1"
                + "    AND (" + SUBMISSION_ACTIVITY_PREDICATE + ")"
                + "), "
                + "ranked AS ("
                + "  SELECT score, "
                + "    ROW_NUMBER() OVER (ORDER BY score) AS rn_asc, "
                + "    ROW_NUMBER() OVER (ORDER BY score DESC) AS rn_desc, "
                + "    COUNT(*) OVER () AS cnt "
                + "  FROM scores"
                + "), "
                + "score_stats AS ("
                + "  SELECT "
                + "    COUNT(*) AS total_students, "
                + "    COALESCE(MAX(score), 0) AS max_score, "
                + "    COALESCE(MIN(score), 0) AS min_score, "
                + "    COALESCE(AVG(score), 0) AS avg_score, "
                + "    (SELECT COALESCE(AVG(score), 0) FROM ranked WHERE rn_asc IN (FLOOR((cnt + 1) / 2), CEIL((cnt + 1) / 2))) AS median, "
                + "    (SELECT COALESCE(AVG(score), 0) FROM ranked WHERE rn_desc <= GREATEST(1, FLOOR(cnt * 0.27 + 0.5))) AS top_avg, "
                + "    (SELECT COALESCE(AVG(score), 0) FROM ranked WHERE rn_asc <= GREATEST(1, FLOOR(cnt * 0.27 + 0.5))) AS bottom_avg "
                + "  FROM scores"
                + "), "
                + "full_score AS ("
                + "  SELECT COALESCE(SUM(COALESCE(ap.max_score, 0)), 0) AS full_score "
                + "  FROM assignment_problem ap "
                + "  WHERE ap.offering_id = ?1 AND ap.status = 'ACTIVE'"
                + ") "
                + "SELECT "
                + "  r.roster_count, r.submitted_count, r.scored_count, "
                + "  s.total_students, s.max_score, s.min_score, s.avg_score, "
                + "  s.median, s.top_avg, s.bottom_avg, "
                + "  f.full_score "
                + "FROM roster r "
                + "CROSS JOIN score_stats s "
                + "CROSS JOIN full_score f";

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter(1, experimentId)
                .getResultList();

        Map<String, Object> overview = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            overview.put("totalStudents", 0);
            overview.put("rosterCount", 0);
            overview.put("submittedCount", 0);
            overview.put("scoredCount", 0);
            overview.put("fullScore", 0);
            return overview;
        }

        Object[] row = rows.get(0);
        int rosterCount = toInt(row[0]);
        int submittedCount = toInt(row[1]);
        int scoredCount = toInt(row[2]);
        long totalStudents = toLong(row[3]);
        double maxScore = toDouble(row[4]);
        double minScore = toDouble(row[5]);
        double avgScore = toDouble(row[6]);
        double median = toDouble(row[7]);
        double topAvg = toDouble(row[8]);
        double bottomAvg = toDouble(row[9]);
        double fullScore = toDouble(row[10]);

        if (fullScore <= 0 && maxScore > 0) {
            fullScore = maxScore;
        }
        if (fullScore <= 0 && totalStudents > 0) {
            fullScore = 100.0;
        }
        if (totalStudents == 0) {
            fullScore = queryFullScore(experimentId);
        }

        overview.put("totalStudents", totalStudents);
        overview.put("rosterCount", rosterCount);
        overview.put("submittedCount", submittedCount);
        overview.put("scoredCount", scoredCount);
        overview.put("maxScore", round2(maxScore));
        overview.put("minScore", round2(minScore));
        overview.put("avgScore", round2(avgScore));
        overview.put("median", round2(median));
        overview.put("topAvg", round2(topAvg));
        overview.put("bottomAvg", round2(bottomAvg));
        overview.put("fullScore", round2(fullScore));
        overview.put("difficulty", fullScore > 0 ? round2(1.0 - avgScore / fullScore) : 0);
        overview.put("discrimination", fullScore > 0 ? round2((topAvg - bottomAvg) / fullScore) : 0);
        return overview;
    }

    private Map<Integer, Map<String, Object>> computeOverviewBatch(List<Integer> experimentIds) {
        Map<Integer, Map<String, Object>> result = new LinkedHashMap<>();
        if (experimentIds.isEmpty()) {
            return result;
        }

        StringBuilder selectedOfferings = new StringBuilder();
        for (int i = 0; i < experimentIds.size(); i++) {
            if (i > 0) selectedOfferings.append(" UNION ALL ");
            selectedOfferings.append("SELECT ?").append(i + 1).append(" AS offering_id");
        }

        String sql = "WITH selected_offerings AS (" + selectedOfferings + "), "
                + "roster AS ("
                + "  SELECT so.offering_id, COUNT(sa.id) AS roster_count, "
                + "    SUM(CASE WHEN " + SUBMISSION_ACTIVITY_PREDICATE + " THEN 1 ELSE 0 END) AS submitted_count, "
                + "    SUM(CASE WHEN " + SCORED_ASSIGNMENT_PREDICATE + " THEN 1 ELSE 0 END) AS scored_count "
                + "  FROM selected_offerings so "
                + "  LEFT JOIN student_assignment sa ON sa.offering_id = so.offering_id "
                + "  GROUP BY so.offering_id"
                + "), scores AS ("
                + "  SELECT sa.offering_id, COALESCE(sa.best_total_score, sa.latest_total_score, 0) AS score "
                + "  FROM student_assignment sa "
                + "  JOIN selected_offerings so ON so.offering_id = sa.offering_id "
                + "  WHERE " + SUBMISSION_ACTIVITY_PREDICATE
                + "), ranked AS ("
                + "  SELECT offering_id, score, "
                + "    ROW_NUMBER() OVER (PARTITION BY offering_id ORDER BY score) AS rn_asc, "
                + "    ROW_NUMBER() OVER (PARTITION BY offering_id ORDER BY score DESC) AS rn_desc, "
                + "    COUNT(*) OVER (PARTITION BY offering_id) AS cnt "
                + "  FROM scores"
                + "), score_stats AS ("
                + "  SELECT offering_id, COUNT(*) AS total_students, "
                + "    COALESCE(MAX(score), 0) AS max_score, COALESCE(AVG(score), 0) AS avg_score, "
                + "    COALESCE(AVG(CASE WHEN rn_desc <= GREATEST(1, FLOOR(cnt * 0.27 + 0.5)) THEN score END), 0) AS top_avg, "
                + "    COALESCE(AVG(CASE WHEN rn_asc <= GREATEST(1, FLOOR(cnt * 0.27 + 0.5)) THEN score END), 0) AS bottom_avg "
                + "  FROM ranked GROUP BY offering_id"
                + "), full_scores AS ("
                + "  SELECT so.offering_id, COALESCE(SUM(COALESCE(ap.max_score, 0)), 0) AS full_score "
                + "  FROM selected_offerings so "
                + "  LEFT JOIN assignment_problem ap ON ap.offering_id = so.offering_id AND ap.status = 'ACTIVE' "
                + "  GROUP BY so.offering_id"
                + ") SELECT so.offering_id, r.roster_count, r.submitted_count, r.scored_count, "
                + "  COALESCE(s.total_students, 0), COALESCE(s.max_score, 0), COALESCE(s.avg_score, 0), "
                + "  COALESCE(s.top_avg, 0), COALESCE(s.bottom_avg, 0), f.full_score "
                + "FROM selected_offerings so "
                + "JOIN roster r ON r.offering_id = so.offering_id "
                + "LEFT JOIN score_stats s ON s.offering_id = so.offering_id "
                + "JOIN full_scores f ON f.offering_id = so.offering_id";

        var query = em.createNativeQuery(sql);
        for (int i = 0; i < experimentIds.size(); i++) {
            query.setParameter(i + 1, experimentIds.get(i));
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        for (Object[] row : rows) {
            int experimentId = toInt(row[0]);
            Map<String, Object> overview = new LinkedHashMap<>();
            overview.put("rosterCount", toInt(row[1]));
            overview.put("submittedCount", toInt(row[2]));
            overview.put("scoredCount", toInt(row[3]));
            overview.put("totalStudents", toLong(row[4]));
            double maxScore = toDouble(row[5]);
            double avgScore = toDouble(row[6]);
            double topAvg = toDouble(row[7]);
            double bottomAvg = toDouble(row[8]);
            double fullScore = toDouble(row[9]);
            if (fullScore <= 0 && maxScore > 0) {
                fullScore = maxScore;
            }
            if (fullScore <= 0 && toLong(row[4]) > 0) {
                fullScore = 100.0;
            }
            if (fullScore <= 0) {
                fullScore = queryFullScore(experimentId);
            }
            overview.put("avgScore", round2(avgScore));
            overview.put("difficulty", fullScore > 0 ? round2(1.0 - avgScore / fullScore) : 0);
            overview.put("discrimination", fullScore > 0 ? round2((topAvg - bottomAvg) / fullScore) : 0);
            result.put(experimentId, overview);
        }
        return result;
    }

    private Map<String, Object> computeScoreDistribution(int experimentId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT " +
                        "  SUM(CASE WHEN score = 100 THEN 1 ELSE 0 END), " +
                        "  SUM(CASE WHEN score >= 90 AND score < 100 THEN 1 ELSE 0 END), " +
                        "  SUM(CASE WHEN score >= 80 AND score < 90 THEN 1 ELSE 0 END), " +
                        "  SUM(CASE WHEN score >= 70 AND score < 80 THEN 1 ELSE 0 END), " +
                        "  SUM(CASE WHEN score >= 60 AND score < 70 THEN 1 ELSE 0 END), " +
                        "  SUM(CASE WHEN score >= 50 AND score < 60 THEN 1 ELSE 0 END), " +
                        "  SUM(CASE WHEN score >= 40 AND score < 50 THEN 1 ELSE 0 END), " +
                        "  SUM(CASE WHEN score >= 30 AND score < 40 THEN 1 ELSE 0 END), " +
                        "  SUM(CASE WHEN score >= 20 AND score < 30 THEN 1 ELSE 0 END), " +
                        "  SUM(CASE WHEN score >= 10 AND score < 20 THEN 1 ELSE 0 END), " +
                        "  SUM(CASE WHEN score >= 0 AND score < 10 THEN 1 ELSE 0 END) " +
                        "FROM (" +
                        "  SELECT COALESCE(sa.best_total_score, sa.latest_total_score, 0) AS score " +
                        "  FROM student_assignment sa " +
                        "  WHERE sa.offering_id = ?1" +
                        ") scored"
        ).setParameter(1, experimentId).getResultList();

        Map<String, Object> dist = new LinkedHashMap<>();
        String[] labels = {
                "[100,100]", "[90,100)", "[80,90)", "[70,80)", "[60,70)",
                "[50,60)", "[40,50)", "[30,40)", "[20,30)", "[10,20)", "[0,10)"
        };
        if (!rows.isEmpty() && rows.get(0) != null) {
            Object[] row = rows.get(0);
            for (int i = 0; i < labels.length; i++) {
                dist.put(labels[i], toInt(row[i]));
            }
        } else {
            for (String label : labels) {
                dist.put(label, 0);
            }
        }
        return dist;
    }

    private List<Map<String, Object>> computeProblemAccuracy(int experimentId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT " +
                        "  COALESCE(NULLIF(TRIM(ap.problem_no), " + EMPTY_STR + "), CAST(ap.sort_order AS CHAR), CAST(ap.id AS CHAR)) AS problem_label, " +
                        "  COALESCE(NULLIF(TRIM(ap.title), " + EMPTY_STR + "), 'PTA Problem') AS problem_title, " +
                        "  COALESCE(ap.max_score, 0) AS full_score, " +
                        "  AVG(COALESCE(sps.best_score, 0)) AS avg_score, " +
                        "  COUNT(sa.id) AS student_count, " +
                        "  SUM(CASE " +
                        "        WHEN COALESCE(ap.max_score, 0) > 0 AND COALESCE(sps.best_score, 0) >= ap.max_score " +
                        "        THEN 1 ELSE 0 END) AS full_mark_count, " +
                        "  SUM(CASE WHEN COALESCE(sps.best_score, 0) = 0 THEN 1 ELSE 0 END) AS zero_count " +
                        "FROM assignment_problem ap " +
                        "JOIN student_assignment sa ON sa.offering_id = ap.offering_id " +
                        "LEFT JOIN student_problem_state sps " +
                        "  ON sps.offering_id = ap.offering_id " +
                        " AND sps.problem_id = ap.id " +
                        " AND sps.student_id = sa.student_id " +
                        "WHERE ap.offering_id = ?1 " +
                        "  AND ap.status = 'ACTIVE' " +
                        "GROUP BY ap.id, problem_label, problem_title, ap.max_score, ap.sort_order " +
                        "ORDER BY ap.sort_order, ap.id"
        ).setParameter(1, experimentId).getResultList();

        if (rows.isEmpty()) {
            return computeLegacyProblemAccuracy(experimentId);
        }

        List<Double> legacyFullScores = null;
        boolean needsLegacyFallback = false;
        for (Object[] row : rows) {
            if (toDouble(row[2]) <= 0) {
                needsLegacyFallback = true;
                break;
            }
        }
        if (needsLegacyFallback) {
            legacyFullScores = queryLegacyProblemFullScores(experimentId);
        }

        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Object[] row = rows.get(i);
            double fullScore = toDouble(row[2]);
            if (fullScore <= 0 && legacyFullScores != null && i < legacyFullScores.size()) {
                fullScore = legacyFullScores.get(i);
            }
            double avgScore = toDouble(row[3]);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", row[0]);
            item.put("type", row[1]);
            item.put("fullScore", round2(fullScore));
            item.put("avgScore", round2(avgScore));
            item.put("accuracyRate", fullScore > 0 ? round2(avgScore / fullScore * 100) : 0);
            item.put("studentCount", toInt(row[4]));
            item.put("fullMarkCount", toInt(row[5]));
            item.put("zeroCount", toInt(row[6]));
            result.add(item);
        }
        return result;
    }

    private List<Double> queryLegacyProblemFullScores(int offeringId) {
        Integer legacyExperimentId = resolveLegacyExperimentId(offeringId);
        if (legacyExperimentId == null) {
            return Collections.emptyList();
        }
        @SuppressWarnings("unchecked")
        List<Number> rows = em.createNativeQuery(
                "SELECT MAX(COALESCE(psd.max_score, 0)) AS full_score " +
                        "FROM problem_score_detail psd " +
                        "JOIN student_profile sp " +
                        "  ON sp.student_no" + COLL + " = psd.student_id" + COLL + " " +
                        "JOIN student_assignment sa " +
                        "  ON sa.student_id = sp.id AND sa.offering_id = ?2 " +
                        "WHERE psd.experiment_id = ?1 " +
                        "GROUP BY psd.problem_label " +
                        "ORDER BY CAST(psd.problem_label AS UNSIGNED), psd.problem_label"
        ).setParameter(1, legacyExperimentId)
                .setParameter(2, offeringId)
                .getResultList();

        List<Double> scores = new ArrayList<>(rows.size());
        for (Number row : rows) {
            scores.add(row == null ? 0.0 : row.doubleValue());
        }
        return scores;
    }

    private List<Map<String, Object>> computeLegacyProblemAccuracy(int offeringId) {
        Integer legacyExperimentId = resolveLegacyExperimentId(offeringId);
        if (legacyExperimentId == null) {
            return Collections.emptyList();
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT " +
                        "  psd.problem_label, " +
                        "  COALESCE(NULLIF(TRIM(psd.problem_type), " + EMPTY_STR + "), 'PTA Problem') AS problem_type, " +
                        "  MAX(COALESCE(psd.max_score, 0)) AS full_score, " +
                        "  AVG(COALESCE(psd.actual_score, 0)) AS avg_score, " +
                        "  COUNT(DISTINCT psd.student_id) AS student_count, " +
                        "  SUM(CASE " +
                        "        WHEN COALESCE(psd.max_score, 0) > 0 AND COALESCE(psd.actual_score, 0) >= COALESCE(psd.max_score, 0) " +
                        "        THEN 1 ELSE 0 END) AS full_mark_count, " +
                        "  SUM(CASE WHEN COALESCE(psd.actual_score, 0) = 0 THEN 1 ELSE 0 END) AS zero_count " +
                        "FROM problem_score_detail psd " +
                        "JOIN student_profile sp " +
                        "  ON sp.student_no" + COLL + " = psd.student_id" + COLL + " " +
                        "JOIN student_assignment sa " +
                        "  ON sa.student_id = sp.id AND sa.offering_id = ?2 " +
                        "WHERE psd.experiment_id = ?1 " +
                        "GROUP BY psd.problem_label, psd.problem_type " +
                        "ORDER BY CAST(psd.problem_label AS UNSIGNED), psd.problem_label"
        ).setParameter(1, legacyExperimentId)
                .setParameter(2, offeringId)
                .getResultList();

        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            double fullScore = toDouble(row[2]);
            double avgScore = toDouble(row[3]);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", row[0]);
            item.put("type", row[1]);
            item.put("fullScore", round2(fullScore));
            item.put("avgScore", round2(avgScore));
            item.put("accuracyRate", fullScore > 0 ? round2(avgScore / fullScore * 100) : 0);
            item.put("studentCount", toInt(row[4]));
            item.put("fullMarkCount", toInt(row[5]));
            item.put("zeroCount", toInt(row[6]));
            result.add(item);
        }
        return result;
    }

    private double queryFullScore(int experimentId) {
        @SuppressWarnings("unchecked")
        List<Number> rows = em.createNativeQuery(
                "SELECT SUM(COALESCE(ap.max_score, 0)) " +
                        "FROM assignment_problem ap " +
                        "WHERE ap.offering_id = ?1 AND ap.status = 'ACTIVE'"
        ).setParameter(1, experimentId).getResultList();

        if (rows.isEmpty() || rows.get(0) == null) {
            return queryLegacyFullScore(experimentId);
        }
        double fullScore = rows.get(0).doubleValue();
        return fullScore > 0 ? fullScore : queryLegacyFullScore(experimentId);
    }

    private double queryLegacyFullScore(int offeringId) {
        Integer legacyExperimentId = resolveLegacyExperimentId(offeringId);
        if (legacyExperimentId == null) {
            return 0;
        }
        @SuppressWarnings("unchecked")
        List<Number> rows = em.createNativeQuery(
                "SELECT SUM(full_score) FROM (" +
                        "  SELECT psd.problem_label, MAX(COALESCE(psd.max_score, 0)) AS full_score " +
                        "  FROM problem_score_detail psd " +
                        "  JOIN student_profile sp " +
                        "    ON sp.student_no" + COLL + " = psd.student_id" + COLL + " " +
                        "  JOIN student_assignment sa " +
                        "    ON sa.student_id = sp.id AND sa.offering_id = ?2 " +
                        "  WHERE psd.experiment_id = ?1 " +
                        "  GROUP BY psd.problem_label" +
                        ") problem_scores"
        ).setParameter(1, legacyExperimentId)
                .setParameter(2, offeringId)
                .getResultList();
        if (rows.isEmpty() || rows.get(0) == null) {
            return 0;
        }
        return rows.get(0).doubleValue();
    }

    private Integer resolveLegacyExperimentId(int offeringId) {
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery(
                "SELECT CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(source_offering_key, ':CLASS:', 1), ':', -1) AS SIGNED) " +
                        "FROM assignment_offering " +
                        "WHERE id = ?1 " +
                        "  AND source_system = 'LEGACY_TAP' " +
                        "  AND source_offering_key LIKE 'LEGACY_EXPERIMENT_OFFERING:%' " +
                        "LIMIT 1"
        ).setParameter(1, offeringId).getResultList();

        if (rows.isEmpty() || rows.get(0) == null) {
            return null;
        }
        return toInt(rows.get(0));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Legacy imports may attach offerings from different courses to one teaching class.
     * Reject an offering only when both sides expose conflicting, recognizable subjects;
     * generic titles without a course marker remain available.
     */
    private static boolean belongsToCourse(Object courseValue, Object experimentValue) {
        int courseSubject = detectCourseSubject(normalizeText(courseValue));
        int experimentSubject = detectCourseSubject(normalizeText(experimentValue));
        return courseSubject < 0 || experimentSubject < 0 || courseSubject == experimentSubject;
    }

    private static int detectCourseSubject(String value) {
        if (!hasText(value)) {
            return -1;
        }
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < COURSE_SUBJECT_ALIASES.size(); i++) {
            for (String alias : COURSE_SUBJECT_ALIASES.get(i)) {
                if (normalized.contains(alias.toLowerCase(java.util.Locale.ROOT))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String normalizeText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Double toDouble(Object value) {
        return value == null ? 0.0 : ((Number) value).doubleValue();
    }

    private static Integer toInt(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static Long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static Long toNullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
