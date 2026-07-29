package com.tap.backend.service;

import com.tap.backend.academic.config.SkillTreeConfig;
import com.tap.backend.domain.classroom.TeachingClassEntity;
import com.tap.backend.repo.TeachingClassRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class TeacherClassProfileService {

    private static final double TIER_A_MIN = 70.0;
    private static final double TIER_B_MIN = 40.0;
    private static final double WEAK_SCORE_MAX = 40.0;

    @PersistenceContext
    private EntityManager em;

    private final TeachingClassRepository classRepository;
    private final SkillTreeConfig skillTreeConfig;

    public TeacherClassProfileService(TeachingClassRepository classRepository, SkillTreeConfig skillTreeConfig) {
        this.classRepository = classRepository;
        this.skillTreeConfig = skillTreeConfig;
    }

    public Map<String, Object> getProfile(Long teacherId, Long classId) {
        if (classId == null || classId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "classId is required");
        }
        TeachingClassEntity teachingClass = classRepository.findById(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "class not found"));
        if (!teacherId.equals(teachingClass.getTeacherId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }

        List<StudentProfile> roster = loadRosterProfiles(classId);
        List<ExperimentStat> stats = roster.isEmpty() ? List.of() : loadLegacyExperimentStats(roster);
        Map<String, StudentProfile> rosterByStudentNo = new LinkedHashMap<>();
        for (StudentProfile student : roster) {
            rosterByStudentNo.put(student.studentNo(), student);
        }

        Map<String, List<ExperimentStat>> byStudent = new LinkedHashMap<>();
        Map<String, String> studentNames = new LinkedHashMap<>();
        for (StudentProfile student : roster) {
            studentNames.put(student.studentNo(), student.studentName());
            byStudent.put(student.studentNo(), new ArrayList<>());
        }
        for (ExperimentStat row : stats) {
            if (!rosterByStudentNo.containsKey(row.studentId())) {
                continue;
            }
            byStudent.computeIfAbsent(row.studentId(), ignored -> new ArrayList<>()).add(row);
            if (!hasText(studentNames.get(row.studentId()))) {
                studentNames.put(row.studentId(), textOr(row.studentName(), row.studentId()));
            }
        }

        Map<String, Map<String, Double>> studentDimScores = new LinkedHashMap<>();
        Map<String, Double> studentOverallScores = new LinkedHashMap<>();
        for (Map.Entry<String, List<ExperimentStat>> entry : byStudent.entrySet()) {
            String studentId = entry.getKey();
            Map<Integer, ExperimentStat> expMap = new LinkedHashMap<>();
            for (ExperimentStat row : entry.getValue()) {
                expMap.put(row.experimentId(), row);
            }

            Map<Integer, Double> expMastery = new LinkedHashMap<>();
            for (Map.Entry<Integer, ExperimentStat> expEntry : expMap.entrySet()) {
                ExperimentStat stat = expEntry.getValue();
                expMastery.put(expEntry.getKey(), computeLegacyMastery(stat.totalSubmissions(), stat.acCount(), stat.questionCount()));
            }

            Map<String, Double> dimScores = new LinkedHashMap<>();
            double totalScore = 0.0;
            int dimCount = 0;
            for (Map.Entry<String, List<Integer>> dim : skillTreeConfig.getDimensions().entrySet()) {
                double sum = 0.0;
                int count = 0;
                for (int experimentId : dim.getValue()) {
                    if (expMastery.containsKey(experimentId)) {
                        sum += expMastery.get(experimentId);
                        count++;
                    }
                }
                double average = count > 0 ? sum / count : 0.0;
                double rounded = round(average);
                dimScores.put(dim.getKey(), rounded);
                totalScore += average;
                dimCount++;
            }
            studentDimScores.put(studentId, dimScores);
            studentOverallScores.put(studentId, dimCount > 0 ? round(totalScore / dimCount) : 0.0);
        }

        Map<String, Object> classDimAvg = new LinkedHashMap<>();
        Map<String, Integer> classDimWeakCount = new LinkedHashMap<>();
        for (String dim : skillTreeConfig.getDimensions().keySet()) {
            double sum = 0.0;
            int count = 0;
            int weakCount = 0;
            for (Map<String, Double> studentScores : studentDimScores.values()) {
                double value = studentScores.getOrDefault(dim, 0.0);
                sum += value;
                count++;
                if (value < WEAK_SCORE_MAX) {
                    weakCount++;
                }
            }
            classDimAvg.put(dim, count > 0 ? round(sum / count) : 0.0);
            classDimWeakCount.put(dim, weakCount);
        }

        List<Map<String, Object>> weakRanking = new ArrayList<>();
        int totalStudents = studentDimScores.size();
        for (String dim : skillTreeConfig.getDimensions().keySet()) {
            int weakCount = classDimWeakCount.getOrDefault(dim, 0);
            weakRanking.add(linkedMap(
                    "dimension", dim,
                    "avgScore", classDimAvg.get(dim),
                    "weakCount", weakCount,
                    "weakRatio", totalStudents > 0 ? round((double) weakCount / totalStudents * 100.0) : 0.0
            ));
        }
        weakRanking.sort((a, b) -> Double.compare(toDouble(b.get("weakRatio")), toDouble(a.get("weakRatio"))));

        List<Map<String, Object>> tierA = new ArrayList<>();
        List<Map<String, Object>> tierB = new ArrayList<>();
        List<Map<String, Object>> tierC = new ArrayList<>();
        for (Map.Entry<String, Double> entry : studentOverallScores.entrySet()) {
            Map<String, Object> student = linkedMap(
                    "studentId", entry.getKey(),
                    "studentName", studentNames.getOrDefault(entry.getKey(), entry.getKey()),
                    "overallScore", entry.getValue()
            );
            if (entry.getValue() >= TIER_A_MIN) {
                tierA.add(student);
            } else if (entry.getValue() >= TIER_B_MIN) {
                tierB.add(student);
            } else {
                tierC.add(student);
            }
        }
        Comparator<Map<String, Object>> byScoreDesc = (a, b) -> Double.compare(toDouble(b.get("overallScore")), toDouble(a.get("overallScore")));
        tierA.sort(byScoreDesc);
        tierB.sort(byScoreDesc);
        tierC.sort(byScoreDesc);

        Map<String, Object> tiers = new LinkedHashMap<>();
        tiers.put("A", linkedMap("label", "优秀 (≥70)", "count", tierA.size(), "students", tierA));
        tiers.put("B", linkedMap("label", "中等 (40-69)", "count", tierB.size(), "students", tierB));
        tiers.put("C", linkedMap("label", "需关注 (<40)", "count", tierC.size(), "students", tierC));

        return linkedMap(
                "scope", linkedMap(
                        "teacherId", teacherId,
                        "classId", teachingClass.getId(),
                        "className", teachingClass.getName(),
                        "courseId", teachingClass.getCourseId(),
                        "courseName", teachingClass.getCourseName()
                ),
                "quality", linkedMap(
                        "status", "LEGACY_CLASS_PROFILE",
                        "rosterCount", roster.size(),
                        "scoreSource", "submit_situation",
                        "rosterSource", "class_member",
                        "generatedAt", Instant.now().toString()
                ),
                "className", teachingClass.getName(),
                "totalStudents", totalStudents,
                "dimensionAvg", classDimAvg,
                "weakRanking", weakRanking,
                "tiers", tiers,
                "dimensions", new ArrayList<>(skillTreeConfig.getDimensions().keySet())
        );
    }

    @SuppressWarnings("unchecked")
    private List<StudentProfile> loadRosterProfiles(Long classId) {
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT sp.student_no, sp.real_name " +
                        "FROM class_member cm " +
                        "JOIN student_profile sp ON sp.id = cm.student_id " +
                        "WHERE cm.class_id = :classId " +
                        "AND cm.member_status = 'ACTIVE' " +
                        "AND sp.status = 'ACTIVE' " +
                        "ORDER BY sp.student_no, sp.id")
                .setParameter("classId", classId)
                .getResultList();
        List<StudentProfile> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            String studentNo = text(row[0]);
            if (hasText(studentNo)) {
                result.add(new StudentProfile(studentNo, textOr(row[1], studentNo)));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<ExperimentStat> loadLegacyExperimentStats(List<StudentProfile> roster) {
        List<String> studentNos = roster.stream().map(StudentProfile::studentNo).filter(TeacherClassProfileService::hasText).toList();
        if (studentNos.isEmpty()) return List.of();
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT ss.student_id, MAX(ss.student_name), ss.experiment_id, MAX(ss.experiment_name), " +
                        "COUNT(*) AS total_submissions, " +
                        "SUM(CASE WHEN UPPER(ss.situation) IN ('C', 'AC', 'ACCEPTED') THEN 1 ELSE 0 END) AS ac_count, " +
                        "COUNT(DISTINCT ss.serial_number) AS question_count " +
                        "FROM submit_situation ss " +
                        "WHERE ss.student_id IN (:studentNos) " +
                        "GROUP BY ss.student_id, ss.experiment_id " +
                        "ORDER BY ss.student_id, ss.experiment_id")
                .setParameter("studentNos", studentNos)
                .getResultList();
        List<ExperimentStat> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Integer experimentId = toInteger(row[2]);
            if (experimentId != null) {
                result.add(new ExperimentStat(
                        text(row[0]),
                        text(row[1]),
                        experimentId,
                        toLong(row[4]),
                        toLong(row[5]),
                        toLong(row[6])
                ));
            }
        }
        return result;
    }

    private double computeLegacyMastery(long totalSubmissions, long acCount, long questionCount) {
        if (totalSubmissions == 0) return 0.0;
        double correctRate = (double) acCount / totalSubmissions;
        double avgAttempts = questionCount > 0 ? (double) totalSubmissions / questionCount : totalSubmissions;
        double efficiency = Math.max(0.0, 1.0 - (avgAttempts - 1.0) / 20.0);
        return round((0.6 * correctRate + 0.2 + 0.2 * efficiency) * 100.0);
    }

    private record StudentProfile(String studentNo, String studentName) {}
    private record ExperimentStat(String studentId, String studentName, int experimentId, long totalSubmissions, long acCount, long questionCount) {}

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static String textOr(Object value, String fallback) {
        String result = text(value);
        return hasText(result) ? result : fallback;
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return 0L;
        return Long.parseLong(String.valueOf(value));
    }

    private static Integer toInteger(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return null;
        return Integer.valueOf(String.valueOf(value));
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return 0.0;
        return Double.parseDouble(String.valueOf(value));
    }

    private static Map<String, Object> linkedMap(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
