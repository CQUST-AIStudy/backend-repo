# PYTHON_TUTOR 从伪执行升级为真实执行可视化方案

> 本文档说明如何把 `service.grading.animation.PythonTutorWorkflow` 从「基于规则生成伪执行轨迹」升级为「真实代码执行可视化」，并在 `AnimationWorkflowRouter` 中让代码类错误优先走该工作流。

---

## 1. 现状分析

### 1.1 当前 `PYTHON_TUTOR` 工作流的问题

当前 `PythonTutorWorkflow.generate()` 的实现逻辑：

1. 根据 `ErrorPatternDetector` 识别出的错误类型（如 `ARRAY_BOUNDS`、`INVALID_POINTER`）进入不同分支。
2. 用 `ErrorParameterExtractor` 从 anchor_text 和代码片段中提取参数，如数组大小、循环上界、指针变量名。
3. 根据这些参数**手工构造**执行步骤（`buildStep`），生成节点/边状态。
4. 返回 `AnimationResult`，前端用 D3.js 或自定义组件渲染。

例如数组越界：

```java
for (int i = 0; i < arraySize; i++) {
    steps.add(buildStep(i + 1, anchorLine,
        "i = " + i + "，访问 arr[" + i + "]",
        Map.of("i", String.valueOf(i), "n", String.valueOf(arraySize)),
        arrayState(values, arraySize, i, false),
        false));
}
```

**这本质上是“根据错误类型写死的动画剧本”**，不是真实执行代码得到的轨迹。问题：

- **不通用**：只能处理预定义的几类错误，新错误类型需要新增分支。
- **不准确**：提取参数可能失败，导致动画和实际代码行为不一致。
- **无法处理复杂代码**：递归、函数调用、结构体、指针链、库函数调用都难以手工模拟。
- **学生不信任**：如果动画里的变量值和真实运行结果对不上，反而误导学生。

### 1.2 当前路由为什么不用 `PYTHON_TUTOR`

`AnimationWorkflowRouter.route()` 的优先级：

```java
if (candidate.codeContext() != null && !candidate.codeContext().fullLines().isEmpty()) {
    return AnimationWorkflow.CODE_HIGHLIGHT;
}
```

只要代码上下文不为空，就返回 `CODE_HIGHLIGHT`。`PYTHON_TUTOR` 虽然在 enum 中定义，但 **router 永远不会主动选择它**。

`CODE_HIGHLIGHT` 的问题是：
- 依赖 LLM 生成 `errorRanges`、`correctedCode`、`popupHtml`。
- 每次生成都要调 AI，成本高、延迟大（数秒到数十秒）。
- LLM 可能生成错误的行号、错误的修正代码、不准确的 D3 动画。
- 不适合批量批改场景（一份报告可能有多个代码错误）。

---

## 2. 升级目标

### 2.1 核心目标

1. **真实执行**：`PYTHON_TUTOR` 工作流基于代码真实执行得到 trace，而不是手工构造。
2. **优先路由**：代码类错误优先走 `PYTHON_TUTOR`，不再默认走 `CODE_HIGHLIGHT`。
3. **降级策略**：当真实执行不可行时（如缺少输入、沙箱失败、执行超时），再回退到 `CODE_HIGHLIGHT` 或 `GENERIC_HIGHLIGHT`。
4. **统一前端**：前端用同一套 Python Tutor 播放器渲染不同错误类型的执行轨迹。

### 2.2 预期效果

- 学生看到的动画 = 程序真实执行过程。
- 教师批改报告生成速度更快（不依赖 LLM 逐错误生成动画）。
- 支持更多错误类型，无需为每种错误手写分支。
- 成本和稳定性优于 LLM 生成动画。

---

## 3. 技术选型

### 3.1 方案 A：复用 Python Tutor 开源后端（推荐）

Python Tutor（Philip Guo）有开源实现：
- 仓库：`https://github.com/pythontutor/python-tutor`
- 支持语言：Python、Java、JavaScript、Ruby、C、C++
- 核心组件：
  - 前端：`js/pytutor.js` + `visualizer.js`
  - 后端执行器：`pg_logger.py`（Python）、`c_trace.py` / `cpp_trace.py`（C/C++）
  - 通信格式：JSON trace

#### 3.1.1 架构

```
┌─────────────────────────────────────────────────────┐
│  backend-repo (Java)                                │
│  CodeExecutionSandboxService                        │
│  ├─ 接收 {code, stdin, language}                    │
│  ├─ 写入临时文件                                     │
│  ├─ 调用 Python Tutor 执行后端（Docker 内）          │
│  └─ 返回 JSON trace                                 │
└────────────────────────┬────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────┐
│  Python Tutor Executor (Docker)                     │
│  ├─ C/C++: gcc + c_trace.py                         │
│  ├─ Python: pg_logger.py                            │
│  └─ 输出 JSON trace                                 │
└────────────────────────┬────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────┐
│  frontend-repo (Vue)                                │
│  PythonTutorPlayer.vue                              │
│  ├─ 解析 JSON trace                                 │
│  ├─ 渲染代码、变量、内存、调用栈                    │
│  └─ 播放/暂停/上一步/下一步                         │
└─────────────────────────────────────────────────────┘
```

#### 3.1.2 优点

- 成熟稳定，教育领域验证多年。
- 支持 C 语言（你们的实验报告主要是 C）。
- 前端渲染器现成可用。
- 准确度高：展示的是真实执行状态。

#### 3.1.3 缺点

- 需要部署 Python Tutor 执行器。
- C/C++ 执行需要 Docker 沙箱保障安全。
- 对复杂项目（多文件、外部库）支持有限，但学生实验代码通常是单文件。

---

### 3.2 方案 B：基于 GDB 自研 C 执行可视化

#### 3.2.1 架构

```
1. 提取学生 C 代码片段
2. gcc -g 编译成可执行文件
3. gdb --batch --command=script.gdb ./a.out
4. 解析 GDB 输出（MI 或 CLI）
5. 生成 trace JSON
6. 前端自定义渲染器展示
```

#### 3.2.2 优点

- 完全可控，可自定义展示字段。
- 不依赖 Python Tutor 的特定格式。
- 可以展示更底层的信息（汇编、寄存器、内存地址）。

#### 3.2.3 缺点

- 开发工作量大。
- GDB MI 输出解析复杂。
- 同样需要沙箱安全。
- 前端渲染器需要从头写。

---

### 3.3 方案 C：轻量级 C 解释器 / 符号执行

#### 3.3.1 思路

对学生常见错误模式（数组越界、空指针、死循环）做一个简化 C 解释器：
- 只支持 `int`、`char`、数组、指针、基本循环、条件判断。
- 不支持标准库函数（或只支持 `printf`/`scanf` 等少数函数）。
- 模拟执行并输出每一步状态。

#### 3.3.2 优点

- 实现快，无需外部依赖。
- 安全，因为是解释执行不是编译执行。

#### 3.3.3 缺点

- 只能处理简单代码。
- 学生实验代码一旦用了库函数就无法执行。
- 准确性和扩展性差。

---

## 4. 推荐方案

**推荐方案 A：复用 Python Tutor 开源后端。**

理由：
1. 你们的学生实验代码主要是单文件 C 程序，非常适合 Python Tutor。
2. 成熟方案，前端渲染器和后端执行器都现成，改造成本最低。
3. 教育效果最好，学生认可度最高。
4. 相比 LLM 生成 D3 动画，成本低、速度快、准确度高。

**方案 B 作为长期演进方向**：如果后续需要展示寄存器、汇编、操作系统相关细节，可以自研 GDB 方案。

---

## 5. 具体改造步骤

### 5.1 新增服务：`CodeExecutionSandboxService`

在 `backend-repo` 新增：

```java
package com.tap.backend.service.grading.animation.execution;

@Service
public class CodeExecutionSandboxService {

    /**
     * 执行 C 代码并返回 Python Tutor 格式的 trace JSON。
     */
    public ExecutionTrace executeC(String code, String stdin, long timeoutMs);

    /**
     * 执行 Python 代码（如果后续需要支持 Python 实验）。
     */
    public ExecutionTrace executePython(String code, String stdin, long timeoutMs);
}
```

`ExecutionTrace` 结构：

```java
public record ExecutionTrace(
    boolean success,
    String errorMessage,
    List<TraceStep> steps,
    String language
) {}

public record TraceStep(
    int stepNumber,
    int lineNumber,
    String stdout,
    Map<String, Variable> globals,
    Map<String, Variable> locals,
    List<StackFrame> stack,
    Heap heap,
    boolean isError,
    String errorMessage
) {}
```

### 5.2 Docker 沙箱

新增 `docker/sandbox/Dockerfile`：

```dockerfile
FROM python:3.11-slim

RUN apt-get update && apt-get install -y \
    gcc \
    g++ \
    gdb \
    && rm -rf /var/lib/apt/lists/*

# 安装 Python Tutor 执行器
RUN pip install --no-cache-dir requests
COPY pythontutor-backend /opt/pythontutor
WORKDIR /opt/pythontutor

USER nobody
ENTRYPOINT ["python", "c_trace.py"]
```

执行流程：

```java
public ExecutionTrace executeC(String code, String stdin, long timeoutMs) {
    // 1. 生成临时目录
    Path tmp = Files.createTempDirectory("code-exec-");
    Path source = tmp.resolve("main.c");
    Files.writeString(source, code);

    // 2. 调用 Docker 执行 Python Tutor C 后端
    ProcessBuilder pb = new ProcessBuilder(
        "docker", "run", "--rm",
        "--network", "none",
        "--memory", "64m",
        "--cpus", "0.5",
        "--timeout", String.valueOf(timeoutMs),
        "-v", tmp + ":/code:ro",
        "code-sandbox",
        "/code/main.c",
        stdin
    );

    // 3. 读取 stdout 中的 JSON trace
    Process process = pb.start();
    String output = readOutput(process, timeoutMs);
    return parseTrace(output);
}
```

### 5.3 改造 `PythonTutorWorkflow`

当前 `PythonTutorWorkflow.generate()` 是分支构造步骤。改造后：

```java
@Component
public class PythonTutorWorkflow {

    private final CodeExecutionSandboxService sandboxService;
    private final ErrorPatternDetector errorPatternDetector;

    public PythonTutorWorkflow(CodeExecutionSandboxService sandboxService,
                               ErrorPatternDetector errorPatternDetector) {
        this.sandboxService = sandboxService;
        this.errorPatternDetector = errorPatternDetector;
    }

    public AnimationResult generate(AnimationCandidate candidate, int index) {
        CodeContext ctx = candidate.codeContext();
        String sourceCode = ctx == null ? candidate.anchor() : ctx.fullCode();
        int anchorLine = ctx == null ? 1 : ctx.relativeAnchorLine();

        // 1. 真实执行代码
        ExecutionTrace trace = sandboxService.executeC(sourceCode, buildStdin(candidate), 5000);

        // 2. 如果执行失败（编译错误、沙箱异常），回退到通用高亮
        if (!trace.success()) {
            log.warn("真实执行失败，回退: {}", trace.errorMessage());
            return fallbackResult(candidate, anchorLine, trace.errorMessage());
        }

        // 3. 找到错误发生的那一步
        int errorStepIndex = findErrorStep(trace, anchorLine);

        // 4. 生成 AnimationResult
        return new AnimationResult(
            AnimationWorkflow.PYTHON_TUTOR.name(),
            buildTitle(candidate),
            buildExplanation(candidate),
            trace.steps(),
            Map.of(
                "errorType", candidate.detectedErrorType(),
                "sourceCode", sourceCode,
                "errorLine", anchorLine,
                "errorStepIndex", errorStepIndex,
                "trace", trace,
                "correctedCode", buildCorrectedCode(candidate)
            )
        );
    }

    private String buildStdin(AnimationCandidate candidate) {
        // 如果问题需要输入，可以从 problemContext 或 evidence 中提取
        // 默认空输入
        return "";
    }

    private int findErrorStep(ExecutionTrace trace, int anchorLine) {
        for (int i = 0; i < trace.steps().size(); i++) {
            TraceStep step = trace.steps().get(i);
            if (step.isError() || step.lineNumber() == anchorLine) {
                return i;
            }
        }
        return trace.steps().size() - 1;
    }
}
```

### 5.4 改造 `AnimationWorkflowRouter`

让代码类错误优先走 `PYTHON_TUTOR`，失败后回退到 `CODE_HIGHLIGHT`。

```java
@Component
public class AnimationWorkflowRouter {

    private final CodeExecutionSandboxService sandboxService;

    public AnimationWorkflowRouter(CodeExecutionSandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    public AnimationWorkflow route(AnimationCandidate candidate) {
        ErrorType errorType = candidate.detectedErrorType();

        // 1. 代码类错误优先 PYTHON_TUTOR
        if (isCodeError(errorType) && hasRunnableCode(candidate)) {
            // 快速判断代码能否执行（如是否包含 main 函数、是否单文件）
            if (canExecute(candidate)) {
                return AnimationWorkflow.PYTHON_TUTOR;
            }
        }

        // 2. 无法真实执行时，走 CODE_HIGHLIGHT（LLM 生成）
        if (candidate.codeContext() != null && !candidate.codeContext().fullLines().isEmpty()) {
            return AnimationWorkflow.CODE_HIGHLIGHT;
        }

        // 3. 其他类型按原逻辑
        return switch (errorType) {
            case RESULT_MISMATCH -> AnimationWorkflow.RESULT_COMPARE;
            case CONCEPT -> AnimationWorkflow.HTML_ANIMATION;
            default -> defaultRoute(candidate);
        };
    }

    private boolean isCodeError(ErrorType errorType) {
        return errorType == ARRAY_BOUNDS || errorType == INVALID_POINTER
            || errorType == INFINITE_LOOP || errorType == MEMORY_LEAK
            || errorType == RECURSION || errorType == RUNTIME_ERROR
            || errorType == TYPE_ERROR || errorType == LOGIC_ERROR;
    }

    private boolean hasRunnableCode(AnimationCandidate candidate) {
        CodeContext ctx = candidate.codeContext();
        return ctx != null && ctx.fullCode() != null
            && ctx.fullCode().contains("int main(");
    }

    private boolean canExecute(AnimationCandidate candidate) {
        // 可以先用简单规则判断，如：
        // - 是否包含 main 函数
        // - 是否只使用了允许的标准库函数
        // - 代码长度是否在限制内
        String code = candidate.codeContext().fullCode();
        return code.contains("int main(") && code.length() < 10000;
    }
}
```

### 5.5 执行失败时的降级

`PythonTutorWorkflow` 在调用 `sandboxService.executeC()` 失败时，返回一个标记为 fallback 的 `AnimationResult`，外层 `GradingErrorDemonstrationService` 可以选择：

1. 接受 fallback 结果（显示错误说明，不展示动画）。
2. 用 `CODE_HIGHLIGHT` 重试一次。

推荐策略：

```java
private AnimationResult executeWorkflow(AnimationWorkflow workflow, AnimationCandidate candidate, int index) {
    return switch (workflow) {
        case PYTHON_TUTOR -> {
            AnimationResult result = pythonTutorWorkflow.generate(candidate, index);
            if (isPythonTutorFallback(result)) {
                yield codeHighlightWorkflow.generate(candidate, index);
            }
            yield result;
        }
        case CODE_HIGHLIGHT -> codeHighlightWorkflow.generate(candidate, index);
        case HTML_ANIMATION -> htmlAnimationWorkflow.generate(candidate, index);
        case RESULT_COMPARE -> resultCompareWorkflow.generate(candidate, index);
        case GENERIC_HIGHLIGHT -> genericHighlightWorkflow.generate(candidate, index);
    };
}
```

### 5.6 前端播放器

新增 `frontend-repo/src/components/grading/PythonTutorPlayer.vue`：

```vue
<template>
  <div class="python-tutor-player">
    <div class="code-panel">
      <div
        v-for="(line, idx) in lines"
        :key="idx"
        :class="['code-line', { active: currentStep.lineNumber === idx + 1 }]"
      >
        <span class="line-num">{{ idx + 1 }}</span>
        <span class="line-content">{{ line }}</span>
      </div>
    </div>
    <div class="state-panel">
      <VariablePanel :variables="currentStep.locals" />
      <HeapPanel :heap="currentStep.heap" />
      <StackPanel :stack="currentStep.stack" />
    </div>
    <div class="control-bar">
      <button @click="prevStep">上一步</button>
      <button @click="togglePlay">{{ playing ? '暂停' : '播放' }}</button>
      <button @click="nextStep">下一步</button>
      <span>第 {{ currentStepIndex + 1 }} / {{ steps.length }} 步</span>
    </div>
  </div>
</template>
```

数据来自后端返回的 `trace` 字段。

---

## 6. 数据模型与 API

### 6.1 新增/修改字段

`ErrorDemonstration` 已经支持 `frames` 和 `popupHtml`。对于真实执行轨迹，主要数据放在 `frames` 中：

```java
public record ErrorDemonstration(
    String id,
    String errorType,
    String title,
    String explanation,
    String sourceCode,
    String correctedCode,
    int errorLine,
    List<Map<String, Object>> frames,   // ← 真实 trace steps
    String workflow,                    // "PYTHON_TUTOR"
    int highlightStartLine,
    int highlightEndLine,
    ProblemContext problemContext,
    int anchorLineInEvidence,
    List<Map<String, Object>> errorRanges,
    String popupHtml
) {}
```

`frames` 中每个元素就是一个 `TraceStep` 的 JSON 表示。

### 6.2 新增内部 API

```java
// 供 PythonTutorWorkflow 内部使用
POST /internal/grading/animation/execute
Body: { "language": "c", "code": "...", "stdin": "..." }
Response: { "success": true, "steps": [...], "errorMessage": null }
```

不建议直接暴露给学生端，防止被滥用执行任意代码。

---

## 7. 安全设计

### 7.1 沙箱隔离

- 使用 Docker 容器执行代码。
- 容器内使用非 root 用户（`nobody`）。
- 禁止网络访问（`--network none`）。
- 限制 CPU、内存、磁盘使用。
- 设置执行超时（默认 5 秒，最大 10 秒）。

### 7.2 静态过滤

执行前检查代码：
- 禁止 `#include` 系统敏感头文件（如 `unistd.h`、`sys/socket.h`、`windows.h`）。
- 禁止调用危险函数（`system`、`exec`、`fork`、`popen`、`remove` 等）。
- 限制代码长度（如最大 10000 字符）。

### 7.3 资源清理

- 每次执行后删除临时文件和容器。
- 使用唯一临时目录，防止路径冲突。

---

## 8. 与现有 LLM 动画的关系

| 工作流 | 升级前 | 升级后 |
|---|---|---|
| `PYTHON_TUTOR` | 伪执行，几乎不用 | 真实执行，代码错误首选 |
| `CODE_HIGHLIGHT` | 代码错误首选 | 无法真实执行时的降级方案 |
| `HTML_ANIMATION` | 概念类错误 | **已弃用**（`@Deprecated`，保留兼容，不再被路由） |
| `CONCEPT_STEPS` | — | 概念类错误改走此工作流（大模型产出结构化步骤，固定引擎渲染） |
| `RESULT_COMPARE` | 结果不匹配 | 结果不匹配 |
| `GENERIC_HIGHLIGHT` | 兜底 | 兜底 |

> **更新（2026-07-24）**：概念/原理类错误的动画已从「大模型直出整段 HTML」（`HTML_ANIMATION`）改为
> 「大模型只产出结构化步骤 JSON，交由前端固定引擎渲染」（`CONCEPT_STEPS`，即"路 B"）。
> 设计与实现详见 **[concept-steps-animation.md](concept-steps-animation.md)**。本文档 §5.4 / §5.5 中
> `case CONCEPT -> HTML_ANIMATION` 及 text/vlm 默认路由的片段为当时方案，现实以新文档为准。

---

## 9. 任务清单

| 编号 | 任务 | 优先级 | 说明 |
|---|---|---|---|
| T1 | 部署 Python Tutor C 后端 Docker 镜像 | P0 | 先让 C 代码能真实执行 |
| T2 | 实现 `CodeExecutionSandboxService` | P0 | Java 调用 Docker 执行代码 |
| T3 | 定义 `ExecutionTrace` 数据结构 | P0 | 统一 trace 格式 |
| T4 | 重写 `PythonTutorWorkflow` | P0 | 基于真实 trace 生成动画 |
| T5 | 修改 `AnimationWorkflowRouter` | P0 | 代码错误优先 PYTHON_TUTOR |
| T6 | 实现执行失败降级到 CODE_HIGHLIGHT | P1 | 保证兜底可用 |
| T7 | 前端新增 `PythonTutorPlayer.vue` | P0 | 渲染 trace |
| T8 | 在 `SubmissionReview` / 学生端接入播放器 | P1 | 展示动画 |
| T9 | 安全策略：危险函数/头文件过滤 | P1 | 防止恶意代码 |
| T10 | 性能测试与超时处理 | P1 | 批量批改场景 |
| T11 | 废弃旧伪执行分支代码 | P2 | 清理 `buildArrayBounds` 等手工构造逻辑 |

---

## 10. 验收标准

- [ ] 包含 `int main()` 的 C 代码片段可以被真实执行。
- [ ] 数组越界错误能在真实执行轨迹中高亮越界访问的那一步。
- [ ] 未初始化指针错误能在真实执行轨迹中展示指针指向无效地址。
- [ ] 死循环代码能在执行超时后停止，并标记为死循环。
- [ ] 无法执行的代码自动降级到 `CODE_HIGHLIGHT`。
- [ ] 前端播放器支持：播放、暂停、上一步、下一步。
- [ ] 沙箱能阻止 `system("rm -rf /")` 等恶意代码。
- [ ] 批量生成 10 份报告的动画，平均耗时 < 30 秒/份。

---

## 11. 风险与应对

| 风险 | 应对 |
|---|---|
| Docker 环境部署复杂 | 提供 `docker-compose.yml`，本地开发直接用 |
| 学生代码无法编译 | 降级到 `CODE_HIGHLIGHT`，并记录失败原因 |
| 执行超时 | 设置 5 秒超时，超时后标记为死循环或复杂代码 |
| 沙箱被绕过 | 多层防护：静态过滤 + Docker 限制 + 非 root 用户 |
| 前端播放器开发工作量大 | 先用简化版（代码 + 变量面板），再逐步增强 |

---

## 12. 总结

建议把 `PYTHON_TUTOR` 升级为**基于 Python Tutor 开源后端的真实 C 代码执行可视化**，并在路由中让代码类错误优先走该路径。这样可以把当前「LLM 生成 D3 动画」的高成本、低确定性方案，降级为兜底方案，从而提升批改系统的准确性、速度和可维护性。
