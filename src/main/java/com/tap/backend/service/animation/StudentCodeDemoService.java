package com.tap.backend.service.animation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.animation.StudentCodeDemoEntity;
import com.tap.backend.repo.StudentCodeDemoRepository;
import com.tap.backend.security.StudentPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 学生端「每题代码执行演示」服务。
 * <p>
 * 只演示学生本人已入库的代码（按 {@code student_no + offering_id + problem_no} 重查 artifact），
 * 不接受前端直接提交任意代码执行。stdin 允许自定义（默认取题面「输入样例」）。
 * 动画合成（真实执行优先→LLM 兜底）委托 {@link CodeDemoComposer}。结果按题缓存（upsert），可 {@code force} 重新生成。
 */
@Service
public class StudentCodeDemoService {

    private static final Logger log = LoggerFactory.getLogger(StudentCodeDemoService.class);

    private final StudentPrincipalResolver studentPrincipalResolver;
    private final StudentCodeDemoRepository repository;
    private final CodeDemoComposer composer;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    public StudentCodeDemoService(StudentPrincipalResolver studentPrincipalResolver,
                                  StudentCodeDemoRepository repository,
                                  CodeDemoComposer composer,
                                  ObjectMapper objectMapper) {
        this.studentPrincipalResolver = studentPrincipalResolver;
        this.repository = repository;
        this.composer = composer;
        this.objectMapper = objectMapper;
    }

    /** 读缓存：无则返回 {@code {status:'NONE'}}。 */
    @Transactional(readOnly = true)
    public Map<String, Object> getCached(Long experimentId, String problemNo, UserPrincipal principal) {
        StudentPrincipalResolver.ResolvedStudent student = studentPrincipalResolver.requireStudent(principal);
        StudentTarget target = loadTarget(student.studentNum(), experimentId, problemNo);
        return repository
                .findByStudentProfileIdAndOfferingIdAndProblemNo(target.studentProfileId(), experimentId, problemNo)
                .map(this::toView)
                .orElseGet(() -> Map.of("status", "NONE"));
    }

    /** 生成 / 重新生成。{@code force=false} 且已有缓存时直接返回缓存。 */
    @Transactional
    public Map<String, Object> generate(Long experimentId, String problemNo, String stdin, boolean force,
                                        UserPrincipal principal) {
        StudentPrincipalResolver.ResolvedStudent student = studentPrincipalResolver.requireStudent(principal);
        StudentTarget target = loadTarget(student.studentNum(), experimentId, problemNo);

        Optional<StudentCodeDemoEntity> existing = repository
                .findByStudentProfileIdAndOfferingIdAndProblemNo(target.studentProfileId(), experimentId, problemNo);
        if (!force && existing.isPresent()) {
            return toView(existing.get());
        }

        String code = target.code();
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该题暂无已提交的代码，无法生成演示");
        }

        String resolvedStdin = stdin != null ? stdin : composer.autoStdin(target.statementMd(), code);
        String title = firstNonBlank(target.title(), "代码执行演示");

        Map<String, Object> demonstration = composer.buildDemonstration(code, resolvedStdin, title, experimentId);

        StudentCodeDemoEntity entity = existing.orElseGet(StudentCodeDemoEntity::new);
        entity.setStudentProfileId(target.studentProfileId());
        entity.setOfferingId(experimentId);
        entity.setProblemNo(problemNo);
        entity.setSourceCode(String.valueOf(demonstration.getOrDefault("sourceCode", code)));
        entity.setStdinText(resolvedStdin);
        entity.setWorkflow(String.valueOf(demonstration.getOrDefault("workflow", "")));
        entity.setTitle(title);
        entity.setExplanation(String.valueOf(demonstration.getOrDefault("explanation", "")));
        entity.setErrorLine(toInt(demonstration.get("errorLine")));
        entity.setStatus("COMPLETED");
        entity.setFramesJson(writeJson(demonstration));
        repository.save(entity);

        return toView(entity);
    }

    // ---- 目标定位与持久化视图 ---------------------------------------------

    private StudentTarget loadTarget(String studentNo, Long experimentId, String problemNo) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT sp.id, ap.title, apd.content, a.text_content " +
                                "FROM student_problem_state sps " +
                                "JOIN student_profile sp ON sp.id = sps.student_id " +
                                "JOIN assignment_problem ap ON ap.id = sps.problem_id " +
                                "LEFT JOIN pta_problem_detail apd ON apd.problem_set_problem_id = ap.problem_no " +
                                "LEFT JOIN artifact a ON a.id = sps.latest_code_artifact_id " +
                                "WHERE sp.student_no = ?1 AND sps.offering_id = ?2 AND ap.problem_no = ?3 " +
                                "LIMIT 1")
                .setParameter(1, studentNo)
                .setParameter(2, experimentId)
                .setParameter(3, problemNo)
                .getResultList();
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到该题的作答记录");
        }
        Object[] row = rows.get(0);
        Long studentProfileId = ((Number) row[0]).longValue();
        String title = row[1] != null ? row[1].toString() : null;
        String statementMd = row[2] != null ? row[2].toString() : null;
        String code = row[3] != null ? row[3].toString().trim() : null;
        return new StudentTarget(studentProfileId, title, statementMd, code);
    }

    private Map<String, Object> toView(StudentCodeDemoEntity entity) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("status", entity.getStatus());
        view.put("problemNo", entity.getProblemNo());
        view.put("title", entity.getTitle());
        view.put("stdin", entity.getStdinText() == null ? "" : entity.getStdinText());
        view.put("workflow", entity.getWorkflow());
        view.put("demonstration", readDemonstration(entity.getFramesJson()));
        return view;
    }

    private Map<String, Object> readDemonstration(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("演示 JSON 解析失败：{}", e.getMessage());
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "演示结果序列化失败");
        }
    }

    private static int toInt(Object value) {
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
        return 0;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private record StudentTarget(Long studentProfileId, String title, String statementMd, String code) {}
}
