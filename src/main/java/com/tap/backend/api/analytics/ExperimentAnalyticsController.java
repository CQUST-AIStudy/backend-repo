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
 * - only data-structure offerings
 * - exclude C-language course data mixed into legacy names
 */
@RestController
@RequestMapping("/api/analytics")
public class ExperimentAnalyticsController {

    private static final String DATA_STRUCTURE_KEYWORD = "\u6570\u636e\u7ed3\u6784";
    private static final String C_LANGUAGE_KEYWORD = "C\u8bed\u8a00";

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
    private static final String C_LANGUAGE_SQL_LITERAL =
            "_utf8mb4'" + C_LANGUAGE_KEYWORD + "' COLLATE utf8mb4_0900_ai_ci";
    private static final String DATA_STRUCTURE_LIKE_PATTERN =
            "CONCAT('%', " + DATA_STRUCTURE_SQL_LITERAL + ", '%')";
    private static final String C_LANGUAGE_LIKE_PATTERN =
            "CONCAT('%', " + C_LANGUAGE_SQL_LITERAL + ", '%')";

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
            HttpServletRequest request) {
        Integer teacherId = requireCurrentTeacherId(request);

        StringBuilder sql = new StringBuilder()
                .append("SELECT ")
                .append("  CAST(ao.id AS SIGNED) AS experimentId, ")
                .append("  ").append(EXPERIMENT_NAME_EXPR).append(" AS name, ")
                .append("  ").append(CLASS_PREFIX_EXPR).append(" AS classPrefix, ")
                .append("  tc.name AS className, ")
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
                .append("WHERE ao.teacher_id = ?1 ");

        boolean hasPrefix = hasText(classPrefix);
        if (hasPrefix) {
            sql.append("  AND (")
                    .append(CLASS_PREFIX_EXPR).append(" = ?2 ")
                    .append("   OR tc.name").append(COLL).append(" LIKE ?3 ")
                    .append("   OR ").append(EXPERIMENT_NAME_EXPR_COLL).append(" LIKE ?3 ")
                    .append("   OR COALESCE(NULLIF(TRIM(tc.pta_keyword), ").append(EMPTY_STR).append("), '')").append(COLL).append(" LIKE ?3")
                    .append(") ");
        }

        sql.append("GROUP BY ao.id, ")
                .append(EXPERIMENT_NAME_EXPR).append(", ")
                .append(CLASS_PREFIX_EXPR).append(", ")
                .append("tc.name, NULLIF(TRIM(").append(COURSE_NAME_EXPR).append("), ").append(EMPTY_STR).append("), ao.seq_no ")
                .append("ORDER BY CASE WHEN ao.seq_no IS NULL THEN 1 ELSE 0 END, ao.seq_no, ao.id");

        var query = em.createNativeQuery(sql.toString());
        query.setParameter(1, teacherId);
        if (hasPrefix) {
            String normalizedPrefix = classPrefix.trim();
            query.setParameter(2, normalizedPrefix);
            query.setParameter(3, normalizedPrefix + "%");
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("experimentId", toInt(row[0]));
            item.put("name", row[1]);
            item.put("classPrefix", normalizeText(row[2]));
            item.put("className", normalizeText(row[3]));
            item.put("courseName", normalizeText(row[4]));
            item.put("rosterCount", toInt(row[5]));
            item.put("submittedCount", toInt(row[6]));
            item.put("topicSum", toInt(row[7]));
            result.add(item);
        }
        return ApiResponse.of(result);
    }

    @GetMapping("/class-prefixes")
    public ApiResponse<List<String>> getClassPrefixes(HttpServletRequest request) {
        Integer teacherId = requireCurrentTeacherId(request);
        String sql = "SELECT DISTINCT " + CLASS_PREFIX_EXPR + " AS prefix " +
                "FROM assignment_offering ao " +
                "JOIN assignment_template at ON at.id = ao.template_id " +
                "JOIN teaching_class tc ON tc.id = ao.class_id " +
                "LEFT JOIN course c ON c.id = tc.course_id " +
                "WHERE ao.teacher_id = ?1 " +
                "ORDER BY prefix";

        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery(sql)
                .setParameter(1, teacherId)
                .getResultList();

        List<String> prefixes = new ArrayList<>();
        for (Object row : rows) {
            String prefix = normalizeText(row);
            if (hasText(prefix)) {
                prefixes.add(prefix);
            }
        }
        return ApiResponse.of(prefixes);
    }

    @GetMapping("/experiments/{experimentId}")
    public ApiResponse<Map<String, Object>> getExperimentAnalytics(
            @PathVariable int experimentId,
            HttpServletRequest request) {
        Integer teacherId = requireCurrentTeacherId(request);
        Map<String, Object> experimentMeta = requireScopedExperiment(experimentId, teacherId);

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
            HttpServletRequest request) {
        List<Map<String, Object>> experiments = listExperiments(classPrefix, request).data();
        if (experiments == null || experiments.isEmpty()) {
            return ApiResponse.of(Collections.emptyList());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> experiment : experiments) {
            Integer experimentId = toInt(experiment.get("experimentId"));
            if (experimentId == null) {
                continue;
            }
            Map<String, Object> overview = computeOverview(experimentId);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("experimentId", experimentId);
            item.put("name", experiment.get("name"));
            item.put("avgScore", overview.get("avgScore"));
            item.put("difficulty", overview.get("difficulty"));
            item.put("discrimination", overview.get("discrimination"));
            item.put("totalStudents", overview.get("totalStudents"));
            item.put("submittedCount", overview.get("submittedCount"));
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

    private Map<String, Object> requireScopedExperiment(int experimentId, Integer teacherId) {
        String sql = "SELECT " +
                "  CAST(ao.id AS SIGNED) AS experimentId, " +
                "  " + EXPERIMENT_NAME_EXPR + " AS experimentName, " +
                "  " + CLASS_PREFIX_EXPR + " AS classPrefix, " +
                "  tc.name AS className, " +
                "  NULLIF(TRIM(" + COURSE_NAME_EXPR + "), " + EMPTY_STR + ") AS courseName, " +
                "  COUNT(DISTINCT ap.id) AS problemCount " +
                "FROM assignment_offering ao " +
                "JOIN assignment_template at ON at.id = ao.template_id " +
                "JOIN teaching_class tc ON tc.id = ao.class_id " +
                "LEFT JOIN course c ON c.id = tc.course_id " +
                "LEFT JOIN assignment_problem ap ON ap.offering_id = ao.id AND ap.status = 'ACTIVE' " +
                "WHERE ao.id = ?1 " +
                "  AND ao.teacher_id = ?2 " +
                "GROUP BY ao.id, " + EXPERIMENT_NAME_EXPR + ", " + CLASS_PREFIX_EXPR + ", tc.name, NULLIF(TRIM(" + COURSE_NAME_EXPR + "), " + EMPTY_STR + ")";

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter(1, experimentId)
                .setParameter(2, teacherId)
                .getResultList();

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "experiment analytics not found");
        }

        Object[] row = rows.get(0);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("experimentId", toInt(row[0]));
        meta.put("experimentName", row[1]);
        meta.put("classPrefix", normalizeText(row[2]));
        meta.put("className", normalizeText(row[3]));
        meta.put("courseName", normalizeText(row[4]));
        meta.put("problemCount", toInt(row[5]));
        return meta;
    }

    private Map<String, Object> buildScope(Map<String, Object> experimentMeta, Map<String, Object> overview) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("classPrefix", experimentMeta.get("classPrefix"));
        scope.put("className", experimentMeta.get("className"));
        scope.put("courseName", experimentMeta.get("courseName"));
        scope.put("problemCount", experimentMeta.get("problemCount"));
        scope.put("dataScope", "all courses");
        scope.put("rosterCount", overview.get("rosterCount"));
        scope.put("submittedCount", overview.get("submittedCount"));
        scope.put("scoredCount", overview.get("scoredCount"));
        scope.put(
                "scopeNote",
                "Headcount is deduplicated from student_assignment roster rows instead of legacy score detail rows."
        );
        return scope;
    }

    private Map<String, Object> computeOverview(int experimentId) {
        Map<String, Object> overview = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        List<Object[]> countRows = em.createNativeQuery(
                "SELECT " +
                        "  COUNT(*) AS rosterCount, " +
                        "  SUM(CASE WHEN " + SUBMISSION_ACTIVITY_PREDICATE + " THEN 1 ELSE 0 END) AS submittedCount, " +
                        "  SUM(CASE WHEN " + SCORED_ASSIGNMENT_PREDICATE + " THEN 1 ELSE 0 END) AS scoredCount " +
                        "FROM student_assignment sa " +
                        "WHERE sa.offering_id = ?1"
        ).setParameter(1, experimentId).getResultList();

        int rosterCount = 0;
        int submittedCount = 0;
        int scoredCount = 0;
        if (!countRows.isEmpty()) {
            Object[] row = countRows.get(0);
            rosterCount = toInt(row[0]) == null ? 0 : toInt(row[0]);
            submittedCount = toInt(row[1]) == null ? 0 : toInt(row[1]);
            scoredCount = toInt(row[2]) == null ? 0 : toInt(row[2]);
        }

        @SuppressWarnings("unchecked")
        List<Number> scoreRows = em.createNativeQuery(
                "SELECT COALESCE(sa.best_total_score, sa.latest_total_score, 0) AS score " +
                        "FROM student_assignment sa " +
                        "WHERE sa.offering_id = ?1 " +
                        "ORDER BY score DESC"
        ).setParameter(1, experimentId).getResultList();

        double fullScore = queryFullScore(experimentId);
        if (scoreRows.isEmpty()) {
            overview.put("totalStudents", 0);
            overview.put("rosterCount", rosterCount);
            overview.put("submittedCount", submittedCount);
            overview.put("scoredCount", scoredCount);
            overview.put("fullScore", round2(fullScore));
            return overview;
        }

        List<Double> scores = new ArrayList<>(scoreRows.size());
        for (Number row : scoreRows) {
            scores.add(row == null ? 0.0 : row.doubleValue());
        }

        scores.sort(Collections.reverseOrder());
        int n = scores.size();
        double max = scores.get(0);
        double min = scores.get(n - 1);
        double sum = scores.stream().mapToDouble(Double::doubleValue).sum();
        double avg = sum / n;
        double median = n % 2 == 0
                ? (scores.get(n / 2 - 1) + scores.get(n / 2)) / 2.0
                : scores.get(n / 2);

        int bandSize = Math.max(1, (int) Math.round(n * 0.27));
        double topAvg = scores.subList(0, bandSize).stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0);
        double bottomAvg = scores.subList(n - bandSize, n).stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0);

        if (fullScore <= 0) {
            fullScore = max > 0 ? max : 100.0;
        }

        overview.put("totalStudents", n);
        overview.put("rosterCount", rosterCount);
        overview.put("submittedCount", submittedCount);
        overview.put("scoredCount", scoredCount);
        overview.put("maxScore", round2(max));
        overview.put("minScore", round2(min));
        overview.put("avgScore", round2(avg));
        overview.put("median", round2(median));
        overview.put("topAvg", round2(topAvg));
        overview.put("bottomAvg", round2(bottomAvg));
        overview.put("fullScore", round2(fullScore));
        overview.put("difficulty", fullScore > 0 ? round2(1.0 - avg / fullScore) : 0);
        overview.put("discrimination", fullScore > 0 ? round2((topAvg - bottomAvg) / fullScore) : 0);
        return overview;
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
                "SELECT MAX(COALESCE(max_score, 0)) AS full_score " +
                        "FROM problem_score_detail " +
                        "WHERE experiment_id = ?1 " +
                        "GROUP BY problem_label " +
                        "ORDER BY CAST(problem_label AS UNSIGNED), problem_label"
        ).setParameter(1, legacyExperimentId).getResultList();

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
                        "  problem_label, " +
                        "  COALESCE(NULLIF(TRIM(problem_type), " + EMPTY_STR + "), 'PTA Problem') AS problem_type, " +
                        "  MAX(COALESCE(max_score, 0)) AS full_score, " +
                        "  AVG(COALESCE(actual_score, 0)) AS avg_score, " +
                        "  COUNT(DISTINCT student_id) AS student_count, " +
                        "  SUM(CASE " +
                        "        WHEN COALESCE(max_score, 0) > 0 AND COALESCE(actual_score, 0) >= COALESCE(max_score, 0) " +
                        "        THEN 1 ELSE 0 END) AS full_mark_count, " +
                        "  SUM(CASE WHEN COALESCE(actual_score, 0) = 0 THEN 1 ELSE 0 END) AS zero_count " +
                        "FROM problem_score_detail " +
                        "WHERE experiment_id = ?1 " +
                        "GROUP BY problem_label, problem_type " +
                        "ORDER BY CAST(problem_label AS UNSIGNED), problem_label"
        ).setParameter(1, legacyExperimentId).getResultList();

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
                        "  SELECT problem_label, MAX(COALESCE(max_score, 0)) AS full_score " +
                        "  FROM problem_score_detail " +
                        "  WHERE experiment_id = ?1 " +
                        "  GROUP BY problem_label" +
                        ") problem_scores"
        ).setParameter(1, legacyExperimentId).getResultList();
        if (rows.isEmpty() || rows.get(0) == null) {
            return 0;
        }
        return rows.get(0).doubleValue();
    }

    private Integer resolveLegacyExperimentId(int offeringId) {
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery(
                "SELECT CAST(SUBSTRING_INDEX(source_offering_key, ':', -1) AS SIGNED) " +
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

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
