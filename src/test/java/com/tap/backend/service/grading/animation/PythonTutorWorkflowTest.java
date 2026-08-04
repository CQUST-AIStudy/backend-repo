package com.tap.backend.service.grading.animation;

import com.tap.backend.service.animation.AnimationAiClient;
import com.tap.backend.service.grading.animation.execution.CodeExecutionSandboxService;
import com.tap.backend.service.grading.animation.execution.ExecutionTrace;
import com.tap.backend.service.grading.animation.execution.TraceStep;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PythonTutorWorkflow 执行前代码补强逻辑测试：
 * 规则补宏/头文件、LLM 生成调用 main、忠实度校验拒绝失真、LLM 不可用回退空壳。
 */
@ExtendWith(MockitoExtension.class)
class PythonTutorWorkflowTest {

    @Mock
    private CodeExecutionSandboxService sandboxService;
    @Mock
    private AnimationAiClient aiClient;

    private PythonTutorWorkflow newWorkflow() {
        return new PythonTutorWorkflow(sandboxService, aiClient, true);
    }

    private AnimationCandidate candidate(String code, ErrorPatternDetector.ErrorType type) {
        CodeContext ctx = new CodeContext(List.of(code.split("\n")), 1, 1, 1, 1, "raw");
        return new AnimationCandidate(
                null,
                new AnimationCandidate.AnnotationInfo("ERROR", "ev1", "anchor", "批注描述", false),
                null,
                ctx,
                null,
                type);
    }

    @Test
    void rulePrepAddsMissingMacro() {
        PythonTutorWorkflow wf = newWorkflow();
        String code = "#include <stdio.h>\n"
                + "int main() {\n"
                + "    int a[MAX];\n"
                + "    a[0] = 1;\n"
                + "    return 0;\n"
                + "}\n";
        AnimationCandidate c = candidate(code, ErrorPatternDetector.ErrorType.ARRAY_BOUNDS);
        PythonTutorWorkflow.ExecutablePrep prep = wf.prepareExecutableCode(c, code);
        assertTrue(prep.code().contains("#define MAX 100"), "应补缺失宏 MAX");
        assertTrue(prep.code().contains(code), "原文必须原样保留");
        assertEquals(1, prep.lineOffset(), "宏定义位于原文前 1 行");
    }

    @Test
    void llmPrepGeneratesCallingMain() {
        PythonTutorWorkflow wf = newWorkflow();
        when(aiClient.isChatAvailable()).thenReturn(true);
        String code = "void sort_desc(int a[], int n) {\n"
                + "    int i, j, t;\n"
                + "    for (i = 0; i < n; i++) {\n"
                + "        for (j = 0; j < n - i; j++) {\n"
                + "            if (a[j] < a[j + 1]) {\n"
                + "                t = a[j];\n"
                + "                a[j] = a[j + 1];\n"
                + "                a[j + 1] = t;\n"
                + "            }\n"
                + "        }\n"
                + "    }\n"
                + "}\n";
        String llm = "#include <stdio.h>\n" + code
                + "\nint main() {\n"
                + "    int arr[5] = {5, 3, 9, 1, 7};\n"
                + "    sort_desc(arr, 5);\n"
                + "    return 0;\n"
                + "}\n";
        when(aiClient.chat(anyString(), anyString(), anyDouble())).thenReturn(llm);

        AnimationCandidate c = candidate(code, ErrorPatternDetector.ErrorType.ARRAY_BOUNDS);
        PythonTutorWorkflow.ExecutablePrep prep = wf.prepareExecutableCode(c, code);

        assertTrue(prep.code().contains("sort_desc(arr, 5)"), "LLM 补的 main 应真实调用学生函数");
        assertTrue(prep.code().contains(code), "原文必须连续保留");
        assertEquals(1, prep.lineOffset(), "LLM 在开头补了 1 行 include");
        verify(aiClient).chat(anyString(), anyString(), anyDouble());
    }

    @Test
    void llmPrepRejectedWhenOriginalRewritten() {
        PythonTutorWorkflow wf = newWorkflow();
        when(aiClient.isChatAvailable()).thenReturn(true);
        String code = "void f() {\n"
                + "    int x = 1;\n"
                + "    printf(\"%d\", x);\n"
                + "}\n";
        // LLM 修改了学生代码（x=999），原文不再连续保留
        String bad = "#include <stdio.h>\n"
                + "void f() {\n"
                + "    int x = 999;\n"
                + "    printf(\"%d\", x);\n"
                + "}\n"
                + "int main() { f(); return 0; }\n";
        when(aiClient.chat(anyString(), anyString(), anyDouble())).thenReturn(bad);

        AnimationCandidate c = candidate(code, ErrorPatternDetector.ErrorType.LOGIC_ERROR);
        PythonTutorWorkflow.ExecutablePrep prep = wf.prepareExecutableCode(c, code);

        assertTrue(prep.code().contains("auto-added for trace"), "失真时应回退空壳");
        assertFalse(prep.code().contains("int main() { f();"), "不应使用 LLM 补强结果");
    }

    @Test
    void llmUnavailableFallsBackToShell() {
        PythonTutorWorkflow wf = newWorkflow();
        when(aiClient.isChatAvailable()).thenReturn(false);
        String code = "void f() {}\n";
        AnimationCandidate c = candidate(code, ErrorPatternDetector.ErrorType.GENERIC_HIGHLIGHT);
        PythonTutorWorkflow.ExecutablePrep prep = wf.prepareExecutableCode(c, code);

        assertTrue(prep.code().contains("auto-added for trace"), "LLM 不可用应补空壳");
        assertTrue(prep.code().contains(code));
        verify(aiClient, never()).chat(anyString(), anyString(), anyDouble());
    }

    @Test
    void existingMainSkipsLlm() {
        PythonTutorWorkflow wf = newWorkflow();
        String code = "#include <stdio.h>\n"
                + "int main() {\n"
                + "    int a[MAX];\n"
                + "    return 0;\n"
                + "}\n";
        AnimationCandidate c = candidate(code, ErrorPatternDetector.ErrorType.ARRAY_BOUNDS);
        PythonTutorWorkflow.ExecutablePrep prep = wf.prepareExecutableCode(c, code);

        assertTrue(prep.code().contains("#define MAX 100"));
        assertTrue(prep.code().contains("int main("));
        verify(aiClient, never()).chat(anyString(), anyString(), anyDouble());
    }

    @Test
    void generateUsesPrepCodeAndProducesFrames() {
        PythonTutorWorkflow wf = newWorkflow();
        when(aiClient.isChatAvailable()).thenReturn(true);
        String code = "void sort_desc(int a[], int n) {\n"
                + "    int i, j, t;\n"
                + "    for (i = 0; i < n; i++) {\n"
                + "        for (j = 0; j < n - i; j++) {\n"
                + "            if (a[j] < a[j + 1]) {\n"
                + "                t = a[j];\n"
                + "                a[j] = a[j + 1];\n"
                + "                a[j + 1] = t;\n"
                + "            }\n"
                + "        }\n"
                + "    }\n"
                + "}\n";
        String llm = "#include <stdio.h>\n" + code
                + "\nint main() {\n"
                + "    int arr[5] = {5, 3, 9, 1, 7};\n"
                + "    sort_desc(arr, 5);\n"
                + "    return 0;\n"
                + "}\n";
        when(aiClient.chat(anyString(), anyString(), anyDouble())).thenReturn(llm);

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        TraceStep step = new TraceStep(1, 1, "step", "", Map.of("i", 0), Map.of(), Map.of(), false, "");
        when(sandboxService.execute(eq("c"), codeCaptor.capture(), anyString()))
                .thenReturn(new ExecutionTrace(true, "c", llm, "", "", "", List.of(step)));

        AnimationCandidate c = candidate(code, ErrorPatternDetector.ErrorType.ARRAY_BOUNDS);
        AnimationResult result = wf.generate(c, 0);

        assertNotNull(result);
        assertTrue(codeCaptor.getValue().contains("sort_desc(arr, 5)"), "沙箱收到的应是补强后的可执行代码");
        assertTrue(codeCaptor.getValue().contains(code), "补强代码必须保留原文");
        assertEquals(1, result.frames().size());
    }
}
