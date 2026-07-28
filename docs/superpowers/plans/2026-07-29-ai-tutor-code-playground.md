# AI 助教 · 代码演示（手动输入）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 AI 助教新增独立子页，学生手动粘贴「错误代码 + 题目」→ 后端"真实执行优先 + LLM 兜底"生成执行/错误动画，保存历史可回看。

**Architecture:** 复用现有动画引擎（`code_tracer.py` gcc 插桩、`ConceptStepsWorkflow` LLM、`ErrorDemonstrationPlayer` 渲染器）。把 `StudentCodeDemoService` 的合成逻辑抽成可注入的 `CodeDemoComposer`，新 `CodePlaygroundService`/控制器复用它并持久化到新表 `student_code_playground`（按 student_no 存历史）。执行学生任意代码 → 在现有容器内加轻量沙箱（setrlimit + 超时 + 输出截断）。

**Tech Stack:** Spring Boot(JDK21)/JPA/Flyway、Python3+gcc(code_tracer)、Vue3 `<script setup>` + Vue Router + apiClient(axios)、Mockito、vue-cli lint。

**约定：** 后端根 `backend-repo/`，前端根 `frontend-repo/`。设计见 `backend-repo/docs/superpowers/specs/2026-07-29-ai-tutor-code-playground-design.md`。参照既有同类文件：`StudentCodeDemoService.java`、`StudentCodeDemoServiceTest.java`、`StudentCodeDemoController.java`、`StudentCodeDemoEntity.java`、`views/student/CodeDemoPage.vue`。

---

## 文件结构

**后端（backend-repo/src/main/java/com/tap/backend/）**
- 新建 `service/animation/CodeDemoComposer.java` — 无持久化的动画合成组件（真实执行优先→LLM 兜底 + stdin 自动生成）。
- 改 `service/animation/StudentCodeDemoService.java` — 删除已抽出的私有方法，改为注入并委托 `CodeDemoComposer`。
- 新建 `domain/animation/StudentCodePlaygroundEntity.java`、`repo/StudentCodePlaygroundRepository.java`。
- 新建 `service/animation/CodePlaygroundService.java`、`api/student/StudentCodePlaygroundController.java`。
- 改 `security/SecurityConfig.java` — 放行 `/api/student/ai-assistant/**`。
- 新建 `src/main/resources/db/migration/V71__student_code_playground.sql`（版本号实现时现查）。
- 改 `grading-worker/code_tracer.py` — C 编译/运行加 setrlimit + 输出截断。
- 新建测试 `src/test/java/com/tap/backend/service/animation/CodePlaygroundServiceTest.java`。

**前端（frontend-repo/src/）**
- 改 `api/index.js` — 新增 playground API。
- 改 `router/index.js` — 新增 `code-playground` 路由。
- 改 `views/student/Layout.vue` — 「AI 智能助手」分组加菜单项。
- 新建 `views/student/CodePlayground.vue`。

---

## Task 1: 抽取 CodeDemoComposer（重构，保持既有测试绿）

**Files:**
- Create: `backend-repo/src/main/java/com/tap/backend/service/animation/CodeDemoComposer.java`
- Modify: `backend-repo/src/main/java/com/tap/backend/service/animation/StudentCodeDemoService.java`
- Test: `backend-repo/src/test/java/com/tap/backend/service/animation/StudentCodeDemoServiceTest.java`（已存在，须仍通过）

- [ ] **Step 1: 新建 CodeDemoComposer**，把 `StudentCodeDemoService` 中这些方法整体搬过来并改 `public`/包级：`buildDemonstration(code, stdin, title, experimentId)`、`buildCandidate`、`demonstration`、`ensureMainFunction`、`resolveStdin`、`cleanSample`、`autoStdin`、`llmGenerateStdin`、`sanitizeStdin`、静态 `toInt`/`firstNonBlank`/`truncate`。构造注入 `CodeExecutionSandboxService`、`ConceptStepsWorkflow`、`AnimationAiClient`。

```java
package com.tap.backend.service.animation;

import com.tap.backend.service.grading.animation.AnimationCandidate;
import com.tap.backend.service.grading.animation.AnimationResult;
import com.tap.backend.service.grading.animation.AnimationWorkflow;
import com.tap.backend.service.grading.animation.CodeContext;
import com.tap.backend.service.grading.animation.ConceptStepsWorkflow;
import com.tap.backend.service.grading.animation.ErrorPatternDetector;
import com.tap.backend.service.grading.animation.ProblemContext;
import com.tap.backend.service.grading.animation.execution.CodeExecutionSandboxService;
import com.tap.backend.service.grading.animation.execution.ExecutionTrace;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 动画合成组件：输入代码/stdin/题面 → demonstration Map。无持久化，供按题演示与 Playground 复用。 */
@Component
public class CodeDemoComposer {
    private static final Logger log = LoggerFactory.getLogger(CodeDemoComposer.class);
    private static final Pattern INPUT_SAMPLE = Pattern.compile(
            "输入样例[^\\n：:]*[：:]?\\s*(?:```[a-zA-Z]*\\s*)?([\\s\\S]*?)(?:```|输出样例|$)");

    private final CodeExecutionSandboxService sandboxService;
    private final ConceptStepsWorkflow conceptStepsWorkflow;
    private final AnimationAiClient aiClient;

    public CodeDemoComposer(CodeExecutionSandboxService sandboxService,
                            ConceptStepsWorkflow conceptStepsWorkflow,
                            AnimationAiClient aiClient) {
        this.sandboxService = sandboxService;
        this.conceptStepsWorkflow = conceptStepsWorkflow;
        this.aiClient = aiClient;
    }
    // ↓ 把 StudentCodeDemoService 中同名方法原样搬入（buildDemonstration/buildCandidate/demonstration/
    //   ensureMainFunction/resolveStdin/cleanSample/autoStdin/llmGenerateStdin/sanitizeStdin/toInt/firstNonBlank/truncate）
}
```

- [ ] **Step 2: 改 StudentCodeDemoService** — 删掉上述已搬走的方法；构造器新增注入 `CodeDemoComposer composer`（移除 `sandboxService`/`conceptStepsWorkflow`/`aiClient` 若不再直接使用）；原调用点改为 `composer.autoStdin(...)` / `composer.buildDemonstration(...)` / `composer.resolveStdin(...)`。保留其 DB/loadTarget/toView 逻辑不变。

- [ ] **Step 3: 同步单测构造器** — `StudentCodeDemoServiceTest` 的 `new StudentCodeDemoService(...)` 参数随构造器调整；其对 `resolveStdin` 的直接断言若方法已移走，改为 `new CodeDemoComposer(...).resolveStdin(...)` 或在 Composer 测试里覆盖（见 Task 1 Step 4）。真实执行/回退等用例改为 stub `composer`（或保留真实 composer 但 stub 其依赖）。**最小改动：** 让测试继续构造真实 `CodeDemoComposer`（传 mock 的 sandbox/conceptSteps/aiClient），注入给 service，其余断言不变。

- [ ] **Step 4: 新建 CodeDemoComposerTest**（覆盖 resolveStdin 三态：纯文本/围栏/缺失）

```java
package com.tap.backend.service.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class CodeDemoComposerTest {
    private final CodeDemoComposer composer = new CodeDemoComposer(null, null, null);

    @Test void plainSample() { assertEquals("3 4", composer.resolveStdin("题面\n输入样例\n3 4\n输出样例\n7")); }
    @Test void fencedSample() { assertEquals("5 6", composer.resolveStdin("输入样例：\n```\n5 6\n```\n输出样例\n11")); }
    @Test void absentSample() { assertEquals("", composer.resolveStdin("没有样例")); }
}
```

- [ ] **Step 5: 编译 + 跑相关测试**

Run: `cd backend-repo; .\mvnw.cmd -o -q test '-Dtest=StudentCodeDemoServiceTest,CodeDemoComposerTest'`
Expected: BUILD SUCCESS（既有 7 项 + 新 3 项通过）

- [ ] **Step 6: Commit**

```bash
git add backend-repo/src/main/java/com/tap/backend/service/animation/ backend-repo/src/test/java/com/tap/backend/service/animation/
git commit -m "refactor(animation): 抽取 CodeDemoComposer 供按题演示与 Playground 复用"
```

---

## Task 2: Flyway 迁移建表

**Files:**
- Create: `backend-repo/src/main/resources/db/migration/V71__student_code_playground.sql`

- [ ] **Step 1: 现查最新迁移号，确定本迁移版本**

Run: `cd backend-repo; git fetch origin; git ls-tree -r --name-only origin/main -- src/main/resources/db/migration | Sort-Object | Select-Object -Last 3`
Expected: 打印最大版本（如 `V70__...`）。本文件用 max+1（下例假定 V71，若已被占用则顺延并同步改文件名）。

- [ ] **Step 2: 写迁移**

```sql
-- AI 助教「代码演示（手动输入）」历史表：按学生学号存多条生成记录
CREATE TABLE IF NOT EXISTS student_code_playground (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    student_no   VARCHAR(128) NOT NULL,
    title        VARCHAR(512),
    problem_md   TEXT,
    source_code  LONGTEXT,
    stdin_text   TEXT,
    workflow     VARCHAR(32),
    frames_json  LONGTEXT,
    explanation  TEXT,
    error_line   INT NOT NULL DEFAULT 0,
    status       VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_scp_student_created (student_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI助教代码演示(手动输入)历史';
```

- [ ] **Step 3: Commit**

```bash
git add backend-repo/src/main/resources/db/migration/V71__student_code_playground.sql
git commit -m "feat(db): V71 student_code_playground 建表"
```

---

## Task 3: 实体与仓库

**Files:**
- Create: `backend-repo/src/main/java/com/tap/backend/domain/animation/StudentCodePlaygroundEntity.java`
- Create: `backend-repo/src/main/java/com/tap/backend/repo/StudentCodePlaygroundRepository.java`

- [ ] **Step 1: 实体**（参照 `StudentCodeDemoEntity.java` 的注解风格；字段对齐 V71）

```java
package com.tap.backend.domain.animation;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "student_code_playground")
public class StudentCodePlaygroundEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "student_no", nullable = false) private String studentNo;
    @Column private String title;
    @Column(name = "problem_md", columnDefinition = "text") private String problemMd;
    @Column(name = "source_code", columnDefinition = "longtext") private String sourceCode;
    @Column(name = "stdin_text", columnDefinition = "text") private String stdinText;
    @Column private String workflow;
    @Column(name = "frames_json", columnDefinition = "longtext") private String framesJson;
    @Column(columnDefinition = "text") private String explanation;
    @Column(name = "error_line", nullable = false) private int errorLine;
    @Column(nullable = false) private String status = "COMPLETED";
    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    // getters/setters（全字段，照 StudentCodeDemoEntity 风格补齐）
}
```

- [ ] **Step 2: 仓库**

```java
package com.tap.backend.repo;

import com.tap.backend.domain.animation.StudentCodePlaygroundEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudentCodePlaygroundRepository extends JpaRepository<StudentCodePlaygroundEntity, Long> {
    List<StudentCodePlaygroundEntity> findTop50ByStudentNoOrderByCreatedAtDesc(String studentNo);
    Optional<StudentCodePlaygroundEntity> findByIdAndStudentNo(Long id, String studentNo);
}
```

- [ ] **Step 3: 编译**

Run: `cd backend-repo; .\mvnw.cmd -o -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend-repo/src/main/java/com/tap/backend/domain/animation/StudentCodePlaygroundEntity.java backend-repo/src/main/java/com/tap/backend/repo/StudentCodePlaygroundRepository.java
git commit -m "feat(playground): 实体与仓库 student_code_playground"
```

---

## Task 4: CodePlaygroundService（TDD）

**Files:**
- Create: `backend-repo/src/main/java/com/tap/backend/service/animation/CodePlaygroundService.java`
- Test: `backend-repo/src/test/java/com/tap/backend/service/animation/CodePlaygroundServiceTest.java`

- [ ] **Step 1: 写失败测试**（参照 `StudentCodeDemoServiceTest` 的 Mockito 写法）

```java
package com.tap.backend.service.animation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.animation.StudentCodePlaygroundEntity;
import com.tap.backend.repo.StudentCodePlaygroundRepository;
import com.tap.backend.security.StudentPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.grading.animation.AnimationWorkflow;
import com.tap.backend.service.grading.animation.execution.CodeExecutionSandboxService;
import com.tap.backend.service.grading.animation.execution.ExecutionTrace;
import com.tap.backend.service.grading.animation.execution.TraceStep;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CodePlaygroundServiceTest {
    @Mock StudentPrincipalResolver resolver;
    @Mock StudentCodePlaygroundRepository repo;
    @Mock CodeExecutionSandboxService sandbox;
    @Mock com.tap.backend.service.grading.animation.ConceptStepsWorkflow concept;
    @Mock AnimationAiClient ai;
    private CodePlaygroundService service;
    private final ObjectMapper om = new ObjectMapper();
    private static final String CODE = "int main(){int x=1;return 0;}";

    @BeforeEach void setUp() {
        CodeDemoComposer composer = new CodeDemoComposer(sandbox, concept, ai);
        service = new CodePlaygroundService(resolver, repo, composer, om);
        when(resolver.requireStudent(nullable(UserPrincipal.class)))
            .thenReturn(new StudentPrincipalResolver.ResolvedStudent(1L, "u", "n", "S001"));
        when(repo.save(any(StudentCodePlaygroundEntity.class))).thenAnswer(i -> { var e=(StudentCodePlaygroundEntity)i.getArgument(0); e.setId(9L); return e; });
    }

    @Test void generateRealExecutionPersistsPythonTutor() {
        TraceStep s = new TraceStep(1,1,"step","",Map.of("x",1),Map.of(),Map.of(),false,null);
        when(sandbox.execute(eq("c"), anyString(), any()))
            .thenReturn(new ExecutionTrace(true,"c",CODE,null,"","",List.of(s)));
        Map<String,Object> v = service.generate("t","题面\n输入样例\n1\n输出样例\n", CODE, "1", (UserPrincipal)null);
        @SuppressWarnings("unchecked") Map<String,Object> demo=(Map<String,Object>)v.get("demonstration");
        assertEquals(AnimationWorkflow.PYTHON_TUTOR.name(), demo.get("workflow"));
        verify(repo).save(any());
    }

    @Test void detailRejectsOtherStudent() {
        when(repo.findByIdAndStudentNo(9L,"S001")).thenReturn(Optional.empty());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> service.detail(9L,(UserPrincipal)null));
    }

    @Test void emptyCodeRejected() {
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> service.generate("t","p","   ",null,(UserPrincipal)null));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend-repo; .\mvnw.cmd -o -q test '-Dtest=CodePlaygroundServiceTest'`
Expected: FAIL（CodePlaygroundService 尚未定义）

- [ ] **Step 3: 实现 CodePlaygroundService**

```java
package com.tap.backend.service.animation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.animation.StudentCodePlaygroundEntity;
import com.tap.backend.repo.StudentCodePlaygroundRepository;
import com.tap.backend.security.StudentPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CodePlaygroundService {
    private final StudentPrincipalResolver resolver;
    private final StudentCodePlaygroundRepository repo;
    private final CodeDemoComposer composer;
    private final ObjectMapper objectMapper;

    public CodePlaygroundService(StudentPrincipalResolver resolver, StudentCodePlaygroundRepository repo,
                                 CodeDemoComposer composer, ObjectMapper objectMapper) {
        this.resolver = resolver; this.repo = repo; this.composer = composer; this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String,Object> generate(String title, String problemMd, String code, String stdin, UserPrincipal principal) {
        var student = resolver.requireStudent(principal);
        if (code == null || code.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写代码");
        if (code.length() > 64 * 1024) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "代码过长");
        String resolvedStdin = stdin != null ? stdin : composer.autoStdin(problemMd, code);
        String finalTitle = deriveTitle(title, problemMd);
        Map<String,Object> demo = composer.buildDemonstration(code, resolvedStdin, finalTitle, null);

        StudentCodePlaygroundEntity e = new StudentCodePlaygroundEntity();
        e.setStudentNo(student.studentNum());
        e.setTitle(finalTitle);
        e.setProblemMd(problemMd);
        e.setSourceCode(String.valueOf(demo.getOrDefault("sourceCode", code)));
        e.setStdinText(resolvedStdin);
        e.setWorkflow(String.valueOf(demo.getOrDefault("workflow", "")));
        e.setExplanation(String.valueOf(demo.getOrDefault("explanation", "")));
        e.setErrorLine(toInt(demo.get("errorLine")));
        e.setStatus("COMPLETED");
        e.setFramesJson(writeJson(demo));
        repo.save(e);
        return toView(e);
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> history(UserPrincipal principal) {
        var student = resolver.requireStudent(principal);
        List<Map<String,Object>> out = new ArrayList<>();
        for (var e : repo.findTop50ByStudentNoOrderByCreatedAtDesc(student.studentNum())) {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id", e.getId()); m.put("title", e.getTitle());
            m.put("workflow", e.getWorkflow());
            m.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
            out.add(m);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String,Object> detail(Long id, UserPrincipal principal) {
        var student = resolver.requireStudent(principal);
        var e = repo.findByIdAndStudentNo(id, student.studentNum())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在"));
        return toView(e);
    }

    @Transactional
    public void delete(Long id, UserPrincipal principal) {
        var student = resolver.requireStudent(principal);
        var e = repo.findByIdAndStudentNo(id, student.studentNum())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在"));
        repo.delete(e);
    }

    private Map<String,Object> toView(StudentCodePlaygroundEntity e) {
        Map<String,Object> v = new LinkedHashMap<>();
        v.put("id", e.getId()); v.put("status", e.getStatus()); v.put("title", e.getTitle());
        v.put("stdin", e.getStdinText() == null ? "" : e.getStdinText());
        v.put("workflow", e.getWorkflow());
        v.put("demonstration", readDemo(e.getFramesJson()));
        return v;
    }

    private String deriveTitle(String title, String problemMd) {
        if (title != null && !title.isBlank()) return title.trim();
        if (problemMd != null) {
            for (String line : problemMd.split("\n")) {
                String t = line.replaceAll("[#>*`]", "").trim();
                if (!t.isEmpty()) return t.length() > 40 ? t.substring(0, 40) : t;
            }
        }
        return "代码演示 " + java.time.LocalDateTime.now().withNano(0);
    }

    private Map<String,Object> readDemo(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, new TypeReference<Map<String,Object>>() {}); }
        catch (Exception ex) { return Map.of(); }
    }
    private String writeJson(Object v) {
        try { return objectMapper.writeValueAsString(v); }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "序列化失败"); }
    }
    private static int toInt(Object o){ return o instanceof Number n ? n.intValue() : 0; }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend-repo; .\mvnw.cmd -o -q test '-Dtest=CodePlaygroundServiceTest'`
Expected: PASS（3 项）

- [ ] **Step 5: Commit**

```bash
git add backend-repo/src/main/java/com/tap/backend/service/animation/CodePlaygroundService.java backend-repo/src/test/java/com/tap/backend/service/animation/CodePlaygroundServiceTest.java
git commit -m "feat(playground): CodePlaygroundService 生成/历史/详情/删除 + 单测"
```

---

## Task 5: 控制器 + 请求体

**Files:**
- Create: `backend-repo/src/main/java/com/tap/backend/api/student/StudentCodePlaygroundController.java`

- [ ] **Step 1: 实现控制器**（参照 `StudentCodeDemoController.java` 的 `ApiResponse.of` 与 `@AuthenticationPrincipal` 风格）

```java
package com.tap.backend.api.student;

import com.tap.backend.common.ApiResponse; // 若包名不同，照 StudentCodeDemoController 的 import 修正
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.animation.CodePlaygroundService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/student/ai-assistant/code-demo")
public class StudentCodePlaygroundController {
    private final CodePlaygroundService service;
    public StudentCodePlaygroundController(CodePlaygroundService service) { this.service = service; }

    public record GenerateRequest(String title, String problemMd, String code, String stdin) {}

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody GenerateRequest req, @AuthenticationPrincipal UserPrincipal p) {
        Map<String,Object> v = service.generate(req.title(), req.problemMd(), req.code(), req.stdin(), p);
        return ResponseEntity.ok(ApiResponse.of(v));
    }

    @GetMapping("/history")
    public ResponseEntity<?> history(@AuthenticationPrincipal UserPrincipal p) {
        List<Map<String,Object>> items = service.history(p);
        return ResponseEntity.ok(ApiResponse.of(Map.of("items", items)));
    }

    @GetMapping("/history/{id}")
    public ResponseEntity<?> detail(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal p) {
        return ResponseEntity.ok(ApiResponse.of(service.detail(id, p)));
    }

    @DeleteMapping("/history/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal p) {
        service.delete(id, p);
        return ResponseEntity.ok(ApiResponse.of(Map.of("deleted", true)));
    }
}
```

- [ ] **Step 2: 编译**

Run: `cd backend-repo; .\mvnw.cmd -o -q -DskipTests compile`
Expected: BUILD SUCCESS（若 `ApiResponse` 包路径报错，打开 `StudentCodeDemoController.java` 抄其 import）

- [ ] **Step 3: Commit**

```bash
git add backend-repo/src/main/java/com/tap/backend/api/student/StudentCodePlaygroundController.java
git commit -m "feat(playground): StudentCodePlaygroundController 端点"
```

---

## Task 6: 安全放行

**Files:**
- Modify: `backend-repo/src/main/java/com/tap/backend/security/SecurityConfig.java`

- [ ] **Step 1: 在 code-demo 规则附近加一行**（`/api/student/code-demo/**` 那行之后）

```java
.requestMatchers("/api/student/ai-assistant/**").hasRole(UserRole.STUDENT.name())
```

- [ ] **Step 2: 编译并 Commit**

Run: `cd backend-repo; .\mvnw.cmd -o -q -DskipTests compile`（Expected: SUCCESS）

```bash
git add backend-repo/src/main/java/com/tap/backend/security/SecurityConfig.java
git commit -m "feat(playground): 放行 /api/student/ai-assistant/**"
```

---

## Task 7: code_tracer.py 轻量沙箱加固

**Files:**
- Modify: `backend-repo/grading-worker/code_tracer.py`

- [ ] **Step 1: 加资源限制 preexec + 输出截断**。在 `CTracer.trace` 里给编译与运行的 `subprocess.run` 传 `preexec_fn=_rlimit_preexec`（仅 POSIX），并在读取 stdout/stderr 后截断到 256KB。文件顶部新增：

```python
def _rlimit_preexec():
    try:
        import resource
        resource.setrlimit(resource.RLIMIT_CPU, (5, 6))
        resource.setrlimit(resource.RLIMIT_AS, (512 * 1024 * 1024, 512 * 1024 * 1024))
        resource.setrlimit(resource.RLIMIT_FSIZE, (8 * 1024 * 1024, 8 * 1024 * 1024))
    except Exception:
        pass

_PREEXEC = _rlimit_preexec if hasattr(__import__("os"), "fork") else None

def _cap(s, limit=256 * 1024):
    return s if s is None or len(s) <= limit else s[:limit] + "\n...[truncated]"
```

在两处 `subprocess.run(... timeout=...)` 调用加参数 `preexec_fn=_PREEXEC`；对 `compile_res.stderr` / `run_res.stdout` / `run_res.stderr` 用 `_cap(...)` 包一层再返回/解析。

- [ ] **Step 2: 本地语法自检**

Run: `cd backend-repo; python -m py_compile grading-worker/code_tracer.py`
Expected: 无输出（成功）

- [ ] **Step 3: Commit**

```bash
git add backend-repo/grading-worker/code_tracer.py
git commit -m "hardening(tracer): 编译/运行加 setrlimit 与输出截断"
```

---

## Task 8: 前端 API

**Files:**
- Modify: `frontend-repo/src/api/index.js`

- [ ] **Step 1: 在 `generateCodeDemo` 附近新增**

```js
  async generatePlaygroundDemo({ title, problemMd, code, stdin }) {
    return apiClient.post('/api/student/ai-assistant/code-demo/generate', { title, problemMd, code, stdin }, { timeout: 180000 })
  },
  async getPlaygroundHistory() {
    return apiClient.get('/api/student/ai-assistant/code-demo/history')
  },
  async getPlaygroundDemo(id) {
    return apiClient.get(`/api/student/ai-assistant/code-demo/history/${id}`)
  },
  async deletePlaygroundDemo(id) {
    return apiClient.delete(`/api/student/ai-assistant/code-demo/history/${id}`)
  },
```

- [ ] **Step 2: lint + Commit**

Run: `cd frontend-repo; node ./node_modules/@vue/cli-service/bin/vue-cli-service.js lint --no-fix src/api/index.js`（Expected: No lint errors）

```bash
git add frontend-repo/src/api/index.js
git commit -m "feat(playground): 前端 playground API"
```

---

## Task 9: 路由 + 菜单

**Files:**
- Modify: `frontend-repo/src/router/index.js`
- Modify: `frontend-repo/src/views/student/Layout.vue`

- [ ] **Step 1: 路由**（StudentLayout children 内，`ai-report` 路由附近）

```js
{ path: 'code-playground', name: 'StudentCodePlayground', component: () => import('../views/student/CodePlayground.vue') },
```

- [ ] **Step 2: 菜单**（`Layout.vue` 的 `ai` 分组 children 追加）

```js
{ path: '/student/code-playground', label: '代码演示' }
```

- [ ] **Step 3: lint + Commit**

Run: `cd frontend-repo; node ./node_modules/@vue/cli-service/bin/vue-cli-service.js lint --no-fix src/router/index.js src/views/student/Layout.vue`（Expected: No lint errors）

```bash
git add frontend-repo/src/router/index.js frontend-repo/src/views/student/Layout.vue
git commit -m "feat(playground): 路由与 AI 助手菜单入口"
```

---

## Task 10: CodePlayground.vue 页面

**Files:**
- Create: `frontend-repo/src/views/student/CodePlayground.vue`

- [ ] **Step 1: 实现页面**。参照 `CodeDemoPage.vue`（复用其加载动效/`ErrorDemonstrationPlayer variant="plain" readonly`/`LucideIcon`）。结构：左侧历史列表 + 右侧「输入表单 + 结果播放器」。要点：
  - 输入：`code`(textarea 必填)、`problemMd`(textarea)、`stdin`(textarea，占位"留空由 AI 自动生成")、`title`(input)。
  - 生成：`code` 空则禁用；`loading` 时旋转 loader + 滚动提示（可从 CodeDemoPage 复制 `LOADING_TIPS`/`startTips`/`stopTips`）。
  - 生成成功：`applyView(res?.data || res)` 设 `demo`/`workflow`/`usedStdin`，刷新历史并置顶。
  - 历史项点击 → `getPlaygroundDemo(id)` → applyView；删除 → `deletePlaygroundDemo(id)` → 刷新。
  - `demonstrations = computed(() => demo.value ? [demo.value] : [])`；`hasDemo` 同 CodeDemoPage。
  - `onMounted` 拉 `getPlaygroundHistory()`。
  - 顶部展示"生成方式：真实执行/AI 概念动画"（`workflow==='PYTHON_TUTOR'?'真实执行':'AI 概念动画'`）。

> 实现时对照 `CodeDemoPage.vue` 逐段迁移：把"读缓存/生成"改为"历史列表/生成+存历史"，其余（loading UI、播放器嵌入、goBack）保持一致。

- [ ] **Step 2: lint**

Run: `cd frontend-repo; node ./node_modules/@vue/cli-service/bin/vue-cli-service.js lint --no-fix src/views/student/CodePlayground.vue`
Expected: No lint errors found!

- [ ] **Step 3: Commit**

```bash
git add frontend-repo/src/views/student/CodePlayground.vue
git commit -m "feat(playground): CodePlayground 页面（输入代码/题目→动画+历史）"
```

---

## Task 11: 整体验证

- [ ] **Step 1: 后端全量相关测试**

Run: `cd backend-repo; .\mvnw.cmd -o -q test '-Dtest=CodeDemoComposerTest,StudentCodeDemoServiceTest,CodePlaygroundServiceTest'`
Expected: BUILD SUCCESS

- [ ] **Step 2: 后端打包（镜像用 maven.test.skip；此处本地跑一次 package 确认可打包）**

Run: `cd backend-repo; .\mvnw.cmd -q -DskipTests package`
Expected: BUILD SUCCESS，产物 `target/teaching-assistant-backend-0.0.1-SNAPSHOT.jar`

- [ ] **Step 3: 前端整仓 lint**

Run: `cd frontend-repo; node ./node_modules/@vue/cli-service/bin/vue-cli-service.js lint --no-fix`
Expected: 无新增 error

- [ ] **Step 4: 说明**：部署（重建 121 前后端容器 / 或按分支流程合并）由用户负责；本计划不含部署步骤。

---

## 备注
- 迁移版本号 V71 为预估，Task 2 Step 1 现查为准。
- 若 `ApiResponse` / `UserPrincipal` / `StudentPrincipalResolver.ResolvedStudent` 的包路径与示例不符，一律以 `StudentCodeDemoController.java` / `StudentCodeDemoService.java` 现有 import 为准。
- 执行学生任意代码的残留风险已在 spec 记录；本计划的 Task 7 是约定的轻量加固上限。
