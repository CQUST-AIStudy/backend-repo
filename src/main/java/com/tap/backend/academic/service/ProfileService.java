package com.tap.backend.academic.service;

import com.tap.backend.academic.config.SkillTreeConfig;
import com.tap.backend.academic.dao.ProfileDao;
import com.tap.backend.academic.dao.UserDao;
import com.tap.backend.academic.entity.UserEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.Cacheable;

@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    @Autowired
    private ProfileDao profileDao;

    @Autowired
    private UserDao userDao;

    @Autowired
    private UserService userService;

    @Autowired
    private SkillTreeConfig skillTreeConfig;

    @PersistenceContext
    private EntityManager em;

    @Value("${tap.ai.openai.api-key:}")
    private String deepseekApiKey;

    @Value("${tap.ai.openai.base-url:https://api.deepseek.com/v1}")
    private String deepseekBaseUrl;

    @Value("${tap.ai.openai.model:deepseek-chat}")
    private String deepseekModel;

    private static final Gson gson = new Gson();

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    // ========== 学生画像 ==========

    // 判题状态归一化（中英文统一），学生画像聚合与薄弱题查询共用，避免不同 SQL 各自维护状态列表
    private static final String STU_AC =
            "(UPPER(TRIM(COALESCE(spa.judge_status,''))) IN ('C','AC','ACCEPTED','CORRECT','PASS','PASSED','100') " +
            "OR TRIM(COALESCE(spa.judge_status,'')) IN ('\u6ee1\u5206','\u6210\u529f','\u901a\u8fc7','\u7b54\u6848\u6b63\u786e'))";
    private static final String STU_CE =
            "(UPPER(TRIM(COALESCE(spa.judge_status,''))) IN ('CE','COMPILE_ERROR','COMPILATION_ERROR','E1') " +
            "OR TRIM(COALESCE(spa.judge_status,'')) = '\u7f16\u8bd1\u9519\u8bef')";
    private static final String STU_WA =
            "(UPPER(TRIM(COALESCE(spa.judge_status,''))) IN ('WA','WRONG_ANSWER','MULTIPLE_ERROR','E3') " +
            "OR TRIM(COALESCE(spa.judge_status,'')) = '\u7b54\u6848\u9519\u8bef')";
    private static final String STU_TLE =
            "(UPPER(TRIM(COALESCE(spa.judge_status,''))) IN ('TLE','TIMEOUT','TIME_LIMIT_EXCEEDED','E5') " +
            "OR TRIM(COALESCE(spa.judge_status,'')) = '\u8fd0\u884c\u8d85\u65f6')";
    private static final String STU_PARTIAL =
            "(UPPER(TRIM(COALESCE(spa.judge_status,''))) IN ('PA','PARTIAL_ACCEPTED','PARTIAL_CORRECT','P','PARTIAL') " +
            "OR TRIM(COALESCE(spa.judge_status,'')) = '\u90e8\u5206\u6b63\u786e')";

    /** 学生画像范围：锁定单个教学班，避免跨班/跨课程/跨学期混算。 */
    private record StudentProfileScope(Long studentProfileId, String studentNo, Long classId,
                                       String className, String courseName, boolean ambiguous) {
    }

    /** 学生每 offering 的聚合统计（typed，取代遗留 Map<String,Object>）。 */
    private record StudentOfferingStat(long offeringId, String experimentName,
                                       long totalSubmissions, long acCount, long compileErrorCount,
                                       long wrongAnswerCount, long timeoutCount, long partialCount,
                                       long questionCount, int orderRank) {
    }

    @org.springframework.cache.annotation.Cacheable(
            value = "studentProfile",
            key = "#studentNo",
            // 错误响应(无 scope/无数据)不入缓存，避免后续拿到数据后仍命中旧错误
            unless = "#result == null || #result.containsKey('error')")
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> getStudentProfile(String studentNo) {
        return computeStudentProfile(studentNo, false);
    }

    @org.springframework.cache.annotation.CachePut(
            value = "studentProfile",
            key = "#studentNo",
            unless = "#result == null || #result.containsKey('error')")
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> refreshFeedback(String studentNo) {
        // refresh 复用同一画像计算，仅绕过 AI 反馈缓存；@CachePut 用最新结果替换缓存
        return computeStudentProfile(studentNo, true);
    }

    private Map<String, Object> computeStudentProfile(String studentNo, boolean forceRefresh) {
        StudentProfileScope scope = resolveStudentScope(studentNo);
        if (scope == null) {
            // 用可变 Map：/api/profile/me 控制器会 putIfAbsent 补字段，不可变 Map.of 会抛 UnsupportedOperationException
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "\u8be5\u5b66\u751f\u65e0\u63d0\u4ea4\u8bb0\u5f55");
            err.put("studentId", studentNo);
            err.put("diagnostic", diagnoseStudentNo(studentNo));
            return err;
        }
        List<StudentOfferingStat> stats = loadUnifiedStudentStats(scope);
        if (stats.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "\u8be5\u5b66\u751f\u65e0\u63d0\u4ea4\u8bb0\u5f55");
            err.put("studentId", studentNo);
            err.put("scope", Map.of("classId", scope.classId(), "className", scope.className()));
            err.put("diagnostic", diagnoseStudentNo(studentNo));
            return err;
        }
        Map<Long, String> offeringDimension = loadScopedOfferingDimensions(scope.classId());
        int totalOfferings = countScopedOfferings(scope.classId());

        // 分配稳定顺序（用于趋势前后半划分与折线图排序）
        stats = assignOrderRanks(stats);
        Map<Long, StudentOfferingStat> statByOffering = new LinkedHashMap<>();
        for (StudentOfferingStat s : stats) {
            statByOffering.put(s.offeringId(), s);
        }

        Map<String, Object> studentInfo = loadScopedStudentInfo(scope);
        String name = studentInfo != null && studentInfo.get("name") != null
                ? String.valueOf(studentInfo.get("name")) : "\u540c\u5b66";

        Map<Long, Double> mastery = new LinkedHashMap<>();
        Map<Long, Double> confidence = new LinkedHashMap<>();
        for (StudentOfferingStat s : stats) {
            mastery.put(s.offeringId(), computeMastery(s));
            confidence.put(s.offeringId(), computeConfidence(s));
        }

        HalfSplit halfSplit = computeHalfSplit(stats);
        Map<String, Object> radar = computeRadar(mastery, confidence, offeringDimension);
        List<Map<String, Object>> skillTree = buildSkillTree(mastery, confidence, statByOffering, offeringDimension);
        List<Map<String, Object>> weaknesses = findWeaknesses(mastery, confidence, statByOffering, offeringDimension, scope);
        Map<String, Object> trend = computeTrend(mastery, statByOffering, halfSplit);
        List<Map<String, Object>> patterns = detectPatterns(statByOffering, mastery, halfSplit);
        Map<String, Object> overview = computeOverview(statByOffering, totalOfferings);

        String feedback = generateFeedback(studentNo, name, radar, weaknesses, patterns, overview, trend, forceRefresh);

        // 未分类 offering 数（DimensionClassifier 无法归类或维度映射缺失），计入质量标记但不静默丢弃
        int unclassified = (int) stats.stream()
                .map(s -> offeringDimension.get(s.offeringId()))
                .filter(d -> d == null || DimensionClassifier.UNCLASSIFIED.equals(d))
                .count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("studentId", studentNo);
        result.put("studentName", name);
        result.put("className", scope.className());
        result.put("overview", overview);
        result.put("radar", radar);
        result.put("skillTree", skillTree);
        result.put("weaknesses", weaknesses);
        result.put("trend", trend);
        result.put("patterns", patterns);
        result.put("feedback", feedback);
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("source", "student_problem_attempt");
        quality.put("classId", scope.classId());
        quality.put("className", scope.className());
        quality.put("courseName", scope.courseName());
        quality.put("scopeAmbiguous", scope.ambiguous());
        quality.put("dimensionMapping", "DYNAMIC_BY_KNOWLEDGE_LEAF");
        quality.put("unclassifiedOfferingCount", unclassified);
        result.put("quality", quality);
        return result;
    }

    /**
     * 解析学生画像范围：选一个教学班（优先 ACTIVE；若无 ACTIVE 班则回退任意状态，避免归档班学生彻底无数据）。
     * 按 PUBLISHED/CLOSED 的 offering 数最多的优先，并列时记 ambiguous。
     */
    @SuppressWarnings("unchecked")
    private StudentProfileScope resolveStudentScope(String studentNo) {
        if (studentNo == null || studentNo.isBlank()) {
            return null;
        }
        // 优先 ACTIVE 教学班
        List<Object[]> rows = queryScope(studentNo, true);
        // 无 ACTIVE 班则回退任意状态（归档班学生也能出数据）
        if (rows.isEmpty()) {
            rows = queryScope(studentNo, false);
        }
        if (rows.isEmpty()) {
            return null;
        }
        Object[] top = rows.get(0);
        long topCount = asLong(top[5]);
        boolean ambiguous = rows.size() > 1 && asLong(((Object[]) rows.get(1))[5]) == topCount;
        return new StudentProfileScope(
                asLong(top[0]), studentNo, asLong(top[1]),
                textOr(top[2], ""), textOr(top[4], ""), ambiguous);
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> queryScope(String studentNo, boolean activeOnly) {
        String statusFilter = activeOnly ? " AND tc.status = 'ACTIVE'" : "";
        String sql = """
                SELECT sp.id AS spid, tc.id AS class_id, tc.name AS class_name,
                       tc.course_id, tc.course_name,
                       (SELECT COUNT(DISTINCT ao.id) FROM assignment_offering ao
                         WHERE ao.class_id = tc.id
                           AND ao.status IN ('PUBLISHED','CLOSED')) AS offering_count,
                       cm.joined_at AS joined_at
                FROM student_profile sp
                JOIN class_member cm ON cm.student_id = sp.id AND cm.member_status = 'ACTIVE'
                JOIN teaching_class tc ON tc.id = cm.class_id
                WHERE sp.student_no = :studentNo AND sp.status <> 'DELETED'
                """ + statusFilter + """
                ORDER BY offering_count DESC, cm.joined_at DESC, tc.id DESC
                """;
        return em.createNativeQuery(sql)
                .setParameter("studentNo", studentNo)
                .getResultList();
    }

    /** 诊断：当画像无数据时，暴露学生在统一核心各表的关联状态，便于定位链路断点。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> diagnoseStudentNo(String studentNo) {
        Map<String, Object> diag = new LinkedHashMap<>();
        try {
            Object spCount = em.createNativeQuery(
                    "SELECT COUNT(*) FROM student_profile WHERE student_no = :s AND status <> 'DELETED'")
                    .setParameter("s", studentNo).getSingleResult();
            diag.put("studentProfileExists", asLong(spCount) > 0);
        } catch (Exception ignored) { }
        try {
            Object cmCount = em.createNativeQuery(
                    "SELECT COUNT(*) FROM class_member cm JOIN student_profile sp ON sp.id = cm.student_id " +
                    "WHERE sp.student_no = :s")
                    .setParameter("s", studentNo).getSingleResult();
            diag.put("classMemberRows", asLong(cmCount));
        } catch (Exception ignored) { }
        try {
            List<Object[]> classes = em.createNativeQuery(
                    "SELECT tc.id, tc.name, tc.status, cm.member_status, " +
                    "(SELECT COUNT(DISTINCT ao.id) FROM assignment_offering ao WHERE ao.class_id = tc.id) AS off_cnt " +
                    "FROM student_profile sp JOIN class_member cm ON cm.student_id = sp.id " +
                    "JOIN teaching_class tc ON tc.id = cm.class_id WHERE sp.student_no = :s")
                    .setParameter("s", studentNo).getResultList();
            List<Map<String, Object>> classList = new ArrayList<>();
            for (Object[] r : classes) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("classId", asLong(r[0]));
                c.put("className", r[1]);
                c.put("classStatus", r[2]);
                c.put("memberStatus", r[3]);
                c.put("offeringCount", asLong(r[4]));
                classList.add(c);
            }
            diag.put("classes", classList);
        } catch (Exception ignored) { }
        try {
            Object attCount = em.createNativeQuery(
                    "SELECT COUNT(*) FROM student_problem_attempt spa " +
                    "JOIN student_profile sp ON sp.id = spa.student_id WHERE sp.student_no = :s")
                    .setParameter("s", studentNo).getSingleResult();
            diag.put("attemptRows", asLong(attCount));
        } catch (Exception ignored) { }
        return diag;
    }


    /** 统一数据源：按 offering 聚合该学生在选定班级的提交（含中英文状态归一化）。 */
    @SuppressWarnings("unchecked")
    private List<StudentOfferingStat> loadUnifiedStudentStats(StudentProfileScope scope) {
        String sql = ("SELECT spa.offering_id AS offering_id, " +
                "COALESCE(NULLIF(TRIM(ao.title_override),''), at.title) AS experiment_name, " +
                "COUNT(*) AS total_submissions, " +
                "SUM(CASE WHEN %s THEN 1 ELSE 0 END) AS ac_count, " +
                "SUM(CASE WHEN %s THEN 1 ELSE 0 END) AS compile_error_count, " +
                "SUM(CASE WHEN %s THEN 1 ELSE 0 END) AS wrong_answer_count, " +
                "SUM(CASE WHEN %s THEN 1 ELSE 0 END) AS timeout_count, " +
                "SUM(CASE WHEN %s THEN 1 ELSE 0 END) AS partial_count, " +
                "COUNT(DISTINCT spa.problem_id) AS question_count " +
                "FROM student_problem_attempt spa " +
                "JOIN assignment_offering ao ON ao.id = spa.offering_id " +
                "JOIN assignment_template at ON at.id = ao.template_id " +
                "JOIN student_profile sp ON sp.id = spa.student_id " +
                "WHERE sp.student_no = :studentNo AND ao.class_id = :classId AND sp.status <> 'DELETED' " +
                "AND ao.status IN ('PUBLISHED','CLOSED') " +
                "GROUP BY spa.offering_id, ao.title_override, at.title")
                .formatted(STU_AC, STU_CE, STU_WA, STU_TLE, STU_PARTIAL);
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("studentNo", scope.studentNo())
                .setParameter("classId", scope.classId())
                .getResultList();
        List<StudentOfferingStat> stats = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            stats.add(new StudentOfferingStat(
                    asLong(row[0]), textOr(row[1], ""),
                    asLong(row[2]), asLong(row[3]), asLong(row[4]),
                    asLong(row[5]), asLong(row[6]), asLong(row[7]), asLong(row[8]), 0));
        }
        return stats;
    }

    /** 选定班级内每个 offering 的动态维度（复用班级画像的归类逻辑）。 */
    private Map<Long, String> loadScopedOfferingDimensions(Long classId) {
        return loadOfferingDimensions(classId);
    }

    /** 选定班级内可参与的 offering 总数（PUBLISHED/CLOSED），作为 totalExperiments 分母。 */
    private int countScopedOfferings(Long classId) {
        Object cnt = em.createNativeQuery(
                "SELECT COUNT(DISTINCT ao.id) FROM assignment_offering ao " +
                "WHERE ao.class_id = :classId AND ao.status IN ('PUBLISHED','CLOSED')")
                .setParameter("classId", classId)
                .getSingleResult();
        return (int) asLong(cnt);
    }

    /** 按稳定全序分配 orderRank：offering_id 升序作为兜底全序，支撑前后半划分与折线图排序。 */
    private List<StudentOfferingStat> assignOrderRanks(List<StudentOfferingStat> stats) {
        List<StudentOfferingStat> sorted = new ArrayList<>(stats);
        sorted.sort(Comparator.comparingLong(StudentOfferingStat::offeringId));
        List<StudentOfferingStat> result = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            StudentOfferingStat s = sorted.get(i);
            result.add(new StudentOfferingStat(s.offeringId(), s.experimentName(),
                    s.totalSubmissions(), s.acCount(), s.compileErrorCount(),
                    s.wrongAnswerCount(), s.timeoutCount(), s.partialCount(),
                    s.questionCount(), i));
        }
        return result;
    }

    /** 学期前后半划分：按 orderRank 中点分；证据不足(<2 个 offering)时 sufficient=false。 */
    private HalfSplit computeHalfSplit(List<StudentOfferingStat> stats) {
        int n = stats.size();
        if (n < 2) {
            return new HalfSplit(Set.of(), Set.of(), false);
        }
        Set<Long> first = new LinkedHashSet<>();
        Set<Long> second = new LinkedHashSet<>();
        int mid = n / 2;
        for (StudentOfferingStat s : stats) {
            if (s.orderRank() < mid) {
                first.add(s.offeringId());
            } else {
                second.add(s.offeringId());
            }
        }
        return new HalfSplit(first, second, true);
    }

    private record HalfSplit(Set<Long> firstHalf, Set<Long> secondHalf, boolean sufficient) {
    }

    private double computeMastery(StudentOfferingStat s) {
        long total = s.totalSubmissions();
        if (total == 0) return 0;
        double correctRate = (double) s.acCount() / total;
        double compileErrRate = (double) s.compileErrorCount() / total;
        double avgAttemptsPerQ = s.questionCount() > 0 ? (double) total / s.questionCount() : total;
        double efficiencyScore = Math.max(0, 1.0 - (avgAttemptsPerQ - 1) / 20.0);
        double m = 0.6 * correctRate + 0.2 * (1.0 - compileErrRate) + 0.2 * efficiencyScore;
        return Math.round(m * 1000.0) / 10.0;
    }

    private double computeConfidence(StudentOfferingStat s) {
        return Math.min(1.0, s.totalSubmissions() / 10.0);
    }

    private Map<String, Object> computeRadar(Map<Long, Double> mastery, Map<Long, Double> confidence,
                                             Map<Long, String> offeringDimension) {
        // 维度顺序：SkillTreeConfig 的标准六维，未命中的维度得 0 分
        Map<String, double[]> accum = new LinkedHashMap<>();
        for (String dim : skillTreeConfig.getDimensions().keySet()) {
            accum.put(dim, new double[3]); // [sumScore, sumConf, count]
        }
        for (var e : mastery.entrySet()) {
            String dim = displayDimension(offeringDimension.get(e.getKey()));
            double[] acc = accum.computeIfAbsent(dim, k -> new double[3]);
            acc[0] += e.getValue();
            acc[1] += confidence.getOrDefault(e.getKey(), 0.0);
            acc[2] += 1;
        }
        List<String> dimensions = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        List<Double> confidences = new ArrayList<>();
        for (var e : accum.entrySet()) {
            dimensions.add(e.getKey());
            double[] acc = e.getValue();
            scores.add(acc[2] > 0 ? Math.round(acc[0] / acc[2] * 10.0) / 10.0 : 0);
            confidences.add(acc[2] > 0 ? Math.round(acc[1] / acc[2] * 100.0) / 100.0 : 0);
        }
        Map<String, Object> radar = new LinkedHashMap<>();
        radar.put("dimensions", dimensions);
        radar.put("scores", scores);
        radar.put("confidences", confidences);
        return radar;
    }

    private List<Map<String, Object>> buildSkillTree(Map<Long, Double> mastery, Map<Long, Double> confidence,
                                                     Map<Long, StudentOfferingStat> statByOffering,
                                                     Map<Long, String> offeringDimension) {
        Map<String, List<Long>> byDim = new LinkedHashMap<>();
        for (Long offId : mastery.keySet()) {
            byDim.computeIfAbsent(displayDimension(offeringDimension.get(offId)), k -> new ArrayList<>()).add(offId);
        }
        List<Map<String, Object>> tree = new ArrayList<>();
        for (String dim : skillTreeConfig.getDimensions().keySet()) {
            List<Long> offIds = byDim.getOrDefault(dim, List.of());
            List<Map<String, Object>> children = new ArrayList<>();
            double sumScore = 0;
            int count = 0;
            for (Long offId : offIds) {
                StudentOfferingStat s = statByOffering.get(offId);
                double m = mastery.getOrDefault(offId, 0.0);
                double c = confidence.getOrDefault(offId, 0.0);
                Map<String, Object> child = new LinkedHashMap<>();
                child.put("experimentId", offId);
                child.put("name", s != null ? experimentName(s) : ("\u5b9e\u9a8c " + offId));
                child.put("mastery", m);
                child.put("confidence", c);
                child.put("level", m >= 70 ? "good" : m >= 40 ? "medium" : "weak");
                if (s != null) {
                    child.put("totalSubmissions", s.totalSubmissions());
                    child.put("acCount", s.acCount());
                    child.put("questionCount", s.questionCount());
                }
                children.add(child);
                sumScore += m;
                count++;
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("dimension", dim);
            node.put("description", skillTreeConfig.getDescriptions().get(dim));
            node.put("avgMastery", count > 0 ? Math.round(sumScore / count * 10.0) / 10.0 : 0);
            double avg = count > 0 ? sumScore / count : 0;
            node.put("level", avg >= 70 ? "good" : avg >= 40 ? "medium" : "weak");
            node.put("children", children);
            tree.add(node);
        }
        return tree;
    }

    private List<Map<String, Object>> findWeaknesses(Map<Long, Double> mastery, Map<Long, Double> confidence,
                                                     Map<Long, StudentOfferingStat> statByOffering,
                                                     Map<Long, String> offeringDimension,
                                                     StudentProfileScope scope) {
        List<Map.Entry<Long, Double>> sorted = mastery.entrySet().stream()
                .filter(e -> confidence.getOrDefault(e.getKey(), 0.0) >= 0.3)
                .sorted(Comparator.comparingDouble(Map.Entry::getValue))
                .limit(3)
                .collect(Collectors.toList());
        List<Map<String, Object>> weaknesses = new ArrayList<>();
        for (var entry : sorted) {
            long offId = entry.getKey();
            double m = entry.getValue();
            StudentOfferingStat s = statByOffering.get(offId);
            String dimension = displayDimension(offeringDimension.get(offId));
            List<Map<String, Object>> weakQs = loadStudentWeakQuestions(scope, offId);

            Map<String, Object> w = new LinkedHashMap<>();
            w.put("experimentId", offId);
            w.put("experimentName", s != null ? experimentName(s) : ("\u5b9e\u9a8c " + offId));
            w.put("dimension", dimension);
            w.put("mastery", m);
            w.put("confidence", confidence.getOrDefault(offId, 0.0));
            Map<String, Object> evidence = new LinkedHashMap<>();
            if (s != null) {
                evidence.put("totalSubmissions", s.totalSubmissions());
                evidence.put("acCount", s.acCount());
                evidence.put("compileErrors", s.compileErrorCount());
                evidence.put("wrongAnswers", s.wrongAnswerCount());
                evidence.put("questionCount", s.questionCount());
            }
            w.put("evidence", evidence);
            w.put("weakQuestions", weakQs != null ? weakQs.stream().limit(3).collect(Collectors.toList()) : List.of());
            weaknesses.add(w);
        }
        return weaknesses;
    }

    /** 薄弱题：按 problem 聚合，返回 serial_number=problem_no 以保持前端契约(AbilityProfile 读 q.serial_number)。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadStudentWeakQuestions(StudentProfileScope scope, long offeringId) {
        String sql = ("SELECT ap.problem_no AS serial_number, ap.id AS problem_id, ap.title AS problem_title, " +
                "COUNT(*) AS attempts, " +
                "SUM(CASE WHEN %s THEN 1 ELSE 0 END) AS ac_count, " +
                "MAX(spa.score) AS best_score, COALESCE(ap.max_score,0) AS max_score " +
                "FROM student_problem_attempt spa " +
                "JOIN assignment_problem ap ON ap.id = spa.problem_id AND ap.offering_id = spa.offering_id " +
                "JOIN student_profile sp ON sp.id = spa.student_id " +
                "WHERE sp.student_no = :studentNo AND spa.offering_id = :offeringId " +
                "GROUP BY ap.problem_no, ap.id, ap.title, ap.max_score " +
                "ORDER BY (SUM(CASE WHEN %s THEN 1 ELSE 0 END) / COUNT(*)) ASC, attempts DESC, ap.problem_no")
                .formatted(STU_AC, STU_AC);
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("studentNo", scope.studentNo())
                .setParameter("offeringId", offeringId)
                .getResultList();
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("serial_number", row[0]);
            q.put("problem_id", asLong(row[1]));
            q.put("problem_title", textOr(row[2], ""));
            q.put("attempts", asLong(row[3]));
            q.put("ac_count", asLong(row[4]));
            q.put("best_score", row[5]);
            q.put("max_score", asLong(row[6]));
            result.add(q);
        }
        return result;
    }

    private Map<String, Object> computeTrend(Map<Long, Double> mastery,
                                             Map<Long, StudentOfferingStat> statByOffering,
                                             HalfSplit halfSplit) {
        double firstHalf = 0, secondHalf = 0;
        int c1 = 0, c2 = 0;
        for (var e : mastery.entrySet()) {
            if (halfSplit.firstHalf().contains(e.getKey())) { firstHalf += e.getValue(); c1++; }
            else if (halfSplit.secondHalf().contains(e.getKey())) { secondHalf += e.getValue(); c2++; }
        }
        double avg1 = c1 > 0 ? firstHalf / c1 : 0;
        double avg2 = c2 > 0 ? secondHalf / c2 : 0;
        double diff = avg2 - avg1;
        // 证据不足(单 offering 或前后半有一侧无数据)时不给上升/下降强结论
        String direction = !halfSplit.sufficient() || c1 == 0 || c2 == 0 ? "flat"
                : diff > 5 ? "up" : diff < -5 ? "down" : "flat";

        List<Map<String, Object>> series = new ArrayList<>();
        mastery.entrySet().stream()
                .sorted(Comparator.comparingLong(Map.Entry::getKey))
                .forEach(e -> {
                    StudentOfferingStat s = statByOffering.get(e.getKey());
                    series.add(Map.of(
                            "experimentId", e.getKey(),
                            "name", s != null ? experimentName(s) : ("\u5b9e\u9a8c " + e.getKey()),
                            "mastery", e.getValue()));
                });

        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("direction", direction);
        trend.put("firstHalfAvg", Math.round(avg1 * 10.0) / 10.0);
        trend.put("secondHalfAvg", Math.round(avg2 * 10.0) / 10.0);
        trend.put("change", Math.round(diff * 10.0) / 10.0);
        trend.put("series", series);
        trend.put("sufficient", halfSplit.sufficient() && c1 > 0 && c2 > 0);
        return trend;
    }

    private List<Map<String, Object>> detectPatterns(Map<Long, StudentOfferingStat> statByOffering,
                                                     Map<Long, Double> mastery, HalfSplit halfSplit) {
        List<Map<String, Object>> patterns = new ArrayList<>();
        long totalSubmissions = 0, totalAc = 0, totalCompileErr = 0, totalQuestions = 0;
        for (StudentOfferingStat s : statByOffering.values()) {
            totalSubmissions += s.totalSubmissions();
            totalAc += s.acCount();
            totalCompileErr += s.compileErrorCount();
            totalQuestions += s.questionCount();
        }
        List<Double> masteryValues = new ArrayList<>(mastery.values());

        double avgAttemptsPerQ = totalQuestions > 0 ? (double) totalSubmissions / totalQuestions : 0;
        if (avgAttemptsPerQ > 8) {
            patterns.add(Map.of(
                    "tag", "\u9ad8\u91cd\u505a\u578b",
                    "description", "\u5e73\u5747\u6bcf\u9898\u63d0\u4ea4" + String.format("%.1f", avgAttemptsPerQ) + "\u6b21\uff0c\u5efa\u8bae\u5148\u7406\u6e05\u601d\u8def\u518d\u7f16\u7801",
                    "evidence", "\u603b\u63d0\u4ea4" + totalSubmissions + "\u6b21\uff0c\u8986\u76d6" + totalQuestions + "\u9898"));
        }

        double compileErrRate = totalSubmissions > 0 ? (double) totalCompileErr / totalSubmissions : 0;
        if (compileErrRate > 0.3) {
            patterns.add(Map.of(
                    "tag", "\u7f16\u7801\u57fa\u7840\u8584\u5f31",
                    "description", "\u7f16\u8bd1\u9519\u8bef\u5360\u6bd4" + String.format("%.0f%%", compileErrRate * 100) + "\uff0c\u5efa\u8bae\u52a0\u5f3aC\u8bed\u8a00\u8bed\u6cd5\u7ec3\u4e60",
                    "evidence", "\u7f16\u8bd1\u9519\u8bef" + totalCompileErr + "/" + totalSubmissions + "\u6b21"));
        }

        if (masteryValues.size() >= 3) {
            double mean = masteryValues.stream().mapToDouble(d -> d).average().orElse(0);
            double variance = masteryValues.stream().mapToDouble(d -> (d - mean) * (d - mean)).average().orElse(0);
            double stddev = Math.sqrt(variance);
            if (stddev > 15) {
                patterns.add(Map.of(
                        "tag", "\u9ad8\u6ce2\u52a8\u578b",
                        "description", "\u5404\u5b9e\u9a8c\u8868\u73b0\u5dee\u5f02\u5927(\u6807\u51c6\u5dee" + String.format("%.1f", stddev) + ")\uff0c\u90e8\u5206\u77e5\u8bc6\u70b9\u638c\u63e1\u4e0d\u5747",
                        "evidence", "mastery\u8303\u56f4: " + String.format("%.0f", Collections.min(masteryValues))
                                + "~" + String.format("%.0f", Collections.max(masteryValues))));
            }
        }

        // 稳定进步：复用同一 halfSplit，不再独立分半
        double first = 0, second = 0;
        int c1 = 0, c2 = 0;
        for (var e : mastery.entrySet()) {
            if (halfSplit.firstHalf().contains(e.getKey())) { first += e.getValue(); c1++; }
            else if (halfSplit.secondHalf().contains(e.getKey())) { second += e.getValue(); c2++; }
        }
        if (halfSplit.sufficient() && c1 >= 2 && c2 >= 2 && (second / c2 - first / c1) > 10) {
            patterns.add(Map.of(
                    "tag", "\u7a33\u5b9a\u8fdb\u6b65",
                    "description", "\u540e\u534a\u5b66\u671f\u8868\u73b0\u660e\u663e\u63d0\u5347\uff0c\u5b66\u4e60\u6001\u5ea6\u79ef\u6781",
                    "evidence", "\u524d\u534a\u5b66\u671f\u5747\u5206" + String.format("%.1f", first / c1)
                            + " \u2192 \u540e\u534a\u5b66\u671f" + String.format("%.1f", second / c2)));
        }

        if (patterns.isEmpty()) {
            patterns.add(Map.of(
                    "tag", "\u8868\u73b0\u5747\u8861",
                    "description", "\u5404\u65b9\u9762\u8868\u73b0\u8f83\u4e3a\u5747\u8861\uff0c\u7ee7\u7eed\u4fdd\u6301",
                    "evidence", "\u603b\u4f53AC\u7387" + String.format("%.0f%%", totalSubmissions > 0 ? (double) totalAc / totalSubmissions * 100 : 0)));
        }
        return patterns;
    }

    private Map<String, Object> computeOverview(Map<Long, StudentOfferingStat> statByOffering, int totalOfferings) {
        long totalSub = 0, totalAc = 0;
        for (StudentOfferingStat s : statByOffering.values()) {
            totalSub += s.totalSubmissions();
            totalAc += s.acCount();
        }
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalSubmissions", totalSub);
        overview.put("totalAc", totalAc);
        overview.put("overallAcRate", totalSub > 0 ? Math.round((double) totalAc / totalSub * 1000.0) / 10.0 : 0);
        overview.put("experimentsCovered", statByOffering.size());
        overview.put("totalExperiments", totalOfferings);
        return overview;
    }

    private String experimentName(StudentOfferingStat s) {
        return s.experimentName() != null && !s.experimentName().isBlank()
                ? s.experimentName() : ("\u5b9e\u9a8c " + s.offeringId());
    }

    /** 未分类(null/UNCLASSIFIED)在展示层映射到"综合"，避免 offering 静默从雷达/技能树消失。 */
    private String displayDimension(String dim) {
        return (dim == null || DimensionClassifier.UNCLASSIFIED.equals(dim))
                ? DimensionClassifier.FALLBACK_DIMENSION : dim;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadScopedStudentInfo(StudentProfileScope scope) {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT sp.student_no, sp.real_name FROM student_profile sp WHERE sp.id = :spid")
                .setParameter("spid", scope.studentProfileId())
                .getResultList();
        if (rows.isEmpty()) return null;
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("student_id", rows.get(0)[0]);
        info.put("name", rows.get(0)[1]);
        info.put("class_name", scope.className());
        return info;
    }

    /**
     * 生成反馈：forceRefresh=false 时先查 DB 缓存(profile_json 一致则复用)；
     * 否则调 DeepSeek 并写缓存。
     */
    private String generateFeedback(String studentNo, String name,
                                    Map<String, Object> radar, List<Map<String, Object>> weaknesses,
                                    List<Map<String, Object>> patterns, Map<String, Object> overview,
                                    Map<String, Object> trend, boolean forceRefresh) {
        String profileJson = buildProfileJson(name, radar, weaknesses, patterns, overview, trend);
        if (!forceRefresh && studentNo != null) {
            try {
                Map<String, Object> cached = profileDao.getAiFeedback(studentNo);
                if (cached != null && cached.get("feedback") != null) {
                    String cachedFeedback = String.valueOf(cached.get("feedback"));
                    String cachedProfileJson = cached.get("profile_json") != null
                            ? String.valueOf(cached.get("profile_json")) : "";
                    if (!cachedFeedback.isBlank() && profileJson.equals(cachedProfileJson)) {
                        return cachedFeedback;
                    }
                }
            } catch (Exception e) {
                log.warn("\u67e5\u8be2\u7f13\u5b58\u5931\u8d25: {}", e.getMessage());
            }
        }
        if (deepseekApiKey != null && !deepseekApiKey.isBlank()) {
            try {
                String llmFeedback = callDeepSeek(profileJson, name, overview);
                if (llmFeedback != null && !llmFeedback.isBlank()) {
                    if (studentNo != null) {
                        try {
                            profileDao.saveAiFeedback(studentNo, llmFeedback, profileJson);
                        } catch (Exception e) {
                            log.warn("\u4fdd\u5b58\u7f13\u5b58\u5931\u8d25: {}", e.getMessage());
                        }
                    }
                    return llmFeedback;
                }
            } catch (Exception e) {
                log.warn("DeepSeek\u8c03\u7528\u5931\u8d25\uff0c\u4f7f\u7528\u6a21\u677f: {}", e.getMessage());
            }
        }
        return buildTemplateFeedback(name, radar, weaknesses, patterns);
    }

    private String buildProfileJson(String name, Map<String, Object> radar,
                                    List<Map<String, Object>> weaknesses, List<Map<String, Object>> patterns,
                                    Map<String, Object> overview, Map<String, Object> trend) {
        Map<String, Object> profileSummary = new LinkedHashMap<>();
        profileSummary.put("studentName", name);
        profileSummary.put("overallAcRate", overview.get("overallAcRate"));
        profileSummary.put("totalSubmissions", overview.get("totalSubmissions"));
        profileSummary.put("totalAc", overview.get("totalAc"));
        profileSummary.put("experimentsCovered", overview.get("experimentsCovered"));
        profileSummary.put("totalExperiments", overview.get("totalExperiments"));
        profileSummary.put("radarScores", radar);
        profileSummary.put("trend", Map.of(
                "direction", trend.get("direction"),
                "firstHalfAvg", trend.get("firstHalfAvg"),
                "secondHalfAvg", trend.get("secondHalfAvg"),
                "change", trend.get("change")));
        List<Map<String, Object>> weakSummary = new ArrayList<>();
        for (var w : weaknesses) {
            Map<String, Object> ws = new LinkedHashMap<>();
            ws.put("name", w.get("experimentName"));
            ws.put("dimension", w.get("dimension"));
            ws.put("mastery", w.get("mastery"));
            if (w.get("evidence") != null) ws.put("evidence", w.get("evidence"));
            weakSummary.add(ws);
        }
        profileSummary.put("weaknesses", weakSummary);
        profileSummary.put("patterns", patterns);
        return gson.toJson(profileSummary);
    }

    private String callDeepSeek(String profileJson, String studentName, Map<String, Object> overview) throws Exception {
        int totalExp = ((Number) overview.getOrDefault("totalExperiments", 0)).intValue();
        String systemPrompt = "\u4f60\u662f\u4e00\u4f4d\u7ecf\u9a8c\u4e30\u5bcc\u7684\u9ad8\u6821\u6570\u636e\u7ed3\u6784\u8bfe\u7a0b\u6559\u5b66\u52a9\u624b\uff0c\u8d1f\u8d23\u6839\u636e\u5b66\u751f\u5728PTA\u7f16\u7a0b\u5e73\u53f0\u4e0a\u7684\u63d0\u4ea4\u6570\u636e\uff0c\u751f\u6210\u4e2a\u6027\u5316\u7684\u5b66\u4e60\u5206\u6790\u62a5\u544a\u3002\n\n"
                + "## \u6570\u636e\u80cc\u666f\n"
                + "- \u8bfe\u7a0b\uff1a\u6570\u636e\u7ed3\u6784\uff08C\u8bed\u8a00\u5b9e\u73b0\uff09\n"
                + "- \u5e73\u53f0\uff1aPTA\uff08Programming Teaching Assistant\uff09\u5728\u7ebf\u7f16\u7a0b\u5e73\u53f0\n"
                + "- \u6570\u636e\u6765\u6e90\uff1a\u5b66\u751f\u5728\u672c\u73ed\u7ea7" + totalExp + "\u4e2a\u5b9e\u9a8c/\u4f5c\u4e1a\u4e2d\u7684\u6240\u6709\u63d0\u4ea4\u8bb0\u5f55\uff0c\u5305\u62ecAC\uff08\u901a\u8fc7\uff09\u3001\u7f16\u8bd1\u9519\u8bef\u3001\u7b54\u6848\u9519\u8bef\u3001\u8d85\u65f6\u7b49\u72b6\u6001\n"
                + "- \u80fd\u529b\u7ef4\u5ea6\uff1a\u7ebf\u6027\u8868\u3001\u6808\u4e0e\u961f\u5217\u3001\u6811\u3001\u56fe\u3001\u54c8\u5e0c\u3001\u7efc\u5408\uff0c\u6bcf\u4e2a\u7ef4\u5ea6\u5305\u542b\u82e5\u5e72\u5b9e\u9a8c\n"
                + "- mastery\u5206\u6570\uff1a0-100\uff0c\u7531AC\u7387(60%)\u3001\u7f16\u8bd1\u6b63\u786e\u7387(20%)\u3001\u63d0\u4ea4\u6548\u7387(20%)\u52a0\u6743\u8ba1\u7b97\n\n"
                + "## \u8f93\u51fa\u683c\u5f0f\u8981\u6c42\uff08\u4e25\u683c\u9075\u5b88\uff09\n"
                + "\u8bf7\u6309\u4ee5\u4e0b\u7ed3\u6784\u8f93\u51fa\uff0c\u4f7f\u7528\u4e2d\u6587\uff1a\n\n"
                + "\u3010\u603b\u8bc4\u3011\uff082-3\u53e5\u8bdd\uff0c\u6982\u62ec\u8be5\u5b66\u751f\u7684\u6574\u4f53\u5b66\u4e60\u60c5\u51b5\uff0c\u5fc5\u987b\u5f15\u7528\u5177\u4f53\u7684AC\u7387\u3001\u63d0\u4ea4\u6b21\u6570\u7b49\u6570\u636e\uff09\n\n"
                + "\u3010\u8584\u5f31\u5206\u6790\u3011\uff08\u9488\u5bf9\u6570\u636e\u4e2dmastery\u6700\u4f4e\u76842-3\u4e2a\u5b9e\u9a8c/\u7ef4\u5ea6\uff0c\u5206\u6790\u53ef\u80fd\u7684\u539f\u56e0\uff0c\u5fc5\u987b\u5f15\u7528\u5177\u4f53\u5b9e\u9a8c\u540d\u79f0\u548c\u5206\u6570\uff09\n\n"
                + "\u3010\u5b66\u4e60\u5efa\u8bae\u3011\n"
                + "1. \uff08\u7b2c\u4e00\u6761\u5efa\u8bae\uff0c\u5fc5\u987b\u9488\u5bf9\u5177\u4f53\u77e5\u8bc6\u70b9\uff0c\u5982'\u94fe\u8868\u6307\u9488\u64cd\u4f5c'\u3001'\u9012\u5f52\u904d\u5386'\u7b49\uff0c\u7ed9\u51fa\u53ef\u6267\u884c\u7684\u7ec3\u4e60\u65b9\u6cd5\uff09\n"
                + "2. \uff08\u7b2c\u4e8c\u6761\u5efa\u8bae\uff0c\u9488\u5bf9\u5b66\u4e60\u4e60\u60ef\u6216\u7b56\u7565\uff0c\u5982\u7f16\u8bd1\u9519\u8bef\u591a\u5219\u5efa\u8bae\u5148\u624b\u5199\u4f2a\u4ee3\u7801\uff09\n"
                + "3. \uff08\u7b2c\u4e09\u6761\u5efa\u8bae\uff0c\u9488\u5bf9\u63d0\u5347\u65b9\u5411\uff0c\u7ed3\u5408\u8d8b\u52bf\u6570\u636e\u7ed9\u51fa\u9f13\u52b1\u6216\u8b66\u793a\uff09\n\n"
                + "## \u7ea6\u675f\n"
                + "- \u53ea\u57fa\u4e8e\u63d0\u4f9b\u7684JSON\u6570\u636e\u8fdb\u884c\u5206\u6790\uff0c\u4e0d\u8981\u7f16\u9020\u4efb\u4f55\u6570\u636e\u4e2d\u4e0d\u5b58\u5728\u7684\u4fe1\u606f\n"
                + "- \u5f15\u7528\u6570\u636e\u65f6\u4f7f\u7528\u539f\u59cb\u6570\u503c\uff0c\u4e0d\u8981\u56db\u820d\u4e94\u5165\u6216\u6a21\u7cca\u5316\n"
                + "- \u8bed\u6c14\u53cb\u597d\u3001\u4e13\u4e1a\u3001\u6709\u5efa\u8bbe\u6027\uff0c\u50cf\u4e00\u4f4d\u5173\u5fc3\u5b66\u751f\u7684\u8001\u5e08\n"
                + "- \u603b\u5b57\u6570\u63a7\u5236\u5728300-500\u5b57\u4e4b\u95f4";

        String userPrompt = "\u4ee5\u4e0b\u662f" + studentName + "\u540c\u5b66\u5728\u6570\u636e\u7ed3\u6784\u8bfe\u7a0bPTA\u5e73\u53f0\u4e0a\u7684\u80fd\u529b\u753b\u50cf\u6570\u636e\uff08JSON\u683c\u5f0f\uff09\uff0c\u8bf7\u6839\u636e\u4e0a\u8ff0\u8981\u6c42\u751f\u6210\u5b66\u4e60\u5206\u6790\u62a5\u544a\uff1a\n\n" + profileJson;

        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", deepseekModel);
        reqBody.addProperty("stream", false);
        reqBody.addProperty("max_tokens", 800);
        reqBody.addProperty("temperature", 0.7);

        JsonArray messages = new JsonArray();
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        messages.add(sysMsg);
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messages.add(userMsg);
        reqBody.add("messages", messages);

        okhttp3.RequestBody body = okhttp3.RequestBody.create(
                reqBody.toString(),
                okhttp3.MediaType.parse("application/json; charset=utf-8"));

        Request request = new Request.Builder()
                .url(deepseekBaseUrl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + deepseekApiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("DeepSeek API\u9519\u8bef: {} {}", response.code(),
                        response.body() != null ? response.body().string() : "");
                return null;
            }
            String respStr = response.body() != null ? response.body().string() : "";
            JsonObject respJson = JsonParser.parseString(respStr).getAsJsonObject();
            JsonArray choices = respJson.getAsJsonArray("choices");
            if (choices != null && !choices.isEmpty()) {
                JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                if (message != null && message.has("content")) {
                    return message.get("content").getAsString().trim();
                }
            }
        }
        return null;
    }

    private String buildTemplateFeedback(String name,
                                          Map<String, Object> radar,
                                          List<Map<String, Object>> weaknesses,
                                          List<Map<String, Object>> patterns) {
        List<Double> scores = (List<Double>) radar.get("scores");
        List<String> dims = (List<String>) radar.get("dimensions");

        int maxIdx = 0, minIdx = 0;
        for (int i = 1; i < scores.size(); i++) {
            if (scores.get(i) > scores.get(maxIdx)) maxIdx = i;
            if (scores.get(i) < scores.get(minIdx)) minIdx = i;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(name).append("\u540c\u5b66\uff0c");
        sb.append("\u4f60\u5728\u300c").append(dims.get(maxIdx)).append("\u300d\u65b9\u9762\u8868\u73b0\u6700\u597d(").append(String.format("%.0f", scores.get(maxIdx))).append("\u5206)\uff0c");
        sb.append("\u300c").append(dims.get(minIdx)).append("\u300d\u9700\u8981\u52a0\u5f3a(").append(String.format("%.0f", scores.get(minIdx))).append("\u5206)\u3002");

        if (!weaknesses.isEmpty()) {
            sb.append("\u5efa\u8bae\u91cd\u70b9\u590d\u4e60\uff1a");
            for (int i = 0; i < weaknesses.size(); i++) {
                if (i > 0) sb.append("\u3001");
                sb.append(weaknesses.get(i).get("experimentName"));
            }
            sb.append("\u3002");
        }

        if (!patterns.isEmpty()) {
            sb.append("\u5b66\u4e60\u7279\u5f81\uff1a").append(patterns.get(0).get("tag")).append("\u3002");
        }

        return sb.toString();
    }

    // ========== 当前用户个人信息（通用：admin / teacher / student） ==========

    public Map<String, Object> getCurrentUserProfile(UserEntity sessionUser) {
        // 从数据库重新加载，避免 session 里是旧数据
        UserEntity user = userDao.findByUsername(sessionUser.getUsername());
        if (user == null) {
            user = sessionUser;
        }
        String role = normalizeRole(user.getRole());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("name", user.getUsername());
        result.put("username", user.getUsername());
        String realName = userService.getRealNameByUsernum(user.getUsernum());
        result.put("realName", realName != null ? realName : user.getUsername());
        result.put("role", role);
        result.put("email", emptyIfNull(user.getEmail()));
        result.put("phone", emptyIfNull(user.getPhone()));
        result.put("department", emptyIfNull(user.getDepartment()));
        result.put("avatar", "");
        if (user.getUsernum() != null && !user.getUsernum().isBlank()) {
            result.put("usernum", user.getUsernum());
        }
        if (user.getClassname() != null && !user.getClassname().isBlank()) {
            result.put("class", user.getClassname());
        }
        return result;
    }

    public Map<String, Object> updateCurrentUserProfile(UserEntity sessionUser, Map<String, String> data) {
        // 用 username 重新加载完整用户记录。
        // 注意：不能用 sessionUser.getId() 查 tap_user，因为 user 表和 tap_user 表的 ID 不一致。
        UserEntity user = userDao.findByUsername(sessionUser.getUsername());
        if (user == null) {
            throw new RuntimeException("user not found");
        }

        String name = data.get("name");
        String email = data.get("email");
        String phone = data.get("phone");
        String department = data.get("department");

        if (name != null && !name.isBlank()) {
            user.setUsername(name.trim());
        }
        if (email != null) {
            user.setEmail(email.trim());
        }
        if (phone != null) {
            user.setPhone(phone.trim());
        }
        if (department != null) {
            user.setDepartment(department.trim());
        }

        userDao.updateUser(user);

        return getCurrentUserProfile(user);
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "student";
        }
        return role.trim().toLowerCase();
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    // ========== 班级画像 ==========

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Map<String, Object> getClassProfile(Long classId, String className, String courseName) {
        // 统一数据源：从 student_problem_attempt 按 (学生, offering) 聚合，
        // 取代遗留的 submit_situation，确保 PTA 同步数据进入画像。
        List<AttemptStat> allStats = loadUnifiedAttemptStats(classId);
        List<Map<String, Object>> students = profileDao.getAllStudents(classId, className);
        if (students == null) {
            students = new ArrayList<>();
        }

        // 动态维度：按本班实际 offering 的实验名 + 题目知识点归类，
        // 取代硬编码的 SkillTreeConfig 维度→experiment_id 映射。
        Map<Long, String> offeringDimension = loadOfferingDimensions(classId);

        long totalSubmissions = allStats.stream()
                .mapToLong(AttemptStat::totalSubmissions)
                .sum();

        // 按学生分组
        Map<String, List<AttemptStat>> byStudent = new LinkedHashMap<>();
        Map<String, String> studentNames = new LinkedHashMap<>();
        for (Map<String, Object> student : students) {
            String sid = String.valueOf(student.get("student_id"));
            String sname = student.get("name") != null ? String.valueOf(student.get("name")) : sid;
            studentNames.put(sid, sname);
            byStudent.putIfAbsent(sid, new ArrayList<>());
        }
        for (AttemptStat row : allStats) {
            String sid = row.studentNo();
            byStudent.computeIfAbsent(sid, k -> new ArrayList<>()).add(row);
            if (!studentNames.containsKey(sid) || studentNames.get(sid) == null || studentNames.get(sid).isBlank()) {
                studentNames.put(sid, row.studentName() != null && !row.studentName().isBlank() ? row.studentName() : sid);
            }
        }

        // 计算每个学生每个维度的分数（维度来自动态 offeringDimension）
        Map<String, Map<String, Double>> studentDimScores = new LinkedHashMap<>();
        Map<String, Double> studentOverallScores = new LinkedHashMap<>();

        for (var entry : byStudent.entrySet()) {
            String sid = entry.getKey();
            List<AttemptStat> rows = entry.getValue();

            if (!studentNames.containsKey(sid) || studentNames.get(sid) == null || studentNames.get(sid).isBlank()) {
                String sname = rows.isEmpty() || rows.get(0).studentName() == null || rows.get(0).studentName().isBlank()
                        ? sid
                        : rows.get(0).studentName();
                studentNames.put(sid, sname);
            }

            // 按 offering 聚合 mastery（公式与学生画像 computeMastery 对齐）
            Map<Long, Double> offeringMastery = new LinkedHashMap<>();
            for (AttemptStat s : rows) {
                long total = s.totalSubmissions();
                long ac = s.acCount();
                long compileErr = s.compileErrorCount();
                long questions = s.questionCount();
                if (total == 0) { offeringMastery.put(s.offeringId(), 0.0); continue; }
                double correctRate = (double) ac / total;
                double compileErrRate = (double) compileErr / total;
                double avgAtt = questions > 0 ? (double) total / questions : total;
                double eff = Math.max(0, 1.0 - (avgAtt - 1) / 20.0);
                double m = 0.6 * correctRate + 0.2 * (1.0 - compileErrRate) + 0.2 * eff;
                offeringMastery.put(s.offeringId(), Math.round(m * 1000.0) / 10.0);
            }

            // 按动态维度归集 offering 分数
            Map<String, double[]> dimAccum = new LinkedHashMap<>();
            for (var oe : offeringMastery.entrySet()) {
                String dim = offeringDimension.getOrDefault(oe.getKey(), DimensionClassifier.FALLBACK_DIMENSION);
                double[] acc = dimAccum.computeIfAbsent(dim, k -> new double[2]);
                acc[0] += oe.getValue();
                acc[1] += 1;
            }

            Map<String, Double> dimScores = new LinkedHashMap<>();
            double totalScore = 0;
            int dimCount = 0;
            for (var de : dimAccum.entrySet()) {
                double avg = de.getValue()[0] / de.getValue()[1];
                dimScores.put(de.getKey(), Math.round(avg * 10.0) / 10.0);
                totalScore += avg;
                dimCount++;
            }
            if (dimCount > 0) {
                studentDimScores.put(sid, dimScores);
                studentOverallScores.put(sid, Math.round(totalScore / dimCount * 10.0) / 10.0);
            }
        }

        // 1. 班级各维度平均分（维度来自本班实际数据，不再硬编码；"未分类"不计入维度输出）
        Map<String, Double> classDimAvg = new LinkedHashMap<>();
        Map<String, Integer> classDimWeakCount = new LinkedHashMap<>();
        Map<String, Integer> classDimEvidenceCount = new LinkedHashMap<>();
        List<String> activeDimensions = new ArrayList<>();
        Set<String> seenDimensions = new LinkedHashSet<>();
        int unmappedOfferingCount = 0;
        for (String d : offeringDimension.values()) {
            if (DimensionClassifier.UNCLASSIFIED.equals(d)) {
                unmappedOfferingCount++;
                continue;
            }
            seenDimensions.add(d);
        }
        for (String dim : seenDimensions) {
            double sum = 0; int cnt = 0; int weakCnt = 0;
            for (var ds : studentDimScores.values()) {
                if (!ds.containsKey(dim)) {
                    continue;
                }
                double v = ds.get(dim);
                sum += v; cnt++;
                if (v < 40) weakCnt++;
            }
            if (cnt > 0) {
                activeDimensions.add(dim);
                classDimAvg.put(dim, Math.round(sum / cnt * 10.0) / 10.0);
                classDimWeakCount.put(dim, weakCnt);
                classDimEvidenceCount.put(dim, cnt);
            }
        }

        // 2. 薄弱维度排行（按低分人数占比排序）
        List<Map<String, Object>> weakRanking = new ArrayList<>();
        int totalStudents = byStudent.size();
        int analyzedStudents = studentOverallScores.size();
        for (String dim : activeDimensions) {
            Map<String, Object> wr = new LinkedHashMap<>();
            wr.put("dimension", dim);
            wr.put("avgScore", classDimAvg.get(dim));
            wr.put("weakCount", classDimWeakCount.get(dim));
            int evidenceCount = classDimEvidenceCount.getOrDefault(dim, 0);
            wr.put("weakRatio", evidenceCount > 0
                    ? Math.round((double) classDimWeakCount.get(dim) / evidenceCount * 1000.0) / 10.0
                    : 0);
            weakRanking.add(wr);
        }
        weakRanking.sort((a, b) -> Double.compare((double) b.get("weakRatio"), (double) a.get("weakRatio")));

        // 3. ABC分层
        List<Map<String, Object>> tierA = new ArrayList<>();
        List<Map<String, Object>> tierB = new ArrayList<>();
        List<Map<String, Object>> tierC = new ArrayList<>();
        for (var entry : studentOverallScores.entrySet()) {
            String sid = entry.getKey();
            double score = entry.getValue();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("studentId", sid);
            info.put("studentName", studentNames.get(sid));
            info.put("overallScore", score);
            if (score >= 70) tierA.add(info);
            else if (score >= 40) tierB.add(info);
            else tierC.add(info);
        }
        tierA.sort((a, b) -> Double.compare((double) b.get("overallScore"), (double) a.get("overallScore")));
        tierB.sort((a, b) -> Double.compare((double) b.get("overallScore"), (double) a.get("overallScore")));
        tierC.sort((a, b) -> Double.compare((double) b.get("overallScore"), (double) a.get("overallScore")));

        Map<String, Object> tiers = new LinkedHashMap<>();
        tiers.put("A", Map.of("label", "优秀 (≥70)", "count", tierA.size(), "students", tierA));
        tiers.put("B", Map.of("label", "中等 (40-69)", "count", tierB.size(), "students", tierB));
        tiers.put("C", Map.of("label", "需关注 (<40)", "count", tierC.size(), "students", tierC));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("classId", classId);
        result.put("className", className);
        result.put("courseName", courseName);
        result.put("totalStudents", totalStudents);
        result.put("analyzedStudents", analyzedStudents);
        result.put("totalSubmissions", totalSubmissions);
        result.put("dimensionAvg", classDimAvg);
        result.put("weakRanking", weakRanking);
        result.put("tiers", tiers);
        result.put("dimensions", activeDimensions);
        // 标注数据来源与质量，便于排查与可信度判断
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("status", "UNIFIED_ATTEMPT_PROFILE");
        quality.put("scoreSource", "student_problem_attempt");
        quality.put("rosterSource", "class_member");
        quality.put("dimensionMapping", "DYNAMIC_BY_KNOWLEDGE_LEAF_OR_EXPERIMENT_NAME");
        quality.put("offeringCount", offeringDimension.size());
        quality.put("unmappedOfferingCount", unmappedOfferingCount);
        result.put("quality", quality);
        return result;
    }

    /** 统一数据源：按 (student_profile.id, offering_id) 聚合学生题目级提交。 */
    @SuppressWarnings("unchecked")
    private List<AttemptStat> loadUnifiedAttemptStats(Long classId) {
        if (classId == null) {
            return List.of();
        }
        String sql = """
                SELECT
                  sp.student_no        AS student_no,
                  COALESCE(NULLIF(TRIM(sp.real_name), ''), sp.student_no) AS student_name,
                  spa.offering_id      AS offering_id,
                  COUNT(*)             AS total_submissions,
                  SUM(CASE WHEN (
                        UPPER(TRIM(COALESCE(spa.judge_status, ''))) IN
                        ('C','AC','ACCEPTED','CORRECT','PASS','PASSED','100')
                        OR TRIM(COALESCE(spa.judge_status, '')) IN
                        ('\u6ee1\u5206','\u6210\u529f','\u901a\u8fc7','\u7b54\u6848\u6b63\u786e')
                      ) THEN 1 ELSE 0 END) AS ac_count,
                  SUM(CASE WHEN UPPER(TRIM(COALESCE(spa.judge_status, ''))) IN
                        ('CE','COMPILE_ERROR','COMPILATION_ERROR','E1')
                        OR TRIM(COALESCE(spa.judge_status, '')) = '\u7f16\u8bd1\u9519\u8bef' THEN 1 ELSE 0 END) AS compile_error_count,
                  COUNT(DISTINCT spa.problem_id) AS question_count
                FROM student_problem_attempt spa
                JOIN student_profile sp ON sp.id = spa.student_id
                JOIN assignment_offering ao ON ao.id = spa.offering_id
                JOIN class_member cm
                  ON cm.student_id = spa.student_id
                 AND cm.class_id = ao.class_id
                 AND cm.member_status = 'ACTIVE'
                WHERE ao.class_id = :classId
                  AND sp.status <> 'DELETED'
                GROUP BY sp.student_no, sp.real_name, spa.offering_id
                ORDER BY sp.student_no, spa.offering_id
                """;
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("classId", classId)
                .getResultList();
        List<AttemptStat> stats = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            stats.add(new AttemptStat(
                    textOr(row[0], ""),
                    textOr(row[1], ""),
                    asLong(row[2]),
                    asLong(row[3]),
                    asLong(row[4]),
                    asLong(row[5]),
                    asLong(row[6])));
        }
        return stats;
    }

    /**
     * 动态维度映射：按本班每个 offering 的题目知识点归类到稳定维度词汇。
     *
     * <p>关联约束（修正跨题集误匹配）：题目集内题目号 {@code problem_set_problem_id} 只允许在
     * <strong>同一题目集</strong>内匹配；只有全局题号 {@code pta_global_problem_id} 才可跨题集。
     * 这样避免"题号 7-1"被其他题目集的同号记录误关联知识点。</p>
     */
    @SuppressWarnings("unchecked")
    private Map<Long, String> loadOfferingDimensions(Long classId) {
        Map<Long, String> result = new LinkedHashMap<>();
        if (classId == null) {
            return result;
        }
        String sql = """
                SELECT
                  ap.offering_id AS offering_id,
                  COALESCE(NULLIF(TRIM(ao.title_override), ''), at.title) AS experiment_name,
                  GROUP_CONCAT(DISTINCT COALESCE(NULLIF(TRIM(apd.knowledge_leaf), ''), '') ORDER BY apd.knowledge_leaf SEPARATOR ';') AS knowledge_leaves
                FROM assignment_offering ao
                JOIN assignment_template at ON at.id = ao.template_id
                LEFT JOIN assignment_problem ap ON ap.offering_id = ao.id
                LEFT JOIN pta_problem_detail apd ON apd.id = (
                  SELECT pd.id FROM pta_problem_detail pd
                  WHERE (
                        (ao.pta_problem_set_id IS NOT NULL
                         AND pd.problem_set_id COLLATE utf8mb4_unicode_ci = ao.pta_problem_set_id COLLATE utf8mb4_unicode_ci
                         AND (pd.problem_set_problem_id COLLATE utf8mb4_unicode_ci = ap.source_problem_id COLLATE utf8mb4_unicode_ci
                              OR pd.problem_set_problem_id COLLATE utf8mb4_unicode_ci = ap.problem_no COLLATE utf8mb4_unicode_ci))
                     OR (ap.source_problem_id IS NOT NULL
                         AND pd.pta_global_problem_id COLLATE utf8mb4_unicode_ci = ap.source_problem_id COLLATE utf8mb4_unicode_ci)
                  )
                  ORDER BY CASE
                      WHEN ao.pta_problem_set_id IS NOT NULL
                       AND pd.problem_set_id COLLATE utf8mb4_unicode_ci = ao.pta_problem_set_id COLLATE utf8mb4_unicode_ci THEN 0
                      ELSE 1
                  END, pd.updated_at DESC, pd.id DESC
                  LIMIT 1
                )
                WHERE ao.class_id = :classId
                  AND ao.status IN ('PUBLISHED','CLOSED')
                GROUP BY ap.offering_id, ao.title_override, at.title
                ORDER BY MIN(ap.offering_id)
                """;
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("classId", classId)
                .getResultList();
        for (Object[] row : rows) {
            long offeringId = asLong(row[0]);
            String experimentName = textOr(row[1], "");
            String knowledgeLeaves = textOr(row[2], "");
            result.put(offeringId, DimensionClassifier.classifyOffering(experimentName, knowledgeLeaves));
        }
        return result;
    }

    /** 班级画像统一数据源行：按 (学生学号, offering) 聚合后的提交统计。 */
    private record AttemptStat(
            String studentNo,
            String studentName,
            long offeringId,
            long totalSubmissions,
            long acCount,
            long compileErrorCount,
            long questionCount) {
    }


    private static String textOr(Object value, String fallback) {
        if (value == null) return fallback;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? fallback : s;
    }


    // ========== 技能树接口 ==========

    @Cacheable(value = "skillTree")
    public Map<String, Object> getSkillTreeConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> tree = new ArrayList<>();
        for (var entry : skillTreeConfig.getDimensions().entrySet()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("dimension", entry.getKey());
            node.put("description", skillTreeConfig.getDescriptions().get(entry.getKey()));
            List<Map<String, Object>> children = new ArrayList<>();
            for (int eid : entry.getValue()) {
                children.add(Map.of(
                        "experimentId", eid,
                        "name", skillTreeConfig.getExperimentName(eid)
                ));
            }
            node.put("experiments", children);
            tree.add(node);
        }
        result.put("skillTree", tree);
        result.put("totalDimensions", skillTreeConfig.getDimensions().size());
        result.put("totalExperiments", skillTreeConfig.getExperimentNames().size());
        return result;
    }
}
