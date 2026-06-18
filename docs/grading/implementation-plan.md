# 实验报告真实批改系统改造 — 任务规划文档

> 本文档基于 `grading-real-correction-redesign.md` 设计稿，将改造工作拆分为可执行、可验收、可并行的任务包。  
> 目标读者：前端/后端/算法工程师、项目负责人。

---

## 1. 项目背景与目标

### 1.1 背景

当前批改系统本质上是「证据片段自动评分 + 红笔视觉效果生成」。主要痛点：

- √、×、波浪线位置由程序随机/均匀散布，与 AI 评语无精确对应关系。
- 分项评语集中在文档末尾，阅读体验割裂。
- 图片证据（`vlm_failed`）以红色错误样式展示，像系统 bug。
- 对勾尺寸偏小，缺乏真实教师手写批改感。

### 1.2 目标

把系统改造成更接近真实教师批改的体验：

1. AI 决定 √/×/波浪线落点。
2. 分项评语 inline 显示在错误/优点旁；教师总评仍放末尾。
3. 批次级总评 Agent 输出共性问题与教学建议。
4. √ 样式放大、更像手写。
5. 图片证据展示中性化，减少 bug 感。

---

## 2. 规划原则

- **小步快跑**：每个 Phase 都能独立合并、独立验证。
- **向后兼容**：旧提交（无 annotations、旧 evidence 格式）仍能正常生成批注报告。
- **可灰度**：关键改动通过配置开关控制，便于线上验证。
- **数据驱动**：每个 Phase 都有明确的验收指标。

---

## 3. 任务总览

| 阶段 | 主题 | 预估工期 | 前置依赖 | 核心产出 |
|---|---|---|---|---|
| Phase 0 | 图片证据展示优化 | 3-5 天 | 无 | Worker 层减少 `vlm_failed`、前端显示中性化 |
| Phase 1 | 证据定位回填 | 5-7 天 | Phase 0 可选 | evidence 带 `locationJson`、前端显示位置 |
| Phase 2 | AI 返回 annotations | 7-10 天 | Phase 1 | `ScoreItem.annotationsJson`、prompt 升级 |
| Phase 3 | Inline 批注渲染 | 7-10 天 | Phase 2 | 批注版 DOCX/PDF 显示 inline 评语 |
| Phase 4 | 批次总评 Agent | 5-7 天 | Phase 2 或 3 | 任务详情页展示批次总评 |
| Phase 5 | √ 样式优化 | 2-3 天 | 无 | 对勾尺寸放大、手写感增强 |
| Phase 6 | 图片缩略图接口 | 2-3 天 | Phase 0 | 教师可在 evidence 卡片内预览原图 |

> **说明**：Phase 0/1/2/3/4/5 已完成；Phase 6 待实现。

---

## 4. Phase 0：图片证据展示优化

### 4.1 目标

减少教师端「图片未识别」卡片的 bug 感，从源头降低 `vlm_failed` 数量，并为后续真实批改提供更高质量的图片证据。

### 4.2 任务拆分

#### T0.1 前端：图片页卡片中性化（已完成）

- **状态**：已完成
- **文件**：
  - `frontend-repo/src/views/teacher/SubmissionReview.vue`
  - `frontend-repo/src/components/LucideIcon.vue`
- **改动点**：
  - 标签颜色从红色改为蓝色中性。
  - 标签文案「图片未识别」→「图片页」。
  - 内容文案改为「已作为页面上下文参与评分」。
  - 新增 `image` 图标。
- **验收标准**：
  - [x] `vlm_failed` 卡片不再使用红色错误样式。
  - [x] 文案不再出现「AI 未能提取」等失败语气。

#### T0.2 Worker：有文字页面不再生成 `vlm_failed`

- **状态**：已完成
- **文件**：
  - `_inspect_grading_worker/grading_worker/tasks.py`
- **改动点**：
  - 在 `tasks.py` 中引入 `PAGE_TEXT_SUBSTANTIAL_THRESHOLD = 100`。
  - 当 `len(page.text.strip()) >= 100` 时，图片提取失败不再生成 `vlm_failed` 证据块。
  - 图片仍然上传 MinIO 供报告渲染使用。
- **验收标准**：
  - [x] 有充足文字证据的页面不再生成 `vlm_failed` 证据块。
  - [x] 评分结果与改造前相比波动在 ±3% 以内。

#### T0.3 Worker：VLM 统一分析替代规则分类器

- **状态**：已完成
- **文件**：
  - `_inspect_grading_worker/grading_worker/pipeline/vlm_client.py`
  - `_inspect_grading_worker/grading_worker/tasks.py`
  - `_inspect_grading_worker/grading_worker/config.py`
  - `_inspect_grading_worker/grading_worker/local_settings.py`
- **改动点**：
  - 在 `vlm_client.py` 新增 `task="analyze"` prompt，返回 `image_type`、`recognized_text`、`summary`、`confidence`。
  - 在 `tasks.py` 新增 `_analyze_image_with_vlm()`，根据返回类型决定使用文字还是描述。
  - 添加配置开关 `USE_VLM_UNIFIED_ANALYSIS`，默认 False。
  - 在 `local_settings.py` 已开启该开关。
  - 保留旧 `extract_text` / `describe` 任务作为兜底。
- **验收标准**：
  - [x] `task="analyze"` 能正确返回结构化 JSON。
  - [x] 开启开关后，`vlm_failed` 数量下降 ≥ 30%。
  - [x] 关闭开关后，系统行为与改造前一致。

#### T0.4 Worker：`vlm_only` 策略增加 OCR 兜底

- **状态**：已完成
- **文件**：
  - `_inspect_grading_worker/grading_worker/tasks.py`
  - `_inspect_grading_worker/grading_worker/pipeline/ocr_processor.py`
- **改动点**：
  - 新增 `_run_ocr_force()`，当 VLM 统一分析或 `extract_text` 失败时，回退到 OCR。
  - 在 `vlm_only` 路径（第 392 行）和 diagram/plot 路径（第 330 行）均调用 OCR 兜底。
- **验收标准**：
  - [x] 图片 OCR 兜底生效。
  - [x] 不增加过多耗时（单张图片 OCR 耗时 < 3 秒）。

---

## 5. Phase 1：证据定位回填

### 5.1 目标

让每个 evidence block 都携带在原文档中的精确位置信息，为后续 AI 返回的 `anchor_text` 定位做准备。

### 5.2 任务拆分

#### T1.1 数据模型：新增 `locationJson` 字段

- **状态**：已完成
- **文件**：
  - `backend-repo/src/main/java/com/tap/backend/domain/grading/EvidenceBlockEntity.java`
  - `backend-repo/src/main/resources/db/migration/V41__add_evidence_location.sql`
  - `_inspect_grading_worker/grading_worker/models/pipeline_models.py`
  - `_inspect_grading_worker/grading_worker/models/db_models.py`
- **改动点**：
  - Java 实体新增 `locationJson` 字段。
  - DB 新增 `location_json TEXT` 列（V41）。
  - Python 模型同步新增 `location` 字段。
- **验收标准**：
  - [x] 新提交生成的 evidence 包含 `locationJson`。
  - [x] 旧提交无 `locationJson` 时系统不报错。

#### T1.2 PDF 解析：回填图片/文本位置

- **状态**：部分完成
- **文件**：
  - `_inspect_grading_worker/grading_worker/pipeline/pdf_parser.py`
  - `_inspect_grading_worker/grading_worker/tasks.py`
- **改动点**：
  - 文本 evidence 已记录页码（`location={"page": page.page_num}`）。
  - 图片 evidence 已记录 `bbox` 和页面。
  - 尚未回填段落索引、行索引等更细粒度信息。
- **验收标准**：
  - [ ] 90% 以上的文本 evidence 能回填段落索引。
  - [x] 图片 evidence 的 `bbox` 坐标与 PDF 坐标系一致。

#### T1.3 DOCX 解析：改进分页与位置

- **状态**：已完成
- **文件**：
  - `_inspect_grading_worker/grading_worker/pipeline/document_parser.py`
  - `_inspect_grading_worker/grading_worker/models/pipeline_models.py`
  - `_inspect_grading_worker/grading_worker/tasks.py`
  - `_inspect_grading_worker/grading_worker/requirements.txt`
- **改动点**：
  - 引入 `python-docx` 解析 DOCX 段落结构。
  - 按段落顺序分配虚拟页码（每 35 个段落一页，遇到显式分页符也换页）。
  - 图片关联到嵌入段落，记录 `paragraph_index`。
  - 文本 evidence 记录虚拟页码 + 段落索引提示。
  - 新增依赖 `python-docx==1.1.2`。
- **验收标准**：
  - [x] DOCX 图片 evidence 不再全部显示为「页 1」。
  - [x] 文本 evidence 能记录段落索引。

#### T1.4 前端：显示证据位置

- **状态**：已完成
- **文件**：
  - `frontend-repo/src/views/teacher/SubmissionReview.vue`
- **改动点**：
  - 在 evidence 卡片上显示 `formatLocation()`，支持页码、段落、行、bbox 等位置信息。
- **验收标准**：
  - [x] 教师能在证据卡片上看到位置信息。

---

## 6. Phase 2：AI 返回 annotations

### 6.1 目标

让评分 LLM 在返回分数的同时，返回每个维度下的 inline 批注列表（√/×/波浪线位置 + 简短评语）。

### 6.2 任务拆分

#### T2.1 数据模型：新增 `annotationsJson` 字段

- **状态**：已完成
- **文件**：
  - `backend-repo/src/main/java/com/tap/backend/domain/grading/ScoreItemEntity.java`
  - `backend-repo/src/main/resources/db/migration/V42__add_score_annotations.sql`
  - `_inspect_grading_worker/grading_worker/models/db_models.py`
- **改动点**：
  - 新增 `annotationsJson` 字段存储 AI 返回的批注数组。
- **验收标准**：
  - [x] 数据库表新增 `annotations_json` 列。
  - [x] 实体和 DTO 能正确序列化/反序列化。

#### T2.2 Worker：Prompt 升级

- **状态**：已完成
- **文件**：
  - `_inspect_grading_worker/grading_worker/pipeline/scorer.py`
- **改动点**：
  - 在 dimension scoring prompt 中增加 `annotations` 字段要求。
  - 约束 `annotations` 数量 0-3 条。
  - 要求 `anchor_text` 必须来自 evidence 原文。
- **验收标准**：
  - [x] 90% 以上的评分结果包含 `annotations` 字段。
  - [x] `anchor_text` 能在对应 evidence 中匹配到（准确率 ≥ 80%）。

#### T2.3 Worker：解析与保存 annotations

- **状态**：已完成
- **文件**：
  - `_inspect_grading_worker/grading_worker/pipeline/scorer.py`
  - `_inspect_grading_worker/grading_worker/tasks.py`
- **改动点**：
  - 修改 `_normalize_result` 解析 `annotations`，限制最多 3 条。
  - 保存到 `ScoreItem.annotations_json`。
  - 对无效 annotation 做过滤。
- **验收标准**：
  - [x] `annotations` 正确写入数据库。
  - [x] API 返回中包含 `scores[].annotations`。

#### T2.4 后端：定位算法 fallback

- **状态**：已完成
- **文件**：
  - `backend-repo/src/main/java/com/tap/backend/service/AnnotatedStudentReportService.java`
- **改动点**：
  - DOCX：`findDocxParagraphContaining()` 根据 `anchor_text` 定位段落。
  - PDF：`findPdfLineContaining()` / `PdfAnchorLocator` 根据 `anchor_text` 定位行。
  - 定位失败时退回到随机散布（旧路径）。
- **验收标准**：
  - [x] 80% 以上 annotation 能成功定位到文档位置。

---

## 7. Phase 3：Inline 批注渲染

### 7.1 目标

在批注版 DOCX/PDF 中，把 AI 的 `annotations.note` 显示在对应错误/优点段落旁边。

### 7.2 任务拆分

#### T3.1 DOCX：段落旁 inline 批注

- **状态**：已完成
- **文件**：
  - `backend-repo/src/main/java/com/tap/backend/service/AnnotatedStudentReportService.java`
- **改动点**：
  - 在 anchor 段落后插入新段落，右对齐显示批注文字（√/×/波浪线 + note）。
  - 支持 `\n` 换行。
  - 对 `wavy=true` 的 anchor 添加红色下划线近似表示波浪线。
- **验收标准**：
  - [x] DOCX 中 ×/波浪线旁出现 inline 简短批注。
  - [x] 批注文字不超出页面边界。

#### T3.2 PDF：行旁 inline 批注

- **状态**：已完成
- **文件**：
  - `backend-repo/src/main/java/com/tap/backend/service/AnnotatedStudentReportService.java`
- **改动点**：
  - 用 PDFBox 找到 anchor 文本行，在行右侧绘制多行批注文字。
  - 做右边界和上边界检测，超出则调整到行上方。
  - 支持 `\n` 换行。
- **验收标准**：
  - [x] PDF 中 ×/波浪线旁出现 inline 简短批注。
  - [x] 批注不遮挡正文、不超出页面。

#### T3.3 兼容旧格式

- **状态**：已完成
- **文件**：
  - `backend-repo/src/main/java/com/tap/backend/service/AnnotatedStudentReportService.java`
- **改动点**：
  - 无 `annotations` 的旧提交，仍按随机散布逻辑生成报告。
  - 有 `annotations` 的新提交走 AI 驱动的 inline 批注路径。
- **验收标准**：
  - [x] 旧提交批注报告生成不报错。

---

## 8. Phase 4：批次总评 Agent

### 8.1 目标

批量批改完成后，由 Agent 阅读整个批次的学生评分结果，输出共性问题、优秀范例、教学建议。

### 8.2 任务拆分

#### T4.1 数据模型：新增 `batchReviewJson` 字段

- **状态**：已完成
- **文件**：
  - `backend-repo/src/main/java/com/tap/backend/domain/grading/GradingTaskEntity.java`
  - `backend-repo/src/main/resources/db/migration/V43__add_batch_review_and_agent_config.sql`
- **改动点**：
  - 新增 `batchReviewJson`、`batchReviewStatus`、`batchReviewPrompt`、`batchReviewModel` 字段。
- **验收标准**：
  - [x] 数据库表新增对应列。
  - [x] 实体和 DTO 正确映射。

#### T4.2 后端：批次总评生成服务

- **状态**：已完成
- **文件**：
  - `backend-repo/src/main/java/com/tap/backend/service/grading/GradingBatchReviewService.java`（新建）
  - `backend-repo/src/main/java/com/tap/backend/service/grading/GradingAgentConfigService.java`（新建）
- **改动点**：
  - 读取任务下所有 submission 的 scores、comments、evidence。
  - 调用 LLM Agent，按 prompt 模板输出 JSON。
  - 异步执行，状态机管理 `PENDING/GENERATING/COMPLETED/FAILED`。
  - Prompt/model 配置存于 `agent_config` 表，并缓存到 Redis。
- **验收标准**：
  - [x] 能成功生成批次总评 JSON。
  - [x] 失败时有错误日志。

#### T4.3 后端：API 接口

- **状态**：已完成
- **文件**：
  - `backend-repo/src/main/java/com/tap/backend/web/grading/GradingBatchReviewController.java`（新建）
- **改动点**：
  - `GET /api/grading/tasks/{id}/batch-review` 返回 `batchReview`。
  - `POST /api/grading/tasks/{id}/batch-review` 手动触发。
  - `GET/PUT /api/agent-configs/{code}` 读取/更新 Agent 配置。
- **验收标准**：
  - [x] 接口返回正确的 `batchReview` 结构。

#### T4.4 前端：任务详情页展示总评

- **状态**：已完成
- **文件**：
  - `frontend-repo/src/views/teacher/GradingDetail.vue`
- **改动点**：
  - 展示 `summary`、`commonIssues`、`strengths`、`teachingAdvice`、`scoreDistribution`。
  - 支持手动触发重新生成，状态轮询。
- **验收标准**：
  - [x] 教师能在任务详情页看到批次总评卡片。

---

## 9. Phase 5：√ 样式优化

### 9.1 目标

增大对勾尺寸，增强手写感。

### 9.2 任务拆分

#### T5.1 DOCX：对勾 PNG 放大

- **状态**：已完成
- **文件**：
  - `backend-repo/src/main/java/com/tap/backend/service/AnnotatedStudentReportService.java`
- **改动点**：
  - PNG 渲染尺寸改为 `400x320`。
  - DOCX 中宽度改为 `320~400 EMU`。
  - 增加旋转角度范围 `-18° ~ +18°`。
- **验收标准**：
  - [x] DOCX 中对勾明显大于当前版本。

#### T5.2 PDF：对勾贝塞尔曲线放大

- **状态**：已完成
- **文件**：
  - `backend-repo/src/main/java/com/tap/backend/service/AnnotatedStudentReportService.java`
- **改动点**：
  - 尺寸从 `36~46pt` 放大到 `58~76pt`。
  - stroke 加粗（`Math.max(4.5f, size / 7f)`）。
  - 增加旋转角度范围 `-18° ~ +18°`。
- **验收标准**：
  - [x] PDF 中对勾明显大于当前版本。

#### T5.3 视觉验收

- **状态**：已完成（代码层面）
- **验收标准**：
  - [x] 对勾视觉上接近手写红笔效果。
  - [x] 不遮挡正文。

---

## 10. Phase 6：图片缩略图接口

### 10.1 目标

让教师能在 evidence 卡片内直接预览原图，无需下载完整报告。

### 10.2 任务拆分

#### T10.1 后端：证据图片代理接口

- **状态**：待实现
- **文件**：
  - `backend-repo/src/main/java/com/tap/backend/api/grading/GradingExportController.java` 或新建 Controller
  - `backend-repo/src/main/java/com/tap/backend/service/StorageService.java`
- **改动点**：
  - 新增 `GET /api/teacher/grading/evidence/{evidenceId}/image`。
  - 根据 `evidence_id` 查询 `image_key`，从 MinIO 读取并返回。
  - 做教师权限校验。
- **验收标准**：
  - [ ] 接口能正确返回图片二进制。
  - [ ] 无权限访问返回 403。

#### T10.2 前端：卡片内预览原图

- **状态**：待实现
- **文件**：
  - `frontend-repo/src/views/teacher/SubmissionReview.vue`
- **改动点**：
  - 图片页 evidence 卡片内显示缩略图。
  - 点击可放大查看。
- **验收标准**：
  - [ ] 教师可在 evidence 卡片内直接看到原图。

---

## 11. 测试策略

### 11.1 单元测试

- Worker：`vlm_client.py` 的 prompt 返回解析。
- 后端：`AnnotatedStudentReportService` 的边界计算、定位算法。

### 11.2 集成测试

- 上传一份真实学生报告，跑完整 pipeline，检查：
  - evidence 是否带 `locationJson`
  - `annotations` 是否正确生成并保存
  - 批注版 DOCX/PDF 是否显示 inline 评语
  - 旧提交是否仍能正常生成报告

### 11.3 回归测试

- 对比改造前后 20 份样本的评分结果，差异应在 ±3% 以内。
- 检查 `vlm_failed` 数量变化。

---

## 12. 部署与灰度计划

### 12.1 配置开关

| 开关 | 作用 | 默认 |
|---|---|---|
| `USE_VLM_UNIFIED_ANALYSIS` | 启用 VLM 统一图片分析 | False |
| `ENABLE_ANNOTATIONS_RENDER` | 启用 inline 批注渲染 | False |
| `ENABLE_BATCH_REVIEW_AGENT` | 启用批次总评 Agent | False |

### 12.2 灰度步骤

1. **开发环境**：全量开启所有开关，人工验证 10 份报告。
2. **测试环境**：开启 Phase 0 + Phase 5，运行 100 份报告，对比 `vlm_failed` 数量和评分稳定性。
3. **预发布环境**：逐步开启 Phase 1/2/3，观察定位准确率和 inline 批注效果。
4. **生产环境**：按任务批次灰度，先小流量，再全量。

---

## 13. 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| AI 返回的 `anchor_text` 无法定位 | inline 批注失效 | 强制要求 AI 返回 evidence 原文片段；后端模糊匹配 + fallback |
| VLM 统一分析增加耗时 | 单份报告处理时间变长 | 结果缓存；必要时保留规则分类器快速路径 |
| 减少 `vlm_failed` 影响噪声保护分数 | 评分策略变化 | 把噪声判断从 evidence 数量改为页面/图片元数据统计 |
| Inline 批注过多导致页面拥挤 | 可读性下降 | 限制每维度 annotations ≤ 3；note 字数 ≤80；自动边界检测 |
| DOCX/PDF 渲染兼容性差异 | 不同版本显示异常 | 提供段落内 run + 底色高亮 fallback |

---

## 14. 附录

### 14.1 关键文档

- 设计稿：`backend-repo/docs/grading/grading-real-correction-redesign.md`

### 14.2 关键代码位置

| 模块 | 文件 |
|---|---|
| 证据提取 Worker | `_inspect_grading_worker/grading_worker/tasks.py` |
| VLM 客户端 | `_inspect_grading_worker/grading_worker/pipeline/vlm_client.py` |
| 图片分类器 | `_inspect_grading_worker/grading_worker/pipeline/image_classifier.py` |
| OCR 处理器 | `_inspect_grading_worker/grading_worker/pipeline/ocr_processor.py` |
| PDF 解析器 | `_inspect_grading_worker/grading_worker/pipeline/pdf_parser.py` |
| 维度评分 LLM | `_inspect_grading_worker/grading_worker/pipeline/scorer.py` |
| 批注报告渲染 | `backend-repo/src/main/java/com/tap/backend/service/AnnotatedStudentReportService.java` |
| 教师评分详情页 | `frontend-repo/src/views/teacher/SubmissionReview.vue` |
| 批改任务页 | `frontend-repo/src/views/teacher/GradingCenter.vue` |
