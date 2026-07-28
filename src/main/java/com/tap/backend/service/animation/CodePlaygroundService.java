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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    private final StudentPrincipalResolver resolver;
    private final StudentCodePlaygroundRepository repository;
    private final CodeDemoComposer composer;
    private final ObjectMapper objectMapper;

    public CodePlaygroundService(StudentPrincipalResolver resolver,
                                 StudentCodePlaygroundRepository repository,
                                 CodeDemoComposer composer,
                                 ObjectMapper objectMapper) {
        this.resolver = resolver;
        this.repository = repository;
        this.composer = composer;
        this.objectMapper = objectMapper;
    }

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
        String resolvedStdin = stdin != null ? stdin : composer.autoStdin(problemMd, code);
        String finalTitle = deriveTitle(title, problemMd);

        Map<String, Object> demonstration = composer.buildDemonstration(code, resolvedStdin, finalTitle, null);

        StudentCodePlaygroundEntity entity = new StudentCodePlaygroundEntity();
        entity.setStudentNo(student.studentNum());
        entity.setTitle(finalTitle);
        entity.setProblemMd(problemMd);
        entity.setSourceCode(String.valueOf(demonstration.getOrDefault("sourceCode", code)));
        entity.setStdinText(resolvedStdin);
        entity.setWorkflow(String.valueOf(demonstration.getOrDefault("workflow", "")));
        entity.setExplanation(String.valueOf(demonstration.getOrDefault("explanation", "")));
        entity.setErrorLine(toInt(demonstration.get("errorLine")));
        entity.setStatus("COMPLETED");
        entity.setFramesJson(writeJson(demonstration));
        repository.save(entity);

        return toView(entity);
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
