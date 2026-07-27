package com.tap.backend.service.grading.animation;

import com.tap.backend.service.grading.animation.ErrorPatternDetector.ErrorType;
import com.tap.backend.service.grading.animation.execution.CodeExecutionSandboxService;
import org.springframework.stereotype.Component;

/**
 * 根据错误类型和证据特征选择动画工作流。
 * <p>
 * 统一后的路由策略只有两条生产线：
 * <ul>
 *   <li>代码类错误（数组越界、指针、死循环等）且能提取到可执行代码、且沙箱可用时，
 *       使用 PYTHON_TUTOR 真实执行可视化；</li>
 *   <li>其余全部走 CONCEPT_STEPS：大模型只产出结构化步骤数据（代码行 ↔ 画面 ↔ 字幕），
 *       由前端固定渲染器统一呈现。</li>
 * </ul>
 * PYTHON_TUTOR 执行失败时由 {@link com.tap.backend.service.GradingErrorDemonstrationService}
 * 回退到 CONCEPT_STEPS。
 */
@Component
public class AnimationWorkflowRouter {

    private final CodeExecutionSandboxService sandboxService;

    public AnimationWorkflowRouter(CodeExecutionSandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    public AnimationWorkflow route(AnimationCandidate candidate) {
        ErrorType errorType = candidate.detectedErrorType();

        // 代码类错误优先走 PYTHON_TUTOR 真实执行可视化
        if (isCodeError(errorType) && hasExecutableCode(candidate) && sandboxService.isAvailable("c")) {
            return AnimationWorkflow.PYTHON_TUTOR;
        }

        // 其余（概念/结果/无法执行的代码错误）统一走结构化步骤动画
        return AnimationWorkflow.CONCEPT_STEPS;
    }

    private boolean isCodeError(ErrorType errorType) {
        if (errorType == null) {
            return false;
        }
        return switch (errorType) {
            case ARRAY_BOUNDS, INVALID_POINTER, INFINITE_LOOP, MEMORY_LEAK,
                    RECURSION, RUNTIME_ERROR, TYPE_ERROR, LOGIC_ERROR -> true;
            default -> false;
        };
    }

    private boolean hasExecutableCode(AnimationCandidate candidate) {
        CodeContext ctx = candidate.codeContext();
        if (ctx == null || ctx.fullCode() == null || ctx.fullCode().isBlank()) {
            return false;
        }
        // 目前支持 C 语言单文件程序；缺 main 时由 PythonTutorWorkflow 自动补最小 main 壳
        return ctx.fullCode().length() < 20000;
    }
}
