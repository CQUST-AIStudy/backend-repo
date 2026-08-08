package com.tap.backend.service.grading;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.grading.PtaGradingResultEntity;
import com.tap.backend.repo.PtaGradingResultRepository;
import com.tap.backend.security.TeacherPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.NotificationService;
import com.tap.backend.service.animation.AnimationAiClient;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * PTA 批改：按 (班级)题集/offering 取 PTA 客观判题结果做分数、AI 生成评语，
 * 按学生 upsert 到 {@code pta_grading_result}，可发布给学生（同 PDF 批改通知通道）。
 * <p>不重跑 rubric 评分流水线；分数来自 PTA 客观判题（best_score / max_score）。
 */
@Service
public class PtaGradingService {

    private static final Logger log = LoggerFactory.getLogger(PtaGradingService.class);
    private static final Set<String> ACCEPTED_STATUS = Set.of("C", "AC", "ACCEPTED", "CORRECT", "PASS", "PASSED", "100");
    private static final int MAX_CODE_CHARS = 2000;
    private static final int MAX_STATEMENT_CHARS = 800;

    private final PtaGradingResultRepository repository;
    private final TeacherPrincipalResolver teacherPrincipalResolver;
    private final AnimationAiClient aiClient;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    public PtaGradingService(PtaGradingResultRepository repository,
                             TeacherPrincipalResolver teacherPrincipalResolver,
                             AnimationAiClient aiClient,
                             NotificationService notificationService,
                             ObjectMapper objectMapper) {
        this.repository = repository;
        this.teacherPrincipalResolver = teacherPrincipalResolver;
        this.aiClient = aiClient;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    // ---- 教师侧 ----------------------------------------------------------

    /** 预览：只算客观分与题目明细，不调 AI、不落库；合并已保存的评语/发布状态。 */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(Long offeringId, UserPrincipal principal) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        OfferingMeta meta = requireOwnedOffering(offeringId, teacherId);
        List<StudentAggregate> aggregates = aggregate(offeringId);
        Map<Long, PtaGradingResultEntity> saved = new LinkedHashMap<>();
        for (PtaGradingResultEntity e : repository.findByOfferingIdOrderByStudentNoAsc(offeringId)) {
            saved.put(e.getStudentId(), e);
        }
        List<Map<String, Object>> students = new ArrayList<>();
        for (StudentAggregate a : aggregates) {
            Map<String, Object> m = a.toSummary();
            PtaGradingResultEntity e = saved.get(a.studentId);
            m.put("comment", e == null ? null : e.getComment());
            m.put("published", e != null && e.isPublished());
            m.put("graded", e != null);
            students.add(m);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("offeringId", offeringId);
        view.put("title", meta.title());
        view.put("problemSetId", meta.problemSetId());
        view.put("studentCount", students.size());
        view.put("students", students);
        return view;
    }

    /** 批量生成：客观分 + AI 评语 → upsert。AI 不可用/失败时降级为客观摘要评语，不阻断。 */
    @Transactional
    public Map<String, Object> generate(Long offeringId, boolean force, UserPrincipal principal) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        OfferingMeta meta = requireOwnedOffering(offeringId, teacherId);
        List<StudentAggregate> aggregates = aggregate(offeringId);

        int generated = 0;
        for (StudentAggregate a : aggregates) {
            PtaGradingResultEntity entity = repository.findByOfferingIdAndStudentId(offeringId, a.studentId)
                    .orElseGet(PtaGradingResultEntity::new);
            // 已发布的记录不覆盖；非 force 且已有评语则跳过（幂等）
            if (entity.isPublished()) {
                continue;
            }
            if (!force && entity.getId() != null && entity.getComment() != null && !entity.getComment().isBlank()) {
                continue;
            }
            entity.setOfferingId(offeringId);
            entity.setProblemSetId(meta.problemSetId());
            entity.setStudentId(a.studentId);
            entity.setStudentNo(a.studentNo);
            entity.setStudentName(a.realName);
            entity.setScore(a.score());
            entity.setAcRate(a.acRate());
            entity.setProblemCount(a.problemCount);
            entity.setAcceptedCount(a.acceptedCount);
            entity.setComment(buildComment(a, meta));
            entity.setDetailJson(writeJson(a.problems));
            entity.setStatus("COMPLETED");
            repository.save(entity);
            generated++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("offeringId", offeringId);
        result.put("generated", generated);
        result.put("total", aggregates.size());
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Long offeringId, UserPrincipal principal) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        requireOwnedOffering(offeringId, teacherId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (PtaGradingResultEntity e : repository.findByOfferingIdOrderByStudentNoAsc(offeringId)) {
            out.add(toView(e, false));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long resultId, UserPrincipal principal) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        PtaGradingResultEntity e = repository.findById(resultId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在"));
        requireOwnedOffering(e.getOfferingId(), teacherId);
        return toView(e, true);
    }

    /** 按学生详情：实时聚合每题 PTA 状态 + 代码 + 题面，不依赖 generate 是否已跑。 */
    @Transactional(readOnly = true)
    public Map<String, Object> studentDetail(Long offeringId, Long studentId, UserPrincipal principal) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        requireOwnedOffering(offeringId, teacherId);
        for (StudentAggregate a : aggregate(offeringId)) {
            if (!a.studentId.equals(studentId)) continue;
            Map<String, Object> v = a.toSummary();
            v.put("problems", a.problems);
            PtaGradingResultEntity e = repository.findByOfferingIdAndStudentId(offeringId, studentId).orElse(null);
            v.put("comment", e == null ? null : e.getComment());
            v.put("published", e != null && e.isPublished());
            return v;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该学生在此题集下无 PTA 数据");
    }

    /** 发布整个 offering 的批改结果给学生（置 published + 站内通知）。 */
    @Transactional
    public Map<String, Object> publish(Long offeringId, UserPrincipal principal) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        OfferingMeta meta = requireOwnedOffering(offeringId, teacherId);
        List<PtaGradingResultEntity> results = repository.findByOfferingIdOrderByStudentNoAsc(offeringId);
        int published = 0;
        for (PtaGradingResultEntity e : results) {
            if (!e.isPublished()) {
                e.setPublished(true);
                e.setPublishedAt(Instant.now());
                repository.save(e);
                published++;
            }
            try {
                String scoreText = e.getScore() == null ? "" : "，得分 " + e.getScore().stripTrailingZeros().toPlainString();
                notificationService.createGradePublished(
                        e.getStudentNo(),
                        offeringId,
                        "PTA 批改结果已发布",
                        "你的「" + safe(meta.title()) + "」PTA 批改结果已发布" + scoreText + "，点击查看得分与教师评语。");
            } catch (Exception ex) {
                log.warn("PTA 批改发布通知失败 offering={} student={}: {}", offeringId, e.getStudentNo(), ex.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("offeringId", offeringId);
        result.put("published", published);
        result.put("total", results.size());
        return result;
    }

    // ---- 学生侧 ----------------------------------------------------------

    /** 学生查看自己已发布的 PTA 批改结果；未发布返回 {published:false}。 */
    @Transactional(readOnly = true)
    public Map<String, Object> getPublishedForStudent(Long offeringId, String studentNo) {
        if (offeringId == null || studentNo == null || studentNo.isBlank()) {
            return Map.of("published", false);
        }
        return repository.findFirstByOfferingIdAndStudentNoAndPublishedTrue(offeringId, studentNo)
                .map(e -> toView(e, true))
                .orElseGet(() -> Map.of("published", false));
    }

    // ---- 内部 ------------------------------------------------------------

    private OfferingMeta requireOwnedOffering(Long offeringId, Long teacherId) {
        try {
            Object[] row = (Object[]) em.createNativeQuery(
                            "SELECT teacher_id, pta_problem_set_id, COALESCE(title_override, '') "
                                    + "FROM assignment_offering WHERE id = ?1")
                    .setParameter(1, offeringId)
                    .getSingleResult();
            Long ownerId = row[0] == null ? null : ((Number) row[0]).longValue();
            if (ownerId == null || !ownerId.equals(teacherId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权批改该题集");
            }
            String problemSetId = row[1] == null ? null : row[1].toString();
            String title = row[2] == null ? "" : row[2].toString();
            return new OfferingMeta(problemSetId, title);
        } catch (NoResultException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "题集不存在");
        }
    }

    /** 取该 offering 全体学生 × 每题 PTA 状态，按学生聚合。 */
    private List<StudentAggregate> aggregate(Long offeringId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT sp.id, sp.student_no, sp.real_name, "
                                + "ap.problem_no, ap.title, ap.max_score, "
                                + "sps.latest_status, sps.best_score, sps.accepted_at, "
                                + "apd.content, art.text_content "
                                + "FROM student_assignment sa "
                                + "JOIN student_profile sp ON sp.id = sa.student_id "
                                + "JOIN assignment_problem ap ON ap.offering_id = sa.offering_id AND ap.status = 'ACTIVE' "
                                + "LEFT JOIN student_problem_state sps "
                                + "  ON sps.offering_id = sa.offering_id AND sps.problem_id = ap.id AND sps.student_id = sp.id "
                                + "LEFT JOIN pta_problem_detail apd ON apd.problem_set_problem_id = ap.problem_no "
                                + "LEFT JOIN artifact art ON art.id = sps.latest_code_artifact_id "
                                + "WHERE sa.offering_id = ?1 "
                                + "ORDER BY sp.student_no, ap.sort_order, ap.id")
                .setParameter(1, offeringId)
                .getResultList();

        Map<Long, StudentAggregate> byStudent = new LinkedHashMap<>();
        for (Object[] r : rows) {
            Long studentId = ((Number) r[0]).longValue();
            StudentAggregate agg = byStudent.computeIfAbsent(studentId,
                    k -> new StudentAggregate(studentId, str(r[1]), str(r[2])));
            Map<String, Object> problem = new LinkedHashMap<>();
            problem.put("problemNo", str(r[3]));
            problem.put("title", str(r[4]));
            BigDecimal maxScore = num(r[5]);
            String status = str(r[6]);
            BigDecimal bestScore = num(r[7]);
            boolean accepted = r[8] != null || isAccepted(status);
            problem.put("maxScore", maxScore);
            problem.put("status", status);
            problem.put("bestScore", bestScore);
            problem.put("accepted", accepted);
            problem.put("statement", truncate(str(r[9]), MAX_STATEMENT_CHARS));
            problem.put("code", truncate(str(r[10]), MAX_CODE_CHARS));
            agg.add(problem, maxScore, bestScore, accepted);
        }
        return new ArrayList<>(byStudent.values());
    }

    /** 调 AI 生成教师评语；不可用/失败时降级为客观摘要。 */
    private String buildComment(StudentAggregate a, OfferingMeta meta) {
        String objective = a.acceptedCount + "/" + a.problemCount + " 题通过，客观得分 "
                + (a.score() == null ? "-" : a.score().toPlainString());
        if (!aiClient.isChatAvailable()) {
            return objective + "。请结合各题判题结果查漏补缺。";
        }
        try {
            String system = "你是编程实验课的任课教师，正在依据 PTA 客观判题结果与学生代码，为学生写一段简短教师评语。"
                    + "要求：2-3 句中文，自然有温度，结合通过情况与主要问题给出针对性建议，不虚构、不输出分数数字、不使用 Markdown。";
            StringBuilder user = new StringBuilder();
            user.append("题集：").append(safe(meta.title())).append("\n");
            user.append("学生：").append(safe(a.realName)).append("，通过 ").append(a.acceptedCount)
                    .append("/").append(a.problemCount).append(" 题。\n\n各题情况：\n");
            for (Map<String, Object> p : a.problems) {
                user.append("- ").append(p.get("title")).append("（判题：").append(p.get("status")).append("）\n");
                Object code = p.get("code");
                if (code != null && !code.toString().isBlank()) {
                    user.append("代码片段：\n").append(truncate(code.toString(), 800)).append("\n");
                }
            }
            user.append("\n请只输出教师评语正文：");
            String out = aiClient.chat(system, user.toString(), 0.4);
            return out == null || out.isBlank() ? objective + "。" : out.trim();
        } catch (RuntimeException e) {
            log.warn("PTA 评语 AI 生成失败，降级客观摘要 student={}: {}", a.studentNo, e.getMessage());
            return objective + "。请结合各题判题结果查漏补缺。";
        }
    }

    private Map<String, Object> toView(PtaGradingResultEntity e, boolean withDetail) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", e.getId());
        v.put("published", e.isPublished());
        v.put("offeringId", e.getOfferingId());
        v.put("studentNo", e.getStudentNo());
        v.put("studentName", e.getStudentName());
        v.put("score", e.getScore());
        v.put("acRate", e.getAcRate());
        v.put("problemCount", e.getProblemCount());
        v.put("acceptedCount", e.getAcceptedCount());
        v.put("comment", e.getComment());
        v.put("publishedAt", e.getPublishedAt() == null ? null : e.getPublishedAt().toString());
        if (withDetail) {
            v.put("detail", readDetail(e.getDetailJson()));
        }
        return v;
    }

    private static boolean isAccepted(String status) {
        return status != null && ACCEPTED_STATUS.contains(status.trim().toUpperCase(Locale.ROOT));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private Object readDetail(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static BigDecimal num(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal b) {
            return b;
        }
        if (o instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private record OfferingMeta(String problemSetId, String title) {}

    /** 单个学生在该 offering 的聚合。 */
    static final class StudentAggregate {
        final Long studentId;
        final String studentNo;
        final String realName;
        final List<Map<String, Object>> problems = new ArrayList<>();
        int problemCount;
        int acceptedCount;
        BigDecimal sumBest = BigDecimal.ZERO;
        BigDecimal sumMax = BigDecimal.ZERO;

        StudentAggregate(Long studentId, String studentNo, String realName) {
            this.studentId = studentId;
            this.studentNo = studentNo;
            this.realName = realName;
        }

        void add(Map<String, Object> problem, BigDecimal maxScore, BigDecimal bestScore, boolean accepted) {
            problems.add(problem);
            problemCount++;
            if (accepted) {
                acceptedCount++;
            }
            if (bestScore != null) {
                sumBest = sumBest.add(bestScore);
            }
            if (maxScore != null) {
                sumMax = sumMax.add(maxScore);
            }
        }

        /** 客观分：优先 Σbest/Σmax×100；无满分则回退 AC率×100。 */
        BigDecimal score() {
            if (problemCount == 0) {
                return BigDecimal.ZERO;
            }
            if (sumMax.compareTo(BigDecimal.ZERO) > 0) {
                return sumBest.multiply(BigDecimal.valueOf(100))
                        .divide(sumMax, 2, RoundingMode.HALF_UP);
            }
            return BigDecimal.valueOf(acceptedCount).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(problemCount), 2, RoundingMode.HALF_UP);
        }

        BigDecimal acRate() {
            if (problemCount == 0) {
                return BigDecimal.ZERO;
            }
            return BigDecimal.valueOf(acceptedCount).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(problemCount), 2, RoundingMode.HALF_UP);
        }

        Map<String, Object> toSummary() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("studentId", studentId);
            m.put("studentNo", studentNo);
            m.put("studentName", realName);
            m.put("score", score());
            m.put("acRate", acRate());
            m.put("problemCount", problemCount);
            m.put("acceptedCount", acceptedCount);
            return m;
        }
    }
}
