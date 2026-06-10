# AI 批改批量导出与批次模型设计草案

## 1. 背景与问题

当前 AI 批改中心已经支持单个批改任务的查看与导出：教师上传一组学生作业后，后端创建一条 `grading_task`，每个学生文件对应一条 `grading_submission`，批改结果、证据、报告文件分别落到 `score_item`、`evidence_block`、`report_file` 等表中。

从教师使用视角看，一个 `grading_task` 实际上已经接近“一次批改批次”：它包含同一次上传、同一个评分标准、同一个期望分数区间、同一个教师署名、同一组待批改作业。但在界面上，教师看到的是一条条批改任务，导出也主要围绕单个任务进行。随着任务数量增多，会出现几个问题：

- 教师想一次性导出多个批改任务时，需要逐个进入详情页或逐个点导出。
- `display_code` 解决了“#7 这种 ID 越来越长/不友好”的问题，但还没有表达“这些任务属于同一次教学活动或同一个导出批次”。
- 如果后续要支持“按教学班、实验、日期、评分标准、教师署名”汇总导出，需要一个清晰的批次边界。
- 已删除任务在 worker 队列中静默跳过是正确的，但导出侧也需要避免导出已删除、未完成或无报告的任务。

因此，本功能的核心不是简单加一个按钮，而是要决定“批次”到底是业务概念、导出概念，还是仅仅是前端选择的一组任务。

## 2. 现有模型判断

现有核心表关系如下：

```text
grading_task
  ├─ grading_submission
  │   ├─ score_item
  │   ├─ evidence_block
  │   └─ report_file
  └─ report_file
```

其中 `grading_task` 已经包含：

- `teacher_id`
- `rubric_id`
- `experiment_id`
- `class_id`
- `assignment_offering_id`
- `teacher_signature`
- `score_range_min`
- `score_range_max`
- `status`
- `total_count`
- `completed_count`
- `failed_count`
- `display_code`
- `created_at`

这意味着，单次上传生成的 `grading_task` 已经能够承担“批次”的大部分语义。当前最缺的是跨多个 `grading_task` 的聚合导出能力，而不一定是立即新增复杂的批次表。

## 3. 设计目标

本功能建议分两步推进。

第一步解决教师眼前最明显的问题：在批改历史区域勾选多个已完成任务，批量导出 ZIP。ZIP 内部按任务编号和任务名称分目录，包含每个任务已有的 AI 批改报告、批改成绩 Excel，以及必要的导出清单。

第二步再考虑是否引入真正的 `grading_batch` 业务表，用于把多个任务长期归属到一个命名批次中，例如“第 4 次实验报告批改”“计科23数据结构第10周批改”等。

这个顺序的好处是：先满足使用场景，少动数据库；等使用方式稳定后，再把稳定下来的“批次”抽象固化到数据库中。

## 4. 方案对比

### 方案 A：轻量批量导出，不新增批次表

前端在批改历史列表中增加多选框和“批量导出”按钮。教师选择多个 `grading_task` 后，前端提交 `taskIds` 给后端，后端即时生成一个 ZIP。

导出内容建议：

```text
AI批改批量导出-20260610.zip
  ├─ 0610-01-数据结构实验综合评分/
  │   ├─ 批改成绩.xlsx
  │   ├─ AI批改报告.zip
  │   └─ manifest.json
  ├─ 0610-02-数据结构实验综合评分/
  │   ├─ 批改成绩.xlsx
  │   ├─ AI批改报告.zip
  │   └─ manifest.json
  └─ 批量导出清单.xlsx
```

优点：

- 改动最小，最快能用。
- 不引入新表，不改变现有批改链路。
- `grading_task` 已经足够表达单次上传批次。
- 适合目前“还不清楚批次到底怎么用”的阶段。

缺点：

- 选择关系不被持久化，刷新后不会记住“这几个任务属于同一批”。
- 无法给批次命名、备注、归档。
- 不适合后续做“批次分析”“批次状态追踪”等高级功能。

推荐程度：第一阶段推荐。

### 方案 B：新增 `grading_batch` 表，任务归属批次

新增一张 `grading_batch` 表，把多个 `grading_task` 归到同一个批次下。

建议表结构：

```sql
CREATE TABLE grading_batch (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  teacher_id BIGINT NOT NULL,
  display_code VARCHAR(16) NOT NULL,
  name VARCHAR(128) NOT NULL,
  description TEXT NULL,
  class_id BIGINT NULL,
  experiment_id BIGINT NULL,
  assignment_offering_id BIGINT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_grading_batch_teacher FOREIGN KEY (teacher_id) REFERENCES tap_user(id) ON DELETE CASCADE
);

ALTER TABLE grading_task
  ADD COLUMN batch_id BIGINT NULL,
  ADD CONSTRAINT fk_grading_task_batch FOREIGN KEY (batch_id) REFERENCES grading_batch(id) ON DELETE SET NULL;
```

批次与任务关系：

```text
grading_batch
  └─ grading_task
       └─ grading_submission
```

优点：

- 可以给批次命名、备注、归档。
- 可以长期追踪一个批次下所有任务的完成状态。
- 后续可做批次级统计、批次级导出历史、批次级权限控制。
- 更符合业务语言：“本次实验批改批次”。

缺点：

- 数据库和接口复杂度上升。
- 需要设计批次创建入口：创建任务时自动建批次，还是教师手动建批次后再上传任务？
- 需要处理历史任务回填到哪个批次。
- 如果当前使用流程还没定型，容易过早抽象。

推荐程度：第二阶段再做。

### 方案 C：不新增表，只给 `grading_task` 增加导出分组字段

在 `grading_task` 上增加字段，例如：

```sql
ALTER TABLE grading_task
  ADD COLUMN export_group_code VARCHAR(32) NULL,
  ADD COLUMN export_group_name VARCHAR(128) NULL;
```

多个任务共享同一个 `export_group_code` 即可视为同一批次。

优点：

- 比新增表简单。
- 支持粗粒度分组和命名。

缺点：

- 字段会越来越脏，后续如果批次概念变复杂，仍然要迁移到独立表。
- 无法优雅记录批次创建人、状态、备注、导出历史。
- 数据语义不如 `grading_batch` 清楚。

推荐程度：不推荐。它看起来省事，但会把批次概念塞进任务表，后面容易变成历史包袱。

## 5. 推荐方案

建议采用“两阶段方案”。

第一阶段：实现轻量批量导出，不新增批次表。

先把教师最需要的动作做出来：在批改历史区域多选多个任务，然后点击“批量导出”。后端按 `taskIds` 聚合生成 ZIP。这个阶段把“批次”理解为“本次用户选择的一组批改任务”，不持久化。

第二阶段：当教师确实需要保存批次、命名批次、按批次筛选、按批次统计时，再引入 `grading_batch` 表。

这样做更稳，因为现在系统已经有 `grading_task`，而且刚刚新增了 `display_code`。如果马上再加 `batch`，很容易出现两个概念重叠：

- `grading_task` 是一次上传批次？
- `grading_batch` 是多个任务批次？
- 一个任务能不能跨批次？
- 删除批次是否删除任务？

这些问题没有被真实使用流程验证前，不建议直接做重数据库设计。

## 6. 第一阶段功能设计

### 6.1 前端交互

在 `GradingCenter.vue` 的批改历史区域增加：

- 每条任务左侧增加复选框。
- 顶部增加批量操作栏：
  - 已选择 N 个任务
  - 批量导出 ZIP
  - 清空选择
- 默认只允许选择状态为 `COMPLETED` 的任务。
- `PENDING`、`PROCESSING` 的任务可显示但禁用勾选。
- `FAILED` 的任务可以根据策略决定：
  - 第一版建议默认不允许批量导出。
  - 后续可提供“包含失败任务清单”的高级选项。

建议微文案：

```text
批量导出会打包所选任务的批改成绩、AI 批改报告和导出清单。
仅已完成任务可导出。
```

### 6.2 后端接口

新增接口：

```http
POST /api/grading/tasks/batch-export
Content-Type: application/json

{
  "taskIds": [1, 2, 3],
  "includeExcel": true,
  "includeReports": true,
  "includeComments": true
}
```

返回：

```http
200 OK
Content-Type: application/zip
Content-Disposition: attachment; filename="AI-grading-batch-export-20260610.zip"
```

权限规则：

- 只能导出当前教师自己的任务。
- 如果 `taskIds` 中存在不属于当前教师的任务，直接返回 403 或忽略并在清单中标记。第一版建议直接 403，避免静默泄漏。
- 如果任务不是 `COMPLETED`，第一版建议返回 400，并提示哪些任务不可导出。

### 6.3 ZIP 内容

建议 ZIP 结构：

```text
AI批改批量导出-20260610-1530.zip
  ├─ 导出清单.xlsx
  ├─ 0610-01/
  │   ├─ 批改成绩.xlsx
  │   ├─ AI批改报告.zip
  │   └─ 任务信息.json
  ├─ 0610-02/
  │   ├─ 批改成绩.xlsx
  │   ├─ AI批改报告.zip
  │   └─ 任务信息.json
```

`导出清单.xlsx` 建议字段：

- 任务编号：`displayCode`
- 任务 ID：`taskId`
- 实验名称
- 教学班
- 评分标准
- 总份数
- 已完成数
- 失败数
- 创建时间
- 导出状态
- 备注

`任务信息.json` 便于后续追踪和排查：

```json
{
  "taskId": 7,
  "displayCode": "0610-01",
  "experimentName": "数据结构实验综合评分",
  "className": "计科23数据结构实验班",
  "rubricName": "数据结构实验综合评分标准",
  "totalCount": 1,
  "completedCount": 1,
  "failedCount": 0,
  "createdAt": "2026-06-10T21:30:00"
}
```

### 6.4 后端实现边界

第一版可以复用现有能力：

- `exportExcel(taskId, teacherId, submissionIds, includeComments)`
- `exportGradingTask(taskId)` 或现有 ZIP 导出逻辑
- `ReportFileRepository`
- `ObjectStorageService`

如果现有 `exportGradingTask` 是 Controller 内部逻辑，不适合直接复用，建议抽出一个 `GradingExportService`：

```text
GradingExportService
  ├─ exportTaskZip(taskId, teacherId)
  ├─ exportTaskExcel(taskId, teacherId, options)
  └─ exportBatchZip(taskIds, teacherId, options)
```

这样可以避免 Controller 里继续堆导出细节。

## 7. 第二阶段批次模型设计

当出现以下需求时，再进入第二阶段：

- 教师想创建一个“实验批改批次”，后续多次上传都归到同一批。
- 教师想在列表中按批次筛选任务。
- 需要显示批次级完成率、失败率、平均分。
- 需要多次导出同一个批次，并保留导出历史。
- 需要把批次与课程、教学班、实验任务强绑定。

届时建议新增：

```text
grading_batch
grading_batch_export
```

`grading_batch_export` 可记录每次导出：

```sql
CREATE TABLE grading_batch_export (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  batch_id BIGINT NULL,
  teacher_id BIGINT NOT NULL,
  task_ids_json JSON NOT NULL,
  object_key TEXT NULL,
  status VARCHAR(32) NOT NULL,
  error_message TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

但这属于进阶功能，不建议第一版就做。

## 8. 删除与队列一致性

已确认 worker 在任务删除后，如果队列中的 Celery 任务找不到对应 submission，会静默返回，不会报错或重试。这对批量导出功能是好事，因为导出只读数据库中的当前任务和报告文件，不需要关心已删除任务的旧队列消息。

导出侧仍需处理：

- 任务已删除：请求中的 taskId 查不到，返回 400，提示任务不存在。
- 任务未完成：返回 400，提示任务尚未完成。
- 报告文件缺失：可以把该任务标记为“报告未生成”，并在 ZIP 清单中说明；第一版建议直接阻止导出，提示先进入详情页生成报告。

## 9. 推荐的第一版验收标准

第一版做到以下程度就可以算可用：

- 批改历史列表可以多选已完成任务。
- 点击批量导出后生成一个 ZIP。
- ZIP 中每个任务都有独立目录。
- 每个任务目录包含成绩 Excel 和 AI 批改报告 ZIP。
- 顶层包含一份导出清单 Excel。
- 如果选择了未完成任务，前端禁用或后端返回明确错误。
- 导出文件名使用时间戳，避免覆盖。
- 后端校验任务归属当前教师。

## 10. 暂不建议做的内容

第一版不建议做：

- 不建议立即新增 `grading_batch` 表。
- 不建议做批次编辑、归档、重命名。
- 不建议做批次级统计图表。
- 不建议做异步导出任务队列，除非 ZIP 体积明显过大。
- 不建议把失败任务混入导出，避免教师拿到半成品资料。

## 11. 我的建议

目前更适合先做“轻量批量导出”，而不是马上设计一个完整批次系统。

原因很简单：你现在想解决的是“下面批改区域能不能批量导出”，不是已经明确需要“批次生命周期管理”。`grading_task` 已经承载了一次上传批改的批次语义，刚新增的 `display_code` 也让任务编号变得可读。第一版只要让教师能勾选多个 `grading_task` 并导出，就能覆盖主要使用场景。

等教师实际用起来后，如果发现他们会反复说“我想把这几次上传归到同一个实验批次里”，那时再加 `grading_batch` 表就更自然，也更不容易设计偏。

