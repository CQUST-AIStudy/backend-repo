package com.tap.backend.service.animation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.animation.StudentCodePlaygroundEntity;
import com.tap.backend.repo.StudentCodePlaygroundRepository;
import com.tap.backend.security.StudentPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * AI 助教「代码演示（手动输入）」服务。
 * <p>
 * 接受学生手动粘贴的代码 + 题目 + stdin，调用 {@link CodeDemoComposer} 合成执行/错误动画，
 * 按学生学号保存历史（可回看/删除）。执行任意代码的沙箱加固在 code_tracer.py 层。
 */
@Service
public class CodePlaygroundService {

    private static final int MAX_CODE_LENGTH = 64 * 1024;

    private static final Logger log = LoggerFactory.getLogger(CodePlaygroundService.class);

    private final StudentPrincipalResolver resolver;
    private final StudentCodePlaygroundRepository repository;
    private final CodeDemoComposer composer;
    private final ObjectMapper objectMapper;
    private final Executor aiExecutor;
    private final TransactionTemplate transactionTemplate;

    public CodePlaygroundService(StudentPrincipalResolver resolver,
                                 StudentCodePlaygroundRepository repository,
                                 CodeDemoComposer composer,
                                 ObjectMapper objectMapper,
                                 @Qualifier("aiExecutor") Executor aiExecutor,
                                 TransactionTemplate transactionTemplate) {
        this.resolver = resolver;
        this.repository = repository;
        this.composer = composer;
        this.objectMapper = objectMapper;
        this.aiExecutor = aiExecutor;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 生成演示（异步）。
     * <p>
     * 真实执行 / LLM 兜底可能耗时数十秒，过去同步执行会长时间占用 Tomcat 线程与数据库连接
     * （HikariCP 连接泄漏告警），并超过网关 proxy_read_timeout 触发 504。现改为：
     * 快速校验并落库一条 {@code PROCESSING} 记录后立即返回，耗时的合成过程交由
     * {@code aiExecutor} 线程池异步完成，前端轮询 {@link #detail} 获取最终结果。
     */
    @Transactional
    public Map<String, Object> generate(String title, String problemMd, String code, String stdin,
                                        UserPrincipal principal) {
        StudentPrincipalResolver.ResolvedStudent student = resolver.requireStudent(principal);
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写代码");
        }
        if (code.length() > MAX_CODE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "代码过长");
        }
        String finalTitle = deriveTitle(title, problemMd);

        StudentCodePlaygroundEntity entity = new StudentCodePlaygroundEntity();
        entity.setStudentNo(student.studentNum());
        entity.setTitle(finalTitle);
        entity.setProblemMd(problemMd);
        entity.setSourceCode(code);
        entity.setStdinText(stdin);
        entity.setStatus("PROCESSING");
        repository.save(entity);

        Long entityId = entity.getId();
        aiExecutor.execute(() -> runGeneration(entityId, code, problemMd, stdin, finalTitle));

        return toView(entity);
    }

    /** 异步执行耗时的演示合成：真实执行优先、LLM 概念分步兜底；完成后回填帧数据并置 COMPLETED。 */
    private void runGeneration(Long entityId, String code, String problemMd, String stdin, String finalTitle) {
        try {
            // 耗时的真实执行 / LLM 调用在事务之外，不占用数据库连接
            String resolvedStdin = stdin != null ? stdin : composer.autoStdin(problemMd, code);
            Map<String, Object> demonstration = composer.buildDemonstration(code, resolvedStdin, finalTitle, null);
            transactionTemplate.executeWithoutResult(status ->
                    repository.findById(entityId).ifPresent(entity -> {
                        entity.setSourceCode(String.valueOf(demonstration.getOrDefault("sourceCode", entity.getSourceCode())));
                        entity.setStdinText(resolvedStdin);
                        entity.setWorkflow(String.valueOf(demonstration.getOrDefault("workflow", "")));
                        entity.setExplanation(String.valueOf(demonstration.getOrDefault("explanation", "")));
                        entity.setErrorLine(toInt(demonstration.get("errorLine")));
                        entity.setFramesJson(writeJson(demonstration));
                        entity.setStatus("COMPLETED");
                        repository.save(entity);
                    }));
        } catch (RuntimeException e) {
            log.error("代码演示异步生成失败 id={}: {}", entityId, e.getMessage(), e);
            try {
                transactionTemplate.executeWithoutResult(status ->
                        repository.findById(entityId).ifPresent(entity -> {
                            entity.setStatus("FAILED");
                            repository.save(entity);
                        }));
            } catch (RuntimeException ex) {
                log.error("代码演示标记失败状态异常 id={}: {}", entityId, ex.getMessage(), ex);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(UserPrincipal principal) {
        StudentPrincipalResolver.ResolvedStudent student = resolver.requireStudent(principal);
        List<Map<String, Object>> out = new ArrayList<>();
        for (StudentCodePlaygroundEntity e : repository.findTop50ByStudentNoOrderByCreatedAtDesc(student.studentNum())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("title", e.getTitle());
            m.put("workflow", e.getWorkflow());
            m.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
            out.add(m);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id, UserPrincipal principal) {
        StudentPrincipalResolver.ResolvedStudent student = resolver.requireStudent(principal);
        StudentCodePlaygroundEntity e = repository.findByIdAndStudentNo(id, student.studentNum())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在"));
        return toView(e);
    }

    @Transactional
    public void delete(Long id, UserPrincipal principal) {
        StudentPrincipalResolver.ResolvedStudent student = resolver.requireStudent(principal);
        StudentCodePlaygroundEntity e = repository.findByIdAndStudentNo(id, student.studentNum())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在"));
        repository.delete(e);
    }

    private Map<String, Object> toView(StudentCodePlaygroundEntity e) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", e.getId());
        v.put("status", e.getStatus());
        v.put("title", e.getTitle());
        v.put("stdin", e.getStdinText() == null ? "" : e.getStdinText());
        v.put("workflow", e.getWorkflow());
        v.put("demonstration", readDemonstration(e.getFramesJson()));
        return v;
    }

    /** 标题为空时回落：取题面首个非空行（去 Markdown 记号，截断 40 字），再无则用时间。 */
    private String deriveTitle(String title, String problemMd) {
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        if (problemMd != null) {
            for (String line : problemMd.split("\n")) {
                String t = line.replaceAll("[#>*`]", "").trim();
                if (!t.isEmpty()) {
                    return t.length() > 40 ? t.substring(0, 40) : t;
                }
            }
        }
        return "代码演示 " + LocalDateTime.now().withNano(0);
    }

    private Map<String, Object> readDemonstration(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
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
        return value instanceof Number number ? number.intValue() : 0;
    }
}
