# PTA 批改（判题为主 + AI 评语，按班级/题集批量，发布给学生）设计

- 状态：关键决策已确认，待写 spec 复审后实现
- 影响仓库：`backend-repo`（取数/评语/持久化/发布/接口）、`frontend-repo`（新增 PTA 批改菜单与页面）
- 复用：`TeacherExperimentQueryDao`（PTA 取数 join 范式）、`ErrorAnalysisService`/AI provider（AI 评语）、现有学生成绩发布/通知通道（同 PDF 批改）

## 已确认决策
1. 评分方式：**PTA 客观判题结果为主**（不重跑 rubric 流水线），AI 只生成评语/思路点评。
2. 粒度：**按班级 + 题集批量**批改。
3. 发布：**像 PDF 批改那样发布给学生**（学生可见分数 + 评语）。
4. 入口：AI 批改分组下**两个独立菜单项**——「文件批改」(现 `GradingCenter.vue` 原样) 与「PTA 批改」(新页)。

## 背景（现状事实）
- PTA 数据已入库：`student_problem_state`（每生每题 `best_score`/`latest_status`/`latest_attempt_id`/`latest_code_artifact_id`）、`assignment_problem`、`pta_problem_detail`(题面)、`artifact.text_content`(代码)。
- 现有批改域（grading_task/grading_submission/worker）只服务 PDF 上传，无 PTA 打分能力。
- 取数范式已存在：`TeacherExperimentQueryDao.findSubmissionProblemRows`（join 上述表）。

## 架构与数据流
```
教师端「PTA 批改」页(PtaGradingCenter.vue)
  选 班级(offering) + 题集 → 拉学生列表(PTA分/状态)
        │ POST /api/grading/pta/generate {offeringId, problemSetId?}
        ▼
PtaGradingController → PtaGradingService
  1) 查 student_problem_state + pta_problem_detail + artifact（按 offering[/题集]）
  2) 客观分：score = 归一化(每题 best_score)；同时记录 AC 率、每题明细
  3) AI 评语：每生把「题面+代码+判题状态」喂 AI provider → 简短教师评语（复用 ErrorAnalysisService 风格）
  4) upsert 到 pta_grading_result（幂等；可 force 重算评语）
        ▼
教师查看列表/单生详情 → 可重新生成评语 → 发布
  POST /api/grading/pta/publish {resultId | offeringId+studentNo}
        ▼
发布：写入与现有 PDF 批改一致的学生可见通道 + 通知；学生端沿用现有"已发布批改结果"入口查看
```

## 数据存储（Flyway 迁移）
新表 `pta_grading_result`（版本号实现时现查最新 +1，避免撞号）：
- `id BIGINT PK AI`
- `offering_id BIGINT NOT NULL`、`problem_set_id VARCHAR(64) NULL`
- `student_id BIGINT NOT NULL`、`student_no VARCHAR(128) NOT NULL`、`student_name VARCHAR(255)`
- `score DECIMAL(5,2)`、`ac_rate DECIMAL(5,2)`、`problem_count INT`、`accepted_count INT`
- `comment TEXT`（AI 评语）、`detail_json LONGTEXT`（每题：problemNo/title/status/bestScore）
- `status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED'`、`published TINYINT(1) NOT NULL DEFAULT 0`、`published_at DATETIME NULL`
- `created_at`/`updated_at DATETIME`
- 唯一键 `uk_pta_grading (offering_id, problem_set_id, student_id)`；索引 `(offering_id, published)`
- 实体 `PtaGradingResultEntity` + `PtaGradingResultRepository`。

## 后端设计
- `PtaGradingService`：
  - `preview(offeringId, problemSetId, teacher)`：只查 PTA 客观数据 + 学生列表（不调 AI），供页面先展示。
  - `generate(offeringId, problemSetId, force, teacher)`：批量算分 + 生成 AI 评语 + upsert（AI 失败降级为"仅客观分 + 占位评语"，不阻断）。
  - `list(offeringId, problemSetId, teacher)` / `detail(resultId, teacher)`。
  - `publish(resultId 或 offeringId, teacher)`：置 published，并写入学生可见通道 + 发通知（对齐 PDF 批改发布逻辑，实现时定位现有发布/通知服务复用）。
  - 归属校验：教师只能操作自己教的班级（复用 `TeacherPrincipalResolver` + 班级归属校验）。
  - 分数归一：`score = round( Σ best_score / Σ 满分 * 100 )`；若拿不到题目满分则回退 `AC率*100`（实现时核对 `student_problem_state`/`assignment_problem` 是否有满分字段，二选一并注释说明）。
- `PtaGradingController` `@RequestMapping("/api/grading/pta")`：`POST /preview`、`POST /generate`、`GET /list`、`GET /detail/{id}`、`POST /publish`，`@AuthenticationPrincipal`。
- AI 评语：复用现有 AI 调用（`ErrorAnalysisService` 或 `AnimationAiClient` 二选一，优先与 grading 现有 provider 一致）；prompt 要求：结合客观判题（AC/非AC、得分）与代码，给 2-3 句自然教师评语，指出主要问题与改进方向，不虚构。

## 前端设计
- 路由 `frontend-repo/src/router/index.js`：AI 批改模块新增 `{ path: 'grading/pta', name: 'PtaGrading', component: PtaGradingCenter.vue }`。
- 菜单 `frontend-repo/src/views/teacher/Layout.vue` 的 `grading` 组 children：改为
  `[{文件批改 /teacher/grading}, {PTA 批改 /teacher/grading/pta}, {评分标准 /teacher/grading/rubrics}]`（现「批改中心」文案改为「文件批改」）。
- 新页 `PtaGradingCenter.vue`：班级/题集选择器 → 「预览」拉学生 PTA 分/状态列表 → 「批量生成评语」→ 列表展示分数+评语（可单生重生成）→ 「发布」。走 `apiClient`(JSON)。API 方法加到 `api/index.js` 或 `api/tap`。
- 学生端：沿用现有"已发布批改结果"展示（发布通道对齐后无需新页；若 PTA 结果与 PDF 结果结构不同，学生端做最小兼容展示分数+评语）。

## 测试
- 后端 `PtaGradingServiceTest`（Mockito）：客观分归一化计算正确；AI 不可用时降级为仅客观分不抛错；`list/detail` 归属校验（跨教师 404/403）；`publish` 置位 published。
- 前端：改动文件定向 lint 通过。

## 明确不做（YAGNI）
- 不重跑 rubric 评分流水线（PDF 批改路径不动）。
- 不改 PTA 同步/爬虫逻辑。
- 不做 PTA 代码的错误动画演示（本期只做分数+评语；如需可后续接入现有演示能力）。

## 假设与依赖
- AI provider 已配（121 有 DeepSeek）；不可用则降级仅客观分。
- 学生-班级-题集关系可由 `student_problem_state.offering_id` + PTA 题集绑定定位（实现时确认题集→offering 映射）。
- 迁移版本号实现时现查（曾多次撞号）。
- 前后端需重建（部署由用户负责）。
