package com.tap.backend.api.analytics;

import com.tap.backend.academic.security.StudentSessionResolver;
import com.tap.backend.academic.entity.UserEntity;
import com.tap.common.api.ApiResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/analytics/student")
public class StudentAnalyticsController {

    private final StudentSessionResolver studentSessionResolver;

    @PersistenceContext
    private EntityManager em;

    public StudentAnalyticsController(StudentSessionResolver studentSessionResolver) {
        this.studentSessionResolver = studentSessionResolver;
    }

    @GetMapping("/{studentId}/overview")
    public ApiResponse<Map<String, Object>> getStudentOverview(
            @PathVariable String studentId,
            HttpServletRequest request
    ) {
        Long studentProfileId = resolveStudentProfileId(
                studentSessionResolver.requireAuthorizedStudentId(studentId, request));
        UserEntity currentUser = studentSessionResolver.requireStudent(request);
        String classContext = currentUser.getClassname();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT ao.id, COALESCE(NULLIF(ao.title_override, ''), at.title) AS title, " +
                        "COALESCE(sa.latest_total_score, sa.best_total_score, 0) AS my_score, " +
                        "stats.class_avg, stats.total_students, stats.above_count, stats.equal_count " +
                        "FROM class_member cm " +
                        "JOIN teaching_class tc ON tc.id = cm.class_id " +
                        "JOIN assignment_offering ao ON ao.class_id = cm.class_id " +
                        "JOIN assignment_template at ON at.id = ao.template_id " +
                        "LEFT JOIN student_assignment sa ON sa.offering_id = ao.id AND sa.student_id = cm.student_id " +
                        "LEFT JOIN ( " +
                        "  SELECT sa2.offering_id, AVG(COALESCE(sa2.latest_total_score, sa2.best_total_score, 0)) AS class_avg, " +
                        "         COUNT(*) AS total_students, " +
                        "         SUM(CASE WHEN COALESCE(sa2.latest_total_score, sa2.best_total_score, 0) > COALESCE(my.latest_total_score, my.best_total_score, 0) THEN 1 ELSE 0 END) AS above_count, " +
                        "         SUM(CASE WHEN COALESCE(sa2.latest_total_score, sa2.best_total_score, 0) = COALESCE(my.latest_total_score, my.best_total_score, 0) THEN 1 ELSE 0 END) AS equal_count " +
                        "  FROM student_assignment sa2 " +
                        "  JOIN assignment_offering ao2 ON ao2.id = sa2.offering_id " +
                        "  JOIN class_member cm2 ON cm2.class_id = ao2.class_id AND cm2.student_id = sa2.student_id AND cm2.member_status = 'ACTIVE' " +
                        "  LEFT JOIN student_assignment my ON my.offering_id = sa2.offering_id AND my.student_id = ?1 " +
                        "  GROUP BY sa2.offering_id, COALESCE(my.latest_total_score, my.best_total_score, 0) " +
                        ") stats ON stats.offering_id = ao.id " +
                        "WHERE cm.student_id = ?1 " +
                        "AND cm.member_status = 'ACTIVE' " +
                        "AND (?2 IS NULL OR ?2 = '' " +
                        "OR tc.name = CONVERT(?2 USING utf8mb4) COLLATE utf8mb4_unicode_ci " +
                        "OR tc.class_code = CONVERT(?2 USING utf8mb4) COLLATE utf8mb4_unicode_ci " +
                        "OR tc.course_name = CONVERT(?2 USING utf8mb4) COLLATE utf8mb4_unicode_ci) " +
                        "AND ao.status <> 'ARCHIVED' " +
                        "ORDER BY cm.class_id, COALESCE(ao.seq_no, 999999), ao.id"
        ).setParameter(1, studentProfileId)
                .setParameter(2, classContext)
                .getResultList();

        List<Map<String, Object>> experiments = new ArrayList<>();
        double myTotalScore = 0;
        double classTotalAvg = 0;

        for (Object[] row : rows) {
            long offeringId = ((Number) row[0]).longValue();
            double myScore = toDouble(row[2]);
            double classAvg = toDouble(row[3]);
            int totalStudents = toInt(row[4]);
            int above = toInt(row[5]);
            int equal = toInt(row[6]);
            double percentile = totalStudents > 0
                    ? round2((1.0 - (above + equal * 0.5) / totalStudents) * 100)
                    : 50;
            double median = classMedian(offeringId);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("experimentId", offeringId);
            item.put("name", row[1]);
            item.put("myScore", round2(myScore));
            item.put("classAvg", round2(classAvg));
            item.put("classMedian", round2(median));
            item.put("percentile", percentile);
            item.put("totalStudents", totalStudents);
            item.put("diff", round2(myScore - classAvg));
            experiments.add(item);

            myTotalScore += myScore;
            classTotalAvg += classAvg;
        }

        int experimentCount = experiments.size();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("avgMyScore", experimentCount > 0 ? round2(myTotalScore / experimentCount) : 0);
        summary.put("avgClassScore", experimentCount > 0 ? round2(classTotalAvg / experimentCount) : 0);
        summary.put("experimentCount", experimentCount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("experiments", experiments);
        return ApiResponse.of(result);
    }

    @GetMapping("/{studentId}/experiments/{experimentId}")
    public ApiResponse<Map<String, Object>> getStudentExperimentDetail(
            @PathVariable String studentId,
            @PathVariable int experimentId,
            HttpServletRequest request
    ) {
        Long studentProfileId = resolveStudentProfileId(
                studentSessionResolver.requireAuthorizedStudentId(studentId, request));
        UserEntity currentUser = studentSessionResolver.requireStudent(request);
        String classContext = currentUser.getClassname();
        long offeringId = experimentId;

        // The membership join is the access boundary: a student can only inspect offerings in active classes they belong to.
        @SuppressWarnings("unchecked")
        List<Object[]> assignmentRows = em.createNativeQuery(
                "SELECT COALESCE(sa.latest_total_score, sa.best_total_score, 0) AS my_score, " +
                        "stats.class_avg, stats.class_max, stats.class_min, stats.total_students " +
                        "FROM class_member cm " +
                        "JOIN teaching_class tc ON tc.id = cm.class_id " +
                        "JOIN assignment_offering ao ON ao.class_id = cm.class_id AND ao.id = ?1 " +
                        "LEFT JOIN student_assignment sa ON sa.offering_id = ao.id AND sa.student_id = cm.student_id " +
                        "LEFT JOIN ( " +
                        "  SELECT sa2.offering_id, AVG(COALESCE(sa2.latest_total_score, sa2.best_total_score, 0)) AS class_avg, " +
                        "         MAX(COALESCE(sa2.latest_total_score, sa2.best_total_score, 0)) AS class_max, " +
                        "         MIN(COALESCE(sa2.latest_total_score, sa2.best_total_score, 0)) AS class_min, COUNT(*) AS total_students " +
                        "  FROM student_assignment sa2 " +
                        "  JOIN assignment_offering ao2 ON ao2.id = sa2.offering_id " +
                        "  JOIN class_member cm2 ON cm2.class_id = ao2.class_id AND cm2.student_id = sa2.student_id AND cm2.member_status = 'ACTIVE' " +
                        "  WHERE sa2.offering_id = ?1 " +
                        "  GROUP BY sa2.offering_id " +
                        ") stats ON stats.offering_id = ao.id " +
                        "WHERE cm.student_id = ?2 AND cm.member_status = 'ACTIVE' " +
                        "AND (?3 IS NULL OR ?3 = '' " +
                        "OR tc.name = CONVERT(?3 USING utf8mb4) COLLATE utf8mb4_unicode_ci " +
                        "OR tc.class_code = CONVERT(?3 USING utf8mb4) COLLATE utf8mb4_unicode_ci " +
                        "OR tc.course_name = CONVERT(?3 USING utf8mb4) COLLATE utf8mb4_unicode_ci) " +
                        "AND ao.status <> 'ARCHIVED'"
        ).setParameter(1, offeringId)
                .setParameter(2, studentProfileId)
                .setParameter(3, classContext)
                .getResultList();

        Map<String, Object> overview = new LinkedHashMap<>();
        if (!assignmentRows.isEmpty()) {
            Object[] row = assignmentRows.get(0);
            overview.put("myScore", round2(toDouble(row[0])));
            overview.put("classAvg", round2(toDouble(row[1])));
            overview.put("classMax", round2(toDouble(row[2])));
            overview.put("classMin", round2(toDouble(row[3])));
            overview.put("totalStudents", toInt(row[4]));
        } else {
            overview.put("myScore", 0);
            overview.put("classAvg", 0);
            overview.put("classMax", 0);
            overview.put("classMin", 0);
            overview.put("totalStudents", 0);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> problemRows = em.createNativeQuery(
                "SELECT ap.problem_no, ap.title, ap.max_score, COALESCE(sps.best_score, 0) AS my_score, " +
                        "stats.class_avg " +
                        "FROM class_member cm " +
                        "JOIN teaching_class tc ON tc.id = cm.class_id " +
                        "JOIN assignment_offering ao ON ao.class_id = cm.class_id AND ao.id = ?1 " +
                        "JOIN assignment_problem ap ON ap.offering_id = ao.id AND ap.status = 'ACTIVE' " +
                        "LEFT JOIN student_problem_state sps ON sps.offering_id = ap.offering_id AND sps.problem_id = ap.id AND sps.student_id = cm.student_id " +
                        "LEFT JOIN ( " +
                        "  SELECT sps2.problem_id, AVG(COALESCE(sps2.best_score, 0)) AS class_avg " +
                        "  FROM student_problem_state sps2 " +
                        "  JOIN assignment_offering ao2 ON ao2.id = sps2.offering_id " +
                        "  JOIN class_member cm2 ON cm2.class_id = ao2.class_id AND cm2.student_id = sps2.student_id AND cm2.member_status = 'ACTIVE' " +
                        "  WHERE sps2.offering_id = ?1 " +
                        "  GROUP BY sps2.problem_id " +
                        ") stats ON stats.problem_id = ap.id " +
                        "WHERE cm.student_id = ?2 AND cm.member_status = 'ACTIVE' " +
                        "AND (?3 IS NULL OR ?3 = '' " +
                        "OR tc.name = CONVERT(?3 USING utf8mb4) COLLATE utf8mb4_unicode_ci " +
                        "OR tc.class_code = CONVERT(?3 USING utf8mb4) COLLATE utf8mb4_unicode_ci " +
                        "OR tc.course_name = CONVERT(?3 USING utf8mb4) COLLATE utf8mb4_unicode_ci) " +
                        "AND ao.status <> 'ARCHIVED' " +
                        "ORDER BY ap.sort_order, ap.problem_no"
        ).setParameter(1, offeringId)
                .setParameter(2, studentProfileId)
                .setParameter(3, classContext)
                .getResultList();

        List<Map<String, Object>> problems = new ArrayList<>();
        for (Object[] row : problemRows) {
            double fullScore = toDouble(row[2]);
            double myScore = toDouble(row[3]);
            double classAvg = toDouble(row[4]);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", row[0]);
            item.put("type", row[1]);
            item.put("fullScore", round2(fullScore));
            item.put("myScore", round2(myScore));
            item.put("classAvg", round2(classAvg));
            item.put("diff", round2(myScore - classAvg));
            problems.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overview", overview);
        result.put("problems", problems);
        return ApiResponse.of(result);
    }

    private double classMedian(long offeringId) {
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery(
                "SELECT COALESCE(sa.best_total_score, sa.latest_total_score, 0) AS score " +
                        "FROM student_assignment sa " +
                        "JOIN assignment_offering ao ON ao.id = sa.offering_id " +
                        "JOIN class_member cm ON cm.class_id = ao.class_id AND cm.student_id = sa.student_id AND cm.member_status = 'ACTIVE' " +
                        "WHERE sa.offering_id = ?1 ORDER BY score"
        ).setParameter(1, offeringId).getResultList();

        if (rows.isEmpty()) {
            return 0;
        }
        int size = rows.size();
        if (size % 2 == 0) {
            return (toDouble(rows.get(size / 2 - 1)) + toDouble(rows.get(size / 2))) / 2.0;
        }
        return toDouble(rows.get(size / 2));
    }

    private Long resolveStudentProfileId(String studentNo) {
        @SuppressWarnings("unchecked")
        List<Number> profileIds = em.createNativeQuery(
                "SELECT id FROM student_profile WHERE student_no = ?1 LIMIT 1"
        ).setParameter(1, studentNo).getResultList();
        if (profileIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "student profile not found");
        }
        return profileIds.get(0).longValue();
    }

    private static int toInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
