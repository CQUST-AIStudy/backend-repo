# 批改模块现状问题梳理

> 适用范围：`backend-repo`（Spring Boot 编排 + 批注 PDF 渲染 + 封面成绩表 + 动画）与 `backend-repo/grading-worker`（Python/Celery 解析→证据→VLM/OCR→LLM 评分），以及 `frontend-repo` 的 AI 批改中心页面。
> 目的：定位当前批改链路存在的问题，重点解释「目标1 满分、目标2 零分」的成因，并给出后续改造方向（队列批改、前端实时进度、批改过程中预生成批注报告与演示动画）。

---

## 0. 一句话结论

- **「目标1=20、目标2=空」并不是 VLM 对首页识别后填进去的，封面成绩表里的每个目标分数是后端在渲染批注 PDF 时，用「目标 + sortOrder」字符串去匹配教师评分标准维度后“算/填”出来的。**当前实现里这一步存在 **off-by-one（sortOrder 从 0 开始）**、**评分维度与封面目标表不对齐**、以及 **随机分配兜底** 三个叠加缺陷，直接导致了图 2 的现象。
- 学生报告 **首页并没有走 VLM 识别**。`parse_rubric` 这个 VLM 能力只用于「教师上传评分标准图片」（`/parse-rubric-image`），与学生报告封面表无关。用户的预期（首页让 VLM 识别）与现状不符，这本身就是一个需要补齐的能力缺口。

---

## 1. 当前批改链路（现状梳理）

### 1.1 上传与建任务
- 入口：`GradingTaskService.createTask`。一次上传 = 一个 batch，按文件生成多个 `GradingSubmissionEntity`（状态 PENDING），文件并行写入 MinIO。
- 事务提交后 `publishTaskToQueue` 把每份提交以 JSON `rightPush` 到 Redis 队列 `QUEUE_KEY`，任务转 PROCESSING。

### 1.2 队列与 worker
- 已经是 **队列批改**：Redis list 作为队列，**Celery worker**（`celery_app.py`，队列 `grading`，`task_acks_late`，prefetch=1）消费，单提交走 `tasks.py::process_submission`。
- 流程：下载 → `parse_document` 逐页文本/图片 → `classify_image` + `call_vlm`(analyze/describe) 或 `run_ocr` 抽证据 → `build_evidence_packs` → `score_dimensions_batch`/`score_dimension` LLM 评分 → 写 `ScoreItem` → `calculate_weighted_total` 归一到百分制 → 提交置 SCORED → `_notify_result` 通过 Redis `publish` 到 `grading:results`。
- 注意：`main.py` 是独立的 **FastAPI**（只提供 `/health`、`/rerank`、`/parse-rubric-image`），**不是** 批改消费者。

### 1.3 回写与进度
- `RedisGradingListener` 订阅 `grading:results` → `GradingTaskService.onSubmissionComplete` → `refreshTaskCounters`：`completedCount = SCORED + NEED_MORE_EVIDENCE`，`failedCount = FAILED`。
- 当 `completed + failed >= total`：任务转 **FINALIZING**，调用 `GradingFinalizeService.finalizeTaskAsync` 进入资源生成阶段，全部就绪后才置 **COMPLETED**。

### 1.4 资源生成（批注报告 / 错误演示动画）
- `GradingFinalizeService.finalizeTaskAsync`：评分全部完成后，**已经会在 FINALIZING 阶段批量预生成** 每份提交的批注报告（`ensureReviewAndAnnotatedReport` → `createAnnotatedReport`）与批次总评。
- 批注 PDF：`AnnotatedStudentReportService`，含维度评语、行内批注（`ScoreItem.annotationsJson`）、以及对前 10 页再读一次的 `generatePageLevelAiAnnotations`。
- 错误演示动画：`GradingErrorDemonstrationService.buildDemonstrations`（最多 4 个），既有 FINALIZING 阶段的 **eager 预生成**，也保留了打开详情时的 **lazy 兜底**（`buildErrorDemonstrations`），且任意改分会把 `errorDemonstrationsJson` 置空触发重生成。

---

## 2. 重点问题：为什么「目标1 满分、目标2 没分」（图 2）

封面「课程目标」表的每个目标得分，是在 **后端渲染批注 PDF 时**由 `AnnotatedStudentReportService.drawPdfScoreInCourseObjectivesTable` 填上去的，数据来源是 `GradingSubmissionService.buildDimensionScores`。三个缺陷叠加导致了图 2：

### 缺陷 A：目标标号 off-by-one（最可能的直接原因）
`RubricService` 创建评分标准时：
```java
for (int i = 0; i < dimensions.size(); i++) {
    ...
    dim.setSortOrder(i);   // i 从 0 开始
}
```
而 `buildDimensionScores` 用 sortOrder 拼标签：
```java
new AnnotatedStudentReportService.DimensionScore(
        "目标" + score.getDimension().getSortOrder(),  // sortOrder=0 → "目标0"
        score.getScore())
```
- 结果：第 1 个维度被标成 **「目标0」**，第 2 个维度才是 **「目标1」**，第 3 个才是「目标2」……
- 封面表里的行是 **「目标1 / 目标2」**。于是：
  - 「目标0」匹配不到任何行 → 第 1 个维度的分数被丢弃；
  - 「目标1」匹配上封面「目标1」 → 拿到了（其实是评分标准第 2 个维度的）分数，被裁剪到该行满分 20，恰好顶满 → 显示 **20**；
  - 封面「目标2」没有任何维度匹配（评分标准只有 2 个维度时）→ `scoreByLabel.get("目标2")==null` → 该行被 `continue` 跳过 → **留空（即 0）**。
- 「成绩」行 = 可见目标分之和 = 仅「目标1」= **20**。

这与图 2 完全吻合：目标1=20、目标2 空、成绩=20。

### 缺陷 B：评分标准维度 ≠ 封面课程目标表
即使修正 off-by-one，仍有结构性错配：
- 教师评分标准维度（数量、满分、顺序）由教师自定义，**不保证**与学生报告封面表的「目标1/目标2/目标3」一一对应（数量可能不同、满分可能不同、含义可能不同）。
- 现在仅靠「目标」+ 序号做字符串匹配，任何不对齐都会让某些封面目标行 **拿不到分**（留空 = 视觉上的 0 分）。

### 缺陷 C：随机分配兜底（`allocateSplitReportScores`）
当封面各目标满分之和 ≠ 100 且 AI 百分制总分 > 可见满分和时，会进入：
```java
allocated[i] = lower + rnd.nextInt(upper - lower + 1);  // ThreadLocalRandom 随机填充
```
- 这是把真实的「逐维度得分」丢弃，改用 **随机数** 在各目标间分配总分。
- 在「目标1 max=20、目标2 max=40」这类拆分报告下，贪心+随机很容易把 20 全给目标1、把 0 留给目标2，**结果不确定且与学生实际表现无关**。
- 即便不是图 2 的直接成因，它也是一个「同一份报告每次渲染分数都可能不同」的严重隐患。

### 关于「VLM 首页识别」
- **现状：学生报告首页没有任何 VLM 识别步骤。** worker 里的 VLM（`call_vlm` 的 analyze/describe）只用于代码截图、图表等通用图片证据；`parse_rubric` 只服务于「教师上传评分标准图片」。
- 封面目标表的分数完全靠上面 A/B/C 的「评分标准维度 → 封面目标行」启发式映射，没有「读封面表 → 按目标对位回填」的能力。
- 这正是需要补齐的：**用 VLM 真正识别学生报告首页的课程目标表（目标编号、各目标满分、评价区间），再把 AI 评分按目标对位、按各目标满分校准后回填**，替换掉字符串匹配 + 随机分配。

---

## 3. 其它问题清单

### 3.1 进度只是「批次级 + 轮询」，不满足「实时呈现进度」
- 前端进度（图 1 的 40%）= `(completedCount + failedCount) / totalCount`，是 **整批已完成份数占比**，而且靠 **轮询** 拿计数，没有 WebSocket/SSE。
- 单份报告没有「解析中 / 证据抽取 / VLM / 评分 / 批注生成」这种 **阶段级进度**，体验上是 0%→100% 跳变。
- worker 内 `trace_step`/`code_tracer` 已经有阶段信息，但没有对外推送到前端。
- 改造方向：worker 在每个阶段 `publish` 细粒度进度事件 → 后端用 SSE/WebSocket 转发 → 前端按提交、按阶段展示实时进度条。

### 3.2 评分校准偏松，可能掩盖真实差异
- `scorer.py` 有多重「保底」：`MIN_FLOOR_RATIO=0.60`、按 `score_range_min` 抬底、噪声保护等，并强约束「每个维度落在期望区间」。
- 好处是避免误杀，但副作用是 **不同目标之间的真实差异被压平**，再叠加封面回填的错配，更难看出「哪个目标真的没做」。

### 3.3 批注 PDF 的封面回填依赖脆弱的几何解析
- `drawPdfScoreInCourseObjectivesTable` 靠 `collectPdfLines` 的坐标带（±45f 等阈值）找行锚点、找「分值」「评分」列。对不同模板的封面表非常敏感，换一种报告模板就可能错位或失效。
- `extractCourseObjectiveMaxScores` 用正则 `^\d{1,3}(\.\d+)?$` 抓满分，遇到「20分」「(20)」等写法会抓不到。

### 3.4 资源生成阶段的健壮性 / 可观测性
- `finalizeTaskAsync` 里单份失败只 `log.error` 不重试、不在前端暴露「该份批注/动画生成失败」的细分状态；只有整体 COMPLETED/FAILED。
- `aiExecutor` 与 `fileExecutor` 两个线程池嵌套 `join`，并发与超时行为需关注（大批量时易堆积）。
- 改分会使 `errorDemonstrationsJson` 失效并触发重生成，频繁改分会反复触发 AI 调用。

### 3.5 队列与状态一致性
- 进度计数依赖 Redis pub/sub 通知；若 `grading:results` 消息丢失或监听器掉线，任务可能 **卡在 PROCESSING/FINALIZING**，缺少超时兜底/对账（定时扫描重算 counters）。
- `process_submission` 有 `max_retries=3`，但失败/重试状态没有清晰地反馈到前端进度。

---

## 4. 对照用户期望的差距

| 期望 | 现状 | 差距 |
| --- | --- | --- |
| 队列批改 | 已有 Redis 队列 + Celery worker | 基本满足 |
| 前端实时呈现进度 | 仅批次级百分比 + 轮询 | 缺单份/阶段级进度、缺 SSE/WebSocket 推送 |
| 批改过程中预生成批注报告与动画，完成后秒开 | FINALIZING 阶段已 eager 预生成，但仍保留 lazy 兜底，且改分会失效重生成 | 大体满足；需补：细分生成状态、失败重试、避免重复生成 |
| 首页让 VLM 识别课程目标表 | **不存在**该步骤；封面分靠字符串匹配 + 随机分配 | 能力缺失，需新增 VLM 首页识别并对位回填 |

---

## 5. 建议的修复优先级（仅作后续参考，本次不改代码）

1. **P0 修 off-by-one**：`RubricService` 用 `setSortOrder(i + 1)`（或在 `buildDimensionScores` 用 `sortOrder + 1` / 用维度真实名称），让标号与封面「目标N」一致。
2. **P0 去掉随机分配**：`allocateSplitReportScores` 改为「按各目标实际得分/满分比例确定，再按封面满分校准」，移除 `ThreadLocalRandom`，保证可复现且反映真实表现。
3. **P1 新增 VLM 首页识别**：识别封面课程目标表（目标编号、各目标满分、评价区间），建立「评分标准维度 ↔ 封面目标」的可靠对位，替代字符串匹配。
4. **P1 实时进度**：worker 推送阶段事件 → 后端 SSE/WebSocket → 前端单份 + 阶段进度。
5. **P2 健壮性**：finalize 单份重试与细分状态、任务超时对账、封面表解析容错（满分正则、模板适配）。

---

> 备注：本文仅做问题定位与梳理，未改动任何代码。图 2 现象的最可能直接成因是第 2 节「缺陷 A（sortOrder 从 0 起的 off-by-one）」叠加「缺陷 B（维度与封面目标不对齐）」。

---

## 6. 修复记录（2026-06-30）

### 已修复（P0，封面成绩表评分真实化）

本次修复让封面「课程目标」表的每个目标得分变为 **真实、确定、可复现**，不再出现「目标1 满分、目标2 零分」这类随机/错位结果。

1. **修正目标标号 off-by-one** — `GradingSubmissionService.buildDimensionScores`
   - 标签由 `"目标" + sortOrder`（0 起，导致「目标0/目标1」错位）改为 `"目标" + (sortOrder + 1)`，与封面表的「目标1/目标2」对齐。
   - 同时携带该维度的 AI 满分（`ScoreItemEntity.maxScore`），供渲染端按真实比例换算。

2. **`DimensionScore` 记录扩展** — `AnnotatedStudentReportService`
   - 新增 `maxScore` 字段：`record DimensionScore(String label, BigDecimal score, BigDecimal maxScore)`，并保留 2 参旧构造器向后兼容。

3. **去除随机分配，改为确定性按比例换算** — `AnnotatedStudentReportService`
   - 删除 `allocateSplitReportScores`（基于 `ThreadLocalRandom` 的随机分配）。
   - `normalizeCourseObjectiveScores` 改为：`填入分 = round(AI得分 / AI满分 × 封面该目标满分)`，并裁剪到 `[0, 封面满分]`。
   - AI 满分缺失时退化为「按封面满分裁剪原始分」。
   - 「成绩」行 = 各可见目标填入分之和（真实合计）。
   - 效果：同一份报告每次渲染结果一致，且每个目标分数反映该维度的真实表现。

> 验证：`./mvnw -q -DskipTests compile` 通过；改动文件无诊断错误。

### 待办（建议后续按此推进）

- **P1 VLM 首页识别**：用 VLM 真正识别学生报告首页课程目标表（目标编号、各目标满分、评价区间），建立「评分标准维度 ↔ 封面目标」的可靠对位，替换当前「按顺序 + 字符串匹配」的启发式映射（应对维度数量/含义与封面表不一致的情况）。
- **P1 实时进度**：worker 各阶段（解析/证据/VLM/评分/批注）`publish` 细粒度事件 → 后端 SSE/WebSocket → 前端单份 + 阶段级进度条，替代当前「整批份数占比 + 轮询」。
- **P2 健壮性**：finalize 单份失败重试与细分状态、任务超时对账（定时重算 counters 防卡死）、封面表解析容错（满分正则支持「20分/(20)」等写法、多模板适配）。

---

## 7. 实现记录（2026-06-30，第二批：VLM 首页识别 + 实时进度）

### 7.1 VLM 首页识别 + 评分标准维度↔封面目标可靠对位

**worker（Python）**
- 新增 `grading-worker/pipeline/cover_parser.py`：把学生报告首页渲染成图片（PyMuPDF），调用 `call_vlm(task="parse_rubric")` 识别封面"课程目标"表，归一为稳定结构
  `{source, confidence, objectives:[{index,label:"目标1",maxScore,levelRanges}]}`。仅当识别到 `目标N` 行才返回，避免误判普通评分表。
- `tasks.py::process_submission`：在文档解析后、抽证据前执行首页识别（`trace_step "cover_recognize"`），用原生 `UPDATE` 写入 `grading_submission.cover_objectives_json`（不映射进 ORM，迁移未应用时可平滑降级）。可用环境变量 `COVER_RECOGNITION_ENABLED`（默认 true）开关。
- `config.py`：新增 `PROGRESS_CHANNEL`、`COVER_RECOGNITION_ENABLED`。

**后端（Java）**
- 迁移 `V58__grading_submission_cover_objectives.sql`：新增 `cover_objectives_json LONGTEXT NULL`。
- `GradingSubmissionEntity`：新增 `coverObjectivesJson` 字段。
- `DimensionScore` 记录扩展为 `(label, score, maxScore, coverMax)`（保留 2/3 参旧构造器）。
- `GradingSubmissionService.buildDimensionScores(scores, coverObjectivesJson)`：当存在 VLM 识别结果时，按 sortOrder 顺序把第 i 个评分维度对位到第 i 个**识别出的**封面目标，使用其真实 `label` 与 `maxScore`；无识别结果时回退到确定性的 `目标N` 顺序标号。
- `AnnotatedStudentReportService`：渲染时优先采用 VLM 识别的封面满分（`vlmCoverMaxByLabel` 覆盖几何解析值），再按"真实表现比例 × 封面满分"确定性回填（沿用第 6 节去随机化逻辑）。

### 7.2 实时进度（worker 阶段事件 → 后端 SSE → 前端）

**worker（Python）**
- 新增 `grading-worker/pipeline/progress_reporter.py`：定义有序阶段（排队/解析/识别封面/抽证据/AI 评分/生成批注/完成）与百分比，`publish_progress(...)` 发布到 Redis 频道 `grading:progress`。
- `tasks.py`：在各阶段（parsing/cover_recognize/evidence/scoring/report/done）与失败路径 `_fail_submission` 推送进度事件（best-effort，不影响主流程）。

**后端（Java）**
- `GradingProgressService`：按 taskId 维护 `SseEmitter` 订阅集合，解析 worker 进度消息并广播（event: `progress`）；同时提供任务级快照广播（event: `task`）。
- `GradingProgressRedisListener`：订阅 `grading:progress` 频道转发给上面的服务。
- `GradingTaskController`：新增 `GET /api/grading/tasks/{id}/progress/stream`（SSE，带任务所有权校验，订阅时立即推送一次任务级快照）。
- `GradingTaskService.onSubmissionComplete` 与 `GradingFinalizeService` 在状态/计数变化、FINALIZING→COMPLETED/FAILED 时广播任务级快照，前端无需轮询即可实时收敛。

**前端（Vue）**
- `api/tap/grading.js` 新增 `streamGradingProgress(taskId, handlers)`：fetch + Bearer + 流式读取 SSE（参考 `rag.js`），返回 `{ close }`。
- `GradingCenter.vue`：对 PENDING/PROCESSING/FINALIZING 任务自动订阅进度流；实时更新整体进度条与计数，并在任务行下方显示"学生 · 当前阶段"实时字幕；FINALIZING 显示"生成批注报告与演示动画中…"。SSE 不可用时自动回退到原有 5s 轮询（轮询条件已含 FINALIZING）。组件卸载时关闭所有连接。

### 7.3 验证

- 后端：`./mvnw -q -DskipTests compile` 通过。
- worker：`python -m py_compile` 全部通过。
- 前端：`npm run build` 通过。
- 说明：受限于本地无法启动完整运行栈（Celery worker + Redis + MinIO + MySQL + 前端联调），以上为编译/构建级验证；端到端（真实 VLM 识别、SSE 实时推送）需在部署环境用真实任务冒烟。

### 7.4 部署注意

1. **先后端、后 worker**：先部署后端让 Flyway 执行 `V58`（新增列），再重启 grading-worker；否则 worker 写 `cover_objectives_json` 的 UPDATE 会失败（已 try/except 降级，但建议按序部署）。
2. worker 需具备可用的 VLM（`VLM_API_URL`/`VLM_API_KEY`/`VLM_MODEL`，默认走 DashScope qwen-vl）。未配置时首页识别自动跳过并回退到顺序对位，不影响评分。
3. SSE 走现有 `Authorization: Bearer`（前端 fetch 流式），无需额外网关/Nginx 改动；若反向代理对 `text/event-stream` 有缓冲，需关闭对该路径的 buffering。
4. 新增开关：`COVER_RECOGNITION_ENABLED`（worker，默认 true）。
