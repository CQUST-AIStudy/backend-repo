# 实验报告真实批改系统改造方案

## 1. 背景与问题

当前批改系统本质上是一个「基于证据片段的自动评分 + 红笔视觉效果生成」工具：

- 证据提取依赖 OCR/VLM/文本切片，噪声较大
- LLM 根据片段给出分数和评语，但缺乏对报告具体位置的精确判断
- 报告上的 √、×、波浪线为程序化随机/均匀散布，未与 AI 评语形成对应关系
- 分项评语集中放在文档末尾，阅读体验割裂；应紧跟对应错误/优点位置
- 对勾尺寸偏小，缺乏真实教师批改 handwriting 的随意感

本文档针对以下 4 个改造需求，给出可行性判断、数据模型变更、AI 输出约定与渲染方案。

---

## 2. 改造目标

| 编号 | 需求 | 目标 |
|---|---|---|
| R1 | AI 决定 √/×/波浪线位置 | 让 AI 返回「这条评语/这个判断对应报告里的哪一页、哪一段、哪一行」，渲染时精确落到该位置 |
| R2 | 在 ×/波浪线旁加 inline 批注 | 把评语直接显示在错误点附近，而不是全部堆在文档最后 |
| R3 | 批次级总评 Agent | 在批量批改结束后，由 Agent 阅读整个批次所有学生的评分结果，输出一份批次总评（共性问题、优秀范例、教学建议） |
| R4 | √ 样式放大、更像手写 | 增大尺寸、增加不规则抖动、模拟真实红笔笔触 |

---

## 3. 可行性判断

### R1：AI 决定标记位置 —— 可行，但依赖证据提取质量

**核心思路**：让 LLM 在返回 `score`/`comment` 的同时，返回该判断所依据的「证据定位信息」。

- 已有证据块包含 `page` 字段，可以定位到页
- DOCX/PDF 中的段落/文本行可以被编号，AI 返回段落索引或文本片段即可
- 对于「错误位置」，可以让 AI 在证据文本里指出具体句子，后端通过字符串匹配或 embedding 相似度定位到文档坐标

**风险**：
- 如果 AI 返回的文本片段在原文中不存在（幻觉），定位会失败
- 需要增加 fallback：定位失败时退化成当前随机散布策略

### R2：Inline 批注 —— 可行

DOCX 和 PDF 都支持在页面空白处追加文字批注：

- DOCX：在段落末尾或右侧空白插入文本框/批注框
- PDF：用 PDFBox 在文本行右侧或上方绘制文字

**关键设计**：批注文字要简短（1-2 句），太长会撑爆页面。可以走「摘要 inline + 详细评语仍放末尾」的折中方案。

### R3：批次总评 Agent —— 可行

当前 `GradingTask` 是按批次组织的，一个 task 包含多个 `GradingSubmission`。改造点：

- 在任务完成（或大部分提交评分完成）后，触发一个异步 Agent
- Agent 读取该 task 下所有 submission 的 scores、comments、evidence
- 输出 JSON：`{"summary": "...", "commonIssues": [...], "strengths": [...], "teachingAdvice": "..."}`
- 前端在「批改任务详情页」展示该总评

### R4：√ 样式放大 —— 可行且低风险

当前 `AnnotatedStudentReportService` 已经自己绘制 checkmark（DOCX 用 PNG，PDF 用贝塞尔曲线）。只需调整：

- 尺寸系数：DOCX PNG 从当前 `158~200 EMU` 宽度放大到 `280~380 EMU`
- PDF  stroke 粗细和路径范围同步放大
- 增加随机旋转和笔触粗细抖动

---

## 4. 数据模型与 API 变更

### 4.1 证据块增加段落定位信息

当前 `EvidenceBlockEntity`：

```java
class EvidenceBlockEntity {
    String evidenceId;
    EvidenceKind kind;       // text / ocr / vlm / vlm_failed
    Integer page;
    String content;
    BigDecimal confidence;
    String imageKey;
    String bboxJson;         // 已存在，但主要为图片
    String metadataJson;     // 已存在
}
```

**建议新增字段**：

```java
class EvidenceBlockEntity {
    // ... 原有字段

    /**
     * 证据在原始文档中的段落/行定位信息。
     * JSON 示例：
     * {
     *   "docx": {"paragraphIndex": 12},
     *   "pdf":  {"page": 2, "lineIndex": 5, "bbox": [x1,y1,x2,y2]}
     * }
     */
    String locationJson;

    /**
     * 该证据关联的评分维度 ID 列表。
     * 在证据提取阶段即可初步判断，也可由评分阶段回填。
     * JSON 示例：[1, 3]
     */
    String dimensionIdsJson;
}
```

对应前端 `evidenceDto` 返回：

```json
{
  "evidenceId": "ev-51-...",
  "kind": "text",
  "page": 2,
  "content": "...",
  "location": {
    "paragraphIndex": 12,
    "lineIndex": 5,
    "bbox": [100, 200, 500, 220]
  },
  "dimensionIds": [1, 3]
}
```

### 4.2 ScoreItem 增加位置判定字段

当前 `ScoreItemEntity` 已有 `evidenceIdsJson`，可继续复用。但为了让 AI 给出更精确的「批注落点」，建议新增：

```java
class ScoreItemEntity {
    // ... 原有字段

    /**
     * AI 返回的 inline 批注列表。
     * 每个批注包含：批注文本（可包含 \n 换行）、目标 evidence_id、目标页/段落/行、标记类型。
     * JSON 示例：
     * [
     *   {
     *     "type": "CHECK",
     *     "evidenceId": "ev-51-...",
     *     "page": 2,
     *     "paragraphIndex": 12,
     *     "note": "边缘检测描述准确"
     *   },
     *   {
     *     "type": "CROSS",
     *     "evidenceId": "ev-51-...",
     *     "page": 3,
     *     "paragraphIndex": 18,
     *     "note": "缺少对锐化核方向性的解释，\n建议说明为何不是全方向锐化核"
     *   }
     * ]
     */
    String annotationsJson;
}
```

### 4.3 GradingTask 增加批次总评字段

```java
class GradingTaskEntity {
    // ... 原有字段

    /**
     * 批次总评，由 Agent 在任务完成后生成。
     * JSON 示例见第 6 节。
     */
    String batchReviewJson;

    /**
     * 批次总评生成状态：PENDING / GENERATING / COMPLETED / FAILED
     */
    String batchReviewStatus;
}
```

### 4.4 API 变更

| 接口 | 变更 |
|---|---|
| `GET /api/teacher/grading/submissions/{id}` | response 中 `scores[].annotations` 增加 inline 批注数组 |
| `GET /api/teacher/grading/tasks/{id}` | response 中增加 `batchReview` 对象 |
| `POST /api/teacher/grading/tasks/{id}/generate-batch-review` | 手动触发批次总评生成 |

---

## 5. AI 输出新约定

### 5.1 维度评分 prompt 调整

当前 prompt 要求返回：

```json
{
  "dimension_id": 1,
  "score": 8,
  "max_score": 10,
  "comment": "...",
  "evidence_ids": ["ev-..."],
  "status": "SCORED"
}
```

**新 prompt 要求返回**：

```json
{
  "dimension_id": 1,
  "score": 8,
  "max_score": 10,
  "comment": "可选：维度整体总结，最终可合成教师总评",
  "evidence_ids": ["ev-..."],
  "status": "SCORED",
  "annotations": [
    {
      "evidence_id": "ev-...",
      "type": "CHECK",
      "note": "边缘检测描述准确，\n体现了对灰度变化的理解",
      "anchor_text": "边缘特征主要反映图像中灰度变化较大的区域"
    },
    {
      "evidence_id": "ev-...",
      "type": "CROSS",
      "note": "缺少对方向性锐化核的解释，\n建议说明为何不是全方向锐化核",
      "anchor_text": "这个核是方向性锐化核",
      "wavy": true
    }
  ]
}
```

**Prompt 新增约束**：

1. `annotations` 数量建议 1-3 条，只选最能代表该维度得分的证据
2. `note` 是**分项评语**，直接显示在错误/优点旁边的 inline 批注，**不再放到文档末尾**
3. `note` 允许使用 `\n` 换行，但总行数建议不超过 3 行，总字数不超过 80 字
4. `note` 必须位于 PDF/DOCX 页面可渲染区域内，后端渲染时会做边界检测，超出则自动换行或缩放
5. `anchor_text` 必须是 evidence 原文中出现的连续文字片段，便于后端定位
6. `type` 只能是 `CHECK`（对勾）、`CROSS`（叉）、`WAVE`（波浪线）之一
7. `wavy: true` 表示同时在该行下方画波浪线

**关于 `comment` 字段的说明**：
- `comment` 不再作为文档末尾的分项评语使用
- 可以作为该维度的内部总结，用于最终合成「教师总评」时参考
- 文档末尾只保留由 `finalReviewComment` 生成的统一教师总评

### 5.2 定位算法（后端）

AI 返回 `anchor_text` 后，后端执行：

1. 在对应 evidence 的 `content` 中查找 `anchor_text`
2. 如果能找到：
   - 从 `EvidenceBlockEntity.location` 获取该 evidence 在文档中的起始位置
   - 结合 anchor_text 在 evidence 中的 offset，估算具体行/段落
3. 如果找不到：
   - fallback：在该 evidence 所在段落末尾画标记
   - 再失败：使用当前随机散布策略

### 5.3 批次总评 Agent prompt

```
你是一位高校实验课主讲教师。以下是一个批改任务中所有学生的评分结果汇总。
请阅读后输出一份面向教师的批次总评，帮助老师把握全班情况。

输出严格 JSON：
{
  "summary": "总体情况，80-120字",
  "commonIssues": [
    {"issue": "共性问题1", "affectedRatio": "约30%", "suggestion": "教学改进建议"},
    {"issue": "共性问题2", "affectedRatio": "约15%", "suggestion": "教学改进建议"}
  ],
  "strengths": ["全班表现较好的方面1", "方面2"],
  "teachingAdvice": "下一次课或下一次实验可以重点讲什么、布置什么补救练习",
  "scoreDistribution": {"high": 5, "medium": 12, "low": 3}
}

输入数据：
- 实验名称：{experimentName}
- 评分维度：{dimensions}
- 各学生得分与评语：{submissionsSummary}
```

---

## 6. 渲染方案

### 6.1 评语分布策略

| 类型 | 位置 | 说明 |
|---|---|---|
| 分项评语（维度 annotations 的 `note`） | **Inline，紧跟对应错误/优点段落** | 这是主要的批改反馈 |
| 教师总评（`finalReviewComment`） | **文档末尾** | 保持原有位置不变 |
| 批次总评 | 教师端任务详情页 | 不写入学生报告 |

### 6.2 DOCX 渲染

**Inline 批注实现**：

- 在 anchor 段落之后插入一个新的段落
- 新段落右对齐，使用红色文本框或带浅红底色的 run
- 文本框内写 `note`；如果 `note` 包含 `\n`，则按换行拆成多行
- 文本框宽度根据页面剩余空间动态计算，**不能超出页面右边界**
- ×/波浪线标记放在 anchor 段落末尾或新段落开头

**边界处理**：

```java
float pageWidth = ...;  // A4 约为 595pt
float maxNoteWidth = pageWidth - marginRight - anchorEndX;
if (estimatedNoteWidth > maxNoteWidth) {
    // 方案 A：换行显示
    noteLines = wrapText(note, maxNoteWidth);
    // 方案 B：移动到页面左上方空白处
}
```

**结构示例**：

```
[原段落] ...这个核是方向性锐化核...
[批注段落]                                     × 缺少对方向性锐化核的解释
                                              建议说明为何不是全方向锐化核
```

### 6.3 PDF 渲染

**Inline 批注实现**：

- 找到 anchor 文本行
- 先按 `\n` 把 `note` 拆成多行
- 测量每行文字宽度，计算整体 bounding box
- 在该行右侧空白处绘制红色多行文本
- × 画在行末右侧，波浪线画在该行 baseline 下方 3-5pt

**位置计算（含边界检测）**：

```java
List<String> noteLines = splitNoteByNewline(note);
float lineHeight = fontSize + 2;
float noteWidth = noteLines.stream().mapToDouble(l -> measureWidth(l)).max();
float noteHeight = noteLines.size() * lineHeight;

float noteX = line.endX + 20;
float noteY = line.baselineY;

// 右边界检测：超出则左移
if (noteX + noteWidth > pageWidth - margin) {
    noteX = Math.max(margin, pageWidth - margin - noteWidth);
}

// 上边界检测：超出顶部则下移
if (noteY + noteHeight > pageHeight - margin) {
    noteY = line.baselineY - noteHeight - 6;
}

for (int i = 0; i < noteLines.size(); i++) {
    drawText(noteX, noteY - i * lineHeight, noteLines.get(i));
}
```

### 6.4 √ 样式放大方案

**DOCX**：

```java
// 当前
int widthEMU = Units.toEMU(158 + random.nextInt(42));  // 约 4.0~5.3mm

// 建议
int widthEMU = Units.toEMU(320 + random.nextInt(80));  // 约 8.4~10.5mm
int heightEMU = (int)(widthEMU * 0.78);                // 保持宽高比
```

**PDF**：

```java
// 当前
float size = 36f + random.nextInt(10);

// 建议
float size = 58f + random.nextInt(18);
float strokeWidth = Math.max(4.5f, size / 7f);
```

**手写感增强**：

- 旋转角度范围从当前 `-10° ~ +8°` 扩大到 `-18° ~ +18°`
- 笔触粗细在路径中轻微变化（DOCX 无法变粗细，PDF 可以分段绘制）
- 对勾 PNG 生成时增加一点笔尖飞白效果（用更粗的 stroke + 轻微 alpha 渐变）

---

## 7. 图片证据（vlm_failed）展示优化

### 7.1 问题描述

在教师批改详情页的证据材料区域，经常出现多条如下形态的卡片：

> 页 5  
> 该页包含图片，但 AI 未能从中提取可用内容。建议直接查看原报告页面。  
> ℹ 可点击「下载批注报告」查看原图

这些卡片的标签为红色「图片未识别」，文案使用「未能提取」「建议查看原报告」等失败语气，容易让教师误以为系统出了 bug，而不是「这张图片只是无法被文本化，但已作为页面上下文参与评分」。

### 7.2 根因分析

`vlm_failed` 证据块是 Worker 在图片提取流程中的**正常兜底产物**，并非 crash 类 bug。具体链路如下：

1. `pdf_parser.py` 从每一页抽出嵌入图片（若页面文字极少，还会把整页渲染成一张图）。
2. `image_classifier.py` 对图片做简单分类：代码截图、终端日志、图表、折线图、其他。
3. Worker 按分类调用 VLM/OCR：
   - 图表/折线图走 `describe` 任务，返回图片内容描述。
   - 其他图片走 `extract_text` 任务，尝试提取可见文字。
4. 若 VLM 和 OCR 都拿不到「足够有用」的内容，就创建一条 `vlm_failed` 证据。

当前环境里 `OCR_STRATEGY = "vlm_only"`，意味着 VLM 失败时**不会**再用 OCR 兜底；同时图片分类器默认把大量非代码图归为代码截图，导致它们走更严格的文字提取流程。再加上阈值（VLM 需 ≥20 字或 confidence≥0.55，OCR 需 ≥40 字）对截图、照片、流程图、装饰性图片并不宽松，因此不少页面都会留下 `vlm_failed`。

此外，Worker 的生成逻辑是：只要页面上还有一张图没被识别，且该页未记录过失败，就会生成一条 `vlm_failed`——**即使该页已经有充足的文字证据**。这是卡片数量过多的主要原因。

### 7.3 已实施的前端优化

为避免教师产生「系统故障」的错觉，先对 `SubmissionReview.vue` 做了视觉和文案中性化处理：

- 标签颜色从红色错误样式改为蓝色中性样式。
- 标签文案从「图片未识别」改为「图片页」。
- 内容文案从「AI 未能从中提取可用内容」改为「该页包含图片，已作为页面上下文参与评分。如需查看原图，可下载批注报告」。
- 移除红色 ℹ 提示行和无意义的置信度显示。
- 在 `LucideIcon.vue` 中新增 `image` 图标，卡片左侧显示图片图标，强化「这是图片页」而非「这是错误」的认知。

### 7.4 建议的后续 Worker 层优化

前端优化只是缓解，要进一步减少这类卡片数量，需在 Worker 层调整生成策略：

#### 7.4.1 有文字页面不再生成 vlm_failed

如果一页已经有充足的文字证据（例如文字长度超过 100 字符），则该页的图片提取失败不应再生成 `vlm_failed` 证据块。图片仍然会被保存到 MinIO 供报告渲染使用，但不再作为「未识别证据」展示给教师。

#### 7.4.2 vlm_only 策略增加 OCR 兜底

当前 `OCR_STRATEGY="vlm_only"` 过于激进。建议：

- 当 VLM 无法提取文字时，对代码截图、终端日志类图片回退到 PaddleOCR。
- 或者将策略改为 `qwen_first`，让 OCR 作为二次机会。

#### 7.4.3 改进图片分类器

非文字密集图片（界面截图、手绘草图、装饰图）不应默认走 `extract_text` 任务，而应走 `describe` 任务。同时给 `describe` 返回加更严格的内容检查，避免把空 JSON 当作有效 `vlm` 证据存下。

#### 7.4.4 提供证据图片缩略图接口

`EvidenceBlockEntity.image_key` 已经保存了 MinIO 对象 key，但前端目前只能下载完整批注报告才能看到原图。建议后端新增一个代理接口（如 `GET /api/teacher/grading/evidence/{evidenceId}/image`），把 MinIO 图片直接返回给前端，教师可在卡片内直接预览原图，无需下载整个报告。

#### 7.4.5 用 VLM 统一分析替代规则分类器（推荐）

当前流程先用 `image_classifier.py` 做规则分类，再把图片送进 VLM。这本质上是在 VLM 看原图之前先「瞎猜」一次。既然 Qwen-VL 本身就是多模态模型，完全可以让它直接看原图并同时完成两件事：

1. 判断图片类型（code_screenshot / terminal_log / diagram / plot / photo / other）
2. 根据类型决定提取文字还是生成描述

**建议的 VLM prompt：**

```text
Analyze this image carefully and return strict JSON only.
Schema: {"image_type":"code_screenshot|terminal_log|diagram|plot|photo|other",
"recognized_text":"...","summary":"...","confidence":0.0}.
Choose image_type based on the main content.
For code screenshots or terminal logs, put the extracted visible text in recognized_text.
For diagrams, plots, photos, or other non-text-heavy images, put a concise Chinese description in summary.
If visible text exists even in diagrams, include it in recognized_text.
confidence should reflect how certain you are about the content (0.0-1.0).
```

**后端处理逻辑：**

```python
if image_type in ("code_screenshot", "terminal_log"):
    # 文字密集型：用 recognized_text 作为 vlm 证据
    useful = len(recognized_text) >= 20 or confidence >= 0.55
else:
    # 视觉型：用 summary 作为 vlm 证据
    useful = len(summary) >= 20 or len(recognized_text) >= 20 or confidence >= 0.55
```

**优势：**

| 方面 | 规则分类器 + 分开调用 | VLM 统一分析 |
|---|---|---|
| 分类准确率 | 低，默认兜底为代码截图 | 高，直接看原图判断 |
| VLM 调用次数 | 1~2 次/图（分类后提取，失败再 describe） | 1 次/图 |
| 代码复杂度 | 需要 `image_classifier.py` + 两套任务 | 一个 prompt + 一个处理函数 |
| 失败率 | 非代码图被强制提取文字，容易失败 | 非文字图自然走到 summary 分支 |

**实施建议：**

- 在 `vlm_client.py` 新增 `task="analyze"` 的 prompt。
- 在 `tasks.py` 新增 `_analyze_image_with_vlm()`，返回结构化结果。
- 保留旧的 `extract_text` / `describe` 任务用于兼容和兜底。
- 用配置开关（如 `USE_VLM_UNIFIED_ANALYSIS`）控制启用，便于灰度验证。
- 当统一分析不可用时（VLM 未配置、返回错误），再回退到规则分类器。

### 7.5 影响评估

| 改动 | 对评分的影响 | 对前端展示的影响 |
|---|---|---|
| 有文字页面不生成 vlm_failed | 轻微： scorer.py 中「提取噪声较高」最低分保护逻辑依赖 failed_blocks 计数，需同步改为基于页面/图片元数据 | 显著减少「图片页」卡片数量 |
| vlm_only 增加 OCR 兜底 | 可能提高可用证据量，对分数有正面影响 | 减少 vlm_failed 数量 |
| 图片分类器改进 | 可能让更多图片被描述为 vlm 证据 | 减少 vlm_failed 数量，但需确保 vlm 内容质量 |
| VLM 统一分析替代规则分类器 | 显著提高图片证据利用率，对分数有正面影响；降低误分类导致的提取失败 | 减少 vlm_failed 数量，且 vlm 证据内容更合理 |
| 图片缩略图接口 | 无 | 教师可直接在页面内查看原图，体验大幅提升 |

---

## 8. 实现阶段

### Phase 1：证据定位（1-2 周）

- 在 `DocumentParser` / `PdfParser` / `OcrProcessor` 中为每个 evidence block 回填 `locationJson`
- 前端展示 evidence 时显示「来自第 X 页第 Y 段」
- 风险验证：定位准确率

### Phase 2：AI 返回 annotations（2-3 周）

- 修改 `scorer.py` prompt，要求返回 `annotations` 数组
- 修改 `_normalize_result` 解析并保存 `annotationsJson`
- 增加 fallback 定位逻辑

### Phase 3：Inline 批注渲染（2 周）

- 修改 `AnnotatedStudentReportService`
- DOCX：段落旁插入批注文本框
- PDF：行旁绘制批注文字
- 同时支持旧格式（无 annotations 时走原逻辑）

### Phase 4：批次总评 Agent（1-2 周）

- 新增 `BatchReviewAgent` / service
- 在任务完成时异步触发
- 前端任务详情页展示总评

### Phase 5：√ 样式优化（3-5 天）

- 调整尺寸、旋转、笔触
- 视觉验收

---

## 9. 风险与缓解

| 风险 | 影响 | 缓解方案 |
|---|---|---|
| AI 返回的 `anchor_text` 在原文中找不到 | 标记无法定位 | 强制要求 AI 返回 evidence 原文片段；后端增加模糊匹配 + fallback |
| Inline 批注太多，页面拥挤 | 可读性下降 | 限制每维度 annotations ≤ 3；short_note ≤ 16 字；自动换页/换行 |
| DOCX 批注文本框兼容性差 | 不同 Word 版本显示异常 | 使用段落内 run + 底色高亮作为 fallback |
| 批次总评 Agent 输入 token 过长 | 成本高、易超时 | 对学生结果做摘要（只传分数+关键词），不传完整证据 |
| √ 放大后遮挡正文 | 排版破坏 | 严格计算右侧空白，空间不足时移到上方 |
| 减少 `vlm_failed` 证据块影响「提取噪声」最低分保护 | 评分策略变化 | 把噪声判断从 evidence 数量改为页面/图片元数据统计 |

---

## 10. 验收标准

- [ ] AI 返回的维度结果包含 `annotations` 字段
- [ ] 每个 annotation 都能成功映射到文档具体位置（准确率 ≥ 80%）
- [ ] 批注版 DOCX/PDF 中，× 和波浪线旁出现 inline 简短批注
- [ ] 教师任务详情页展示「批次总评」卡片
- [ ] √ 尺寸明显大于当前版本，视觉上接近手写红笔效果
- [ ] 无 annotations 的旧提交仍能正常生成批注报告
- [ ] 图片页证据卡片不再使用红色错误样式，文案中性化
- [ ] 有充足文字证据的页面不再生成 `vlm_failed` 证据块（或至少不再展示给教师）
- [ ] 教师可在证据卡片内直接预览原图，无需下载完整报告

---

## 11. 附录：当前代码关键位置

| 模块 | 文件 |
|---|---|
| 证据提取 Worker | `_inspect_grading_worker/grading_worker/tasks.py` |
| 维度评分 LLM | `_inspect_grading_worker/grading_worker/pipeline/scorer.py` |
| 图片分类器 | `_inspect_grading_worker/grading_worker/pipeline/image_classifier.py` |
| VLM 客户端 | `_inspect_grading_worker/grading_worker/pipeline/vlm_client.py` |
| OCR 处理器 | `_inspect_grading_worker/grading_worker/pipeline/ocr_processor.py` |
| PDF 解析器 | `_inspect_grading_worker/grading_worker/pipeline/pdf_parser.py` |
| 评分结果 DB 模型 | `backend-repo/src/main/java/com/tap/backend/domain/grading/ScoreItemEntity.java` |
| 证据块 DB 模型 | `backend-repo/src/main/java/com/tap/backend/domain/grading/EvidenceBlockEntity.java` |
| 批注报告渲染 | `backend-repo/src/main/java/com/tap/backend/service/AnnotatedStudentReportService.java` |
| 教师评分详情页 | `frontend-repo/src/views/teacher/SubmissionReview.vue` |
| 图标组件 | `frontend-repo/src/components/LucideIcon.vue` |
| 批改任务页 | `frontend-repo/src/views/teacher/GradingCenter.vue` |
