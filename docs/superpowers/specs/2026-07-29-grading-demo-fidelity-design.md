# 批改代码演示保真度修复（禁止编造 + 完整代码 artifact）设计

- 状态：已通过多轮讨论确认方向（用户核准两层修复；本 spec 固化设计后直接执行）
- 影响仓库：`backend-repo`（后端 Java 为主；worker 侧仅按需）
- 关联证据（生产库 submission 19 实测）：`error_demonstrations_json` 存的是与报告无关的 C 语言「图DFS未标记visited」概念动画，而该提交真实证据全是 Python/PyTorch（代码几乎全在 `vlm` 截图证据里，无 `kind=code` 文本块）。

## 根因（已定位）

1. 演示候选拿不到「真实、完整的学生代码」时，`ConceptStepsWorkflow` 的系统 prompt 明确允许「没有学生代码时可用 C/伪代码自行编写示意代码」→ LLM 在"数据结构"课程背景下**编造了无关的 DFS 演示**并被持久化。
2. 为什么拿不到真实代码：本类报告代码在**截图**里（`kind=vlm`），而 `CodeContextExtractor` 的函数识别正则是 **C 专用**（`int|void|char|struct…`），抽不动 Python；`GradingErrorDemonstrationService` 只把「被批注引用的碎片证据」喂给 `LLMCodeExtractor`，且 `LLMCodeExtractor` prompt 偏 C。

## 目标

- **第一层（止血）**：演示**绝不编造**与学生无关的代码/结构。拿不到真实代码就不产出该演示（宁缺毋滥）。
- **第二层（治本）**：批改时把报告中识别到的代码（**含截图 OCR/VLM**）按提交聚合成**完整代码**并落库为 artifact；演示直接取它；**语言感知**（Python 不强行走 gcc 真实执行）。

---

## 第一层：禁止编造（guardrail）

### 变更点
- `ConceptStepsWorkflow.java`
  - `SYSTEM_PROMPT` 的 `sourceCode` 规则改为：**「没有【学生代码】时，sourceCode 必须为空字符串，禁止虚构或套用与学生无关的示例代码/数据结构」**；并新增：无学生代码时 steps 只能基于【教师批注】做纯概念文字讲解，不得画出具体的无关代码/节点。
  - `generate(...)`：开头判断 `extractStudentCode(candidate).isBlank()`；若为空 → **直接走 `conceptTextResult`（纯文字概念帧，来自 note，无 sourceCode/无 nodes/edges）**，不调用 LLM 画代码动画（确定性防编造）。新增私有方法 `conceptTextResult(candidate, topic)`（基于现有 `staticFrame` + `fallbackResult` 结构，仅含文字）。
- `GradingErrorDemonstrationService.java`
  - `buildCandidate(...)`：把「代码上下文为空则跳过」的判定从「仅 `isCodeError`」**放宽到所有候选**——即 `codeContext == null || codeContext.fullLines().isEmpty()` → 返回 null 跳过（`buildCandidateFromCommentIssue` 已有此校验，保持一致）。
  - 效果：无真实代码的候选**不产出演示**；宁可该提交暂时 0 条演示，也不误导。第二层补齐真实代码后，演示会正确出现。

### 兼容影响
- 学生端「按题演示」(`StudentCodeDemoService`，PTA 代码) 与 AI 助教 Playground（手动粘贴）本就有真实代码，不受影响；ConceptSteps 作为其兜底时也总有真实代码。
- 存量错配演示：`refreshReviewAndAnnotatedReport` 重新生成即覆盖。

---

## 第二层：完整代码 artifact + 语言感知

### 数据存储
- `grading_submission` 新增列 `extracted_code_json LONGTEXT NULL`（Flyway 迁移，版本号实现时现查最新 +1，避免撞号）。
- 内容为 JSON 数组，每段一个代码片段：
  ```json
  [{"language":"python|c|unknown","title":"可选题目/小节名","code":"完整代码","evidenceIds":["ev-...-0009","ev-...-0013"]}]
  ```
- 实体 `GradingSubmissionEntity` 增加 `extractedCodeJson` 字段 + getter/setter。

### 代码聚合（生成时机：批改终态/生成批注报告时）
- 在 `GradingSubmissionService` 新增 `ensureExtractedCode(submission, evidenceBlocks)`：
  - 收集「代码类证据」：`kind=code`（文本代码块）+ `kind=vlm` 且 `image_kind ∈ {code_screenshot, terminal_log}` + `kind=text` 且内容 looksLikeCode。
  - 交给增强后的 `LLMCodeExtractor` 聚合为**按段完整代码 + 语言**；结果序列化写入 `extracted_code_json`（幂等：已存在且非空则跳过，除非 `refresh`）。
  - 在 `createAnnotatedReport` / `persistErrorDemonstrations` 之前调用，确保演示可取用。
- `LLMCodeExtractor.java` 增强：
  - prompt 改为**语言无关**：先判定语言（python/c/unknown），**保留原语言**；仅当为 C 且缺 main 时才补最小 main；Python 保持原样，不得改写成 C。
  - 输出结构扩展为携带 language（如 `{"segments":[{"language":"python","code":"...","evidence_ids":[...]}]}`），并保留对既有 `extractFullCode(title, blocks) -> Map<evidenceId, code>` 调用方的兼容（新增一个返回带语言的方法 `extractSegments(...)`，旧方法保留或改为委托）。

### 演示消费
- `GradingErrorDemonstrationService`：
  - 生成前先读取 `submission.extractedCodeJson`；`resolveCodeContext(block, ann, ...)` 优先用「与该批注 evidenceId/anchor 匹配的完整代码段」作为 `CodeContext`，取不到再回落现有逻辑。
  - **语言感知路由**：代码段语言为 Python（或含 `import`/`def`/`torch` 等）时，**不路由到 PYTHON_TUTOR 真实执行（gcc/C）**，直接走 CONCEPT_STEPS 基于真实 Python 代码做概念动画；C 走原路径。路由判断加在 `AnimationWorkflowRouter` 或候选构造处（择一，实现时定）。

### CodeContextExtractor 语言适配（最小）
- `FUNCTION_START` 增补 Python 边界识别（`def \w+\(` / 顶层赋值段落），或当语言为 python 时改用「整段返回」策略（不用 C 的大括号配对）。避免 Python 代码被判为「非代码」而丢弃。

---

## 测试
- 后端单测：
  - `ConceptStepsWorkflow`：无学生代码 → 返回纯文字概念帧、`sourceCode` 为空、无 nodes（不编造）。
  - `GradingErrorDemonstrationService`：候选无真实代码 → 跳过（不产出）；有 `extractedCodeJson` 匹配段 → 用完整代码作为 codeContext。
  - `LLMCodeExtractor`：Python 证据 → 输出 language=python 且不被改写为 C（Mock aiClient 返回受控 JSON 验证解析）。
- 编译 + 相关测试通过；不破坏既有 `GradingSubmissionServiceTest` 等。

## 明确不做（YAGNI）
- 不改 worker 的 BM25/评分链路（评分仍用检索片段，本 spec 只解决「演示代码保真」）。
- 不引入向量库/RAG 到批改。
- 不做 Python 真实执行沙箱（Python torch 无法真实跑；用基于真实代码的概念动画）。

## 假设与依赖
- LLM 可用（121 已配 DeepSeek）；不可用时 `LLMCodeExtractor` 返回空 → 第一层保证「不编造、跳过」。
- 迁移版本号实现时现查最新（曾多次撞号）。
- 前后端需重建（部署由用户负责）。
