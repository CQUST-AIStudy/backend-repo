package com.tap.backend.service.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.animation.StudentCodeDemoEntity;
import com.tap.backend.repo.StudentCodeDemoRepository;
import com.tap.backend.security.StudentPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.grading.animation.AnimationResult;
import com.tap.backend.service.grading.animation.AnimationWorkflow;
import com.tap.backend.service.grading.animation.ConceptStepsWorkflow;
import com.tap.backend.service.grading.animation.execution.CodeExecutionSandboxService;
import com.tap.backend.service.grading.animation.execution.ExecutionTrace;
import com.tap.backend.service.grading.animation.execution.TraceStep;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StudentCodeDemoServiceTest {

    @Mock StudentPrincipalResolver studentPrincipalResolver;
    @Mock StudentCodeDemoRepository repository;
    @Mock CodeExecutionSandboxService sandboxService;
    @Mock ConceptStepsWorkflow conceptStepsWorkflow;
    @Mock AnimationAiClient aiClient;
    @Mock EntityManager em;
    @Mock Query query;

    private StudentCodeDemoService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String CODE = "int add(int a, int b){ return a + b; }";
    private static final String STATEMENT = "题面\n输入样例\n3 4\n输出样例\n7\n结束";

    @BeforeEach
    void setUp() {
        CodeDemoComposer composer = new CodeDemoComposer(sandboxService, conceptStepsWorkflow, aiClient);
        service = new StudentCodeDemoService(
                studentPrincipalResolver, repository, composer, objectMapper);
        ReflectionTestUtils.setField(service, "em", em);

        when(studentPrincipalResolver.requireStudent(nullable(UserPrincipal.class)))
                .thenReturn(new StudentPrincipalResolver.ResolvedStudent(1L, "u", "n", "S001"));

        // loadTarget 的原生查询链
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyInt(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(
                java.util.Collections.singletonList(new Object[]{1L, "两数之和", STATEMENT, CODE}));
    }

    // 输入样例解析用例已迁移至 CodeDemoComposerTest（resolveStdin 逻辑随抽取而移动）

    // (a) 真实执行出 frames → PYTHON_TUTOR
    @Test
    void generateUsesRealExecutionWhenTraceHasFrames() {
        when(repository.findByStudentProfileIdAndOfferingIdAndProblemNo(1L, 100L, "P1"))
                .thenReturn(Optional.empty());
        TraceStep step = new TraceStep(1, 1, "step", "", Map.of("x", 5), Map.of(), Map.of(), false, null);
        when(sandboxService.execute(eq("c"), anyString(), any()))
                .thenReturn(new ExecutionTrace(true, "c", CODE, null, "7", "", List.of(step)));

        Map<String, Object> view = service.generate(100L, "P1", "3 4", true, (UserPrincipal) null);

        @SuppressWarnings("unchecked")
        Map<String, Object> demo = (Map<String, Object>) view.get("demonstration");
        assertEquals(AnimationWorkflow.PYTHON_TUTOR.name(), demo.get("workflow"));
        assertFalse(((List<?>) demo.get("frames")).isEmpty());
        verify(repository).save(any(StudentCodeDemoEntity.class));
        verify(conceptStepsWorkflow, never()).generate(any(), anyInt());
    }

    // (b) 执行失败 → CONCEPT_STEPS 兜底
    @Test
    void generateFallsBackToConceptStepsWhenExecutionFails() {
        when(repository.findByStudentProfileIdAndOfferingIdAndProblemNo(1L, 100L, "P1"))
                .thenReturn(Optional.empty());
        when(sandboxService.execute(eq("c"), anyString(), any()))
                .thenReturn(ExecutionTrace.failed("c", CODE, "compile error"));
        when(conceptStepsWorkflow.generate(any(), eq(0)))
                .thenReturn(new AnimationResult(
                        AnimationWorkflow.CONCEPT_STEPS.name(),
                        "概念演示",
                        "解释",
                        List.of(Map.of("order", 1, "line", 1)),
                        Map.of("sourceCode", CODE, "errorLine", 0, "correctedCode", "")));

        Map<String, Object> view = service.generate(100L, "P1", "3 4", true, (UserPrincipal) null);

        @SuppressWarnings("unchecked")
        Map<String, Object> demo = (Map<String, Object>) view.get("demonstration");
        assertEquals(AnimationWorkflow.CONCEPT_STEPS.name(), demo.get("workflow"));
        assertFalse(((List<?>) demo.get("frames")).isEmpty());
        verify(repository).save(any(StudentCodeDemoEntity.class));
    }

    // (d) 缓存命中且非强制 → 直接返回缓存，不再执行
    @Test
    void generateReturnsCacheWhenNotForced() throws Exception {
        StudentCodeDemoEntity cached = new StudentCodeDemoEntity();
        cached.setStudentProfileId(1L);
        cached.setOfferingId(100L);
        cached.setProblemNo("P1");
        cached.setStatus("COMPLETED");
        cached.setWorkflow(AnimationWorkflow.PYTHON_TUTOR.name());
        cached.setStdinText("3 4");
        cached.setTitle("两数之和");
        cached.setFramesJson(objectMapper.writeValueAsString(
                Map.of("id", "code-demo", "workflow", "PYTHON_TUTOR", "frames", List.of(Map.of("order", 1)))));
        when(repository.findByStudentProfileIdAndOfferingIdAndProblemNo(1L, 100L, "P1"))
                .thenReturn(Optional.of(cached));

        Map<String, Object> view = service.generate(100L, "P1", null, false, (UserPrincipal) null);

        assertEquals("COMPLETED", view.get("status"));
        assertEquals("3 4", view.get("stdin"));
        verify(sandboxService, never()).execute(anyString(), anyString(), any());
        verify(repository, never()).save(any());
    }

    // (d) force=true 时即使有缓存也重新生成
    @Test
    void generateRegeneratesWhenForced() {
        StudentCodeDemoEntity cached = new StudentCodeDemoEntity();
        cached.setStatus("COMPLETED");
        when(repository.findByStudentProfileIdAndOfferingIdAndProblemNo(1L, 100L, "P1"))
                .thenReturn(Optional.of(cached));
        TraceStep step = new TraceStep(1, 1, "step", "", Map.of("x", 5), Map.of(), Map.of(), false, null);
        when(sandboxService.execute(eq("c"), anyString(), any()))
                .thenReturn(new ExecutionTrace(true, "c", CODE, null, "7", "", List.of(step)));

        service.generate(100L, "P1", "3 4", true, (UserPrincipal) null);

        verify(sandboxService).execute(eq("c"), anyString(), any());
        verify(repository).save(any(StudentCodeDemoEntity.class));
        assertTrue(true);
    }
}
