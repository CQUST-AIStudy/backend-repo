package com.tap.backend.service.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.animation.StudentCodePlaygroundEntity;
import com.tap.backend.repo.StudentCodePlaygroundRepository;
import com.tap.backend.security.StudentPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.grading.animation.AnimationWorkflow;
import com.tap.backend.service.grading.animation.ConceptStepsWorkflow;
import com.tap.backend.service.grading.animation.execution.CodeExecutionSandboxService;
import com.tap.backend.service.grading.animation.execution.ExecutionTrace;
import com.tap.backend.service.grading.animation.execution.TraceStep;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CodePlaygroundServiceTest {

    @Mock StudentPrincipalResolver resolver;
    @Mock StudentCodePlaygroundRepository repository;
    @Mock CodeExecutionSandboxService sandbox;
    @Mock ConceptStepsWorkflow concept;
    @Mock AnimationAiClient ai;
    @Mock PlatformTransactionManager transactionManager;

    private CodePlaygroundService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String CODE = "int main(){int x=1;return 0;}";

    @BeforeEach
    void setUp() {
        CodeDemoComposer composer = new CodeDemoComposer(sandbox, concept, ai);
        // 同步执行器：让异步生成在测试中立即运行，便于断言最终状态
        Executor syncExecutor = Runnable::run;
        // 真实事务模板 + mock 事务管理器：executeWithoutResult 为 final 方法，不可 stub
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        service = new CodePlaygroundService(resolver, repository, composer, objectMapper,
                syncExecutor, transactionTemplate);
        when(resolver.requireStudent(nullable(UserPrincipal.class)))
                .thenReturn(new StudentPrincipalResolver.ResolvedStudent(1L, "u", "n", "S001"));
        when(repository.save(any(StudentCodePlaygroundEntity.class))).thenAnswer(inv -> {
            StudentCodePlaygroundEntity e = inv.getArgument(0);
            e.setId(9L);
            return e;
        });
    }

    @Test
    void generateReturnsProcessingThenCompletesAsync() {
        TraceStep step = new TraceStep(1, 1, "step", "", Map.of("x", 1), Map.of(), Map.of(), false, null);
        when(sandbox.execute(eq("c"), anyString(), any()))
                .thenReturn(new ExecutionTrace(true, "c", CODE, null, "", "", List.of(step)));
        when(repository.findById(9L)).thenAnswer(inv -> {
            StudentCodePlaygroundEntity e = new StudentCodePlaygroundEntity();
            e.setId(9L);
            e.setStatus("PROCESSING");
            return Optional.of(e);
        });

        Map<String, Object> view = service.generate("t", "题面\n输入样例\n1\n输出样例\n", CODE, "1", (UserPrincipal) null);

        // 接口立即返回 PROCESSING 记录（演示帧由前端轮询获取）
        assertEquals(9L, view.get("id"));
        assertEquals("PROCESSING", view.get("status"));
        // 异步任务随后回填帧数据并置 COMPLETED
        org.mockito.ArgumentCaptor<StudentCodePlaygroundEntity> captor =
                org.mockito.ArgumentCaptor.forClass(StudentCodePlaygroundEntity.class);
        verify(repository, atLeast(2)).save(captor.capture());
        StudentCodePlaygroundEntity completed = captor.getAllValues().stream()
                .filter(e -> "COMPLETED".equals(e.getStatus()))
                .findFirst().orElseThrow();
        assertEquals(AnimationWorkflow.PYTHON_TUTOR.name(), completed.getWorkflow());
    }

    @Test
    void generateRunsAsyncAndCompletes() {
        TraceStep step = new TraceStep(1, 1, "step", "", Map.of("x", 1), Map.of(), Map.of(), false, null);
        when(sandbox.execute(eq("c"), anyString(), any()))
                .thenReturn(new ExecutionTrace(true, "c", CODE, null, "", "", List.of(step)));
        when(repository.findById(9L)).thenAnswer(inv -> {
            StudentCodePlaygroundEntity e = new StudentCodePlaygroundEntity();
            e.setId(9L);
            e.setStatus("PROCESSING");
            return Optional.of(e);
        });

        service.generate("t", null, CODE, "1", (UserPrincipal) null);

        // 异步任务应回填帧数据并置 COMPLETED
        verify(repository).findById(9L);
        org.mockito.ArgumentCaptor<StudentCodePlaygroundEntity> captor =
                org.mockito.ArgumentCaptor.forClass(StudentCodePlaygroundEntity.class);
        verify(repository, atLeast(1)).save(captor.capture());
        StudentCodePlaygroundEntity completed = captor.getAllValues().stream()
                .filter(e -> "COMPLETED".equals(e.getStatus()))
                .findFirst().orElseThrow();
        assertEquals(AnimationWorkflow.PYTHON_TUTOR.name(), completed.getWorkflow());
    }

    @Test
    void detailRejectsOtherStudent() {
        when(repository.findByIdAndStudentNo(9L, "S001")).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> service.detail(9L, (UserPrincipal) null));
    }

    @Test
    void emptyCodeRejected() {
        assertThrows(ResponseStatusException.class,
                () -> service.generate("t", "p", "   ", null, (UserPrincipal) null));
    }
}
