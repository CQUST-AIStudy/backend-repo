# PDF 批注精确定位改造 — 遗留问题、测试计划与批次总评联动需求

> 本文档汇总 `AnnotatedStudentReportService` 改造后仍存在的问题、下一步视觉优化需求、测试用例规格，以及批次总评与批改完成状态的联动要求。
> 本文档只描述“要做什么”和“验收标准”，具体代码实现由后续任务完成。

---

## 1. 当前改造已完成的内容

后端 `AnnotatedStudentReportService` 已完成以下改造：

1. 新增 `TextAnchor` 抽象，区分「anchor 子串精确坐标」与「所在整行信息」。
2. `PdfAnchorLocator` 增加文本归一化（去空白、全角转半角、大小写统一）和索引映射，提升 anchor_text 匹配成功率。
3. 修复 `WAVE` 类型错误画 × 的问题：WAVE 现在只画波浪线。
4. `CROSS` 标记位置从行末改到 `anchor_text` 上方居中，指向性更强。
5. 波浪线从 `anchor.startX` 精确画到 `anchor.endX`，不再覆盖整行。
6. 新增 `AnnotatedStudentReportServiceTest` 单元/集成测试，覆盖 DOCX、合成 PDF、真实 PDF。

---

## 2. 当前仍存在的问题

### 2.1 归一化逻辑去掉了所有空格，可能破坏带空格 anchor 的匹配

**位置**：`AnnotatedStudentReportService.normalizePdfTextWithIndexMap()`

**问题描述**：
当前实现跳过所有 `Character.isWhitespace(ch)`，把原文和 anchor 都压缩成无空格字符串。这会导致英文或中英文混排时，原本被空格隔开的词被错误地“粘”在一起：

- PDF 原文：`"model training results and observations"`
- AI 返回 anchor：`"model results"`
- 归一化后原文：`"modeltrainingresultsandobservations"`
- 归一化后 anchor：`"modelresults"`
- 结果：匹配失败（实际上原文存在 `"model"` 和 `"results"`，只是中间隔着其他词）。

**影响**：
英文报告、代码截图中的英文注释、中英混排报告容易出现 anchor 定位失败。

**建议修复**：
把「删除所有空白」改为「合并连续空白为单个空格并保留」，同时保留索引映射：

```java
boolean lastWasSpace = false;
for (int i = 0; i < value.length(); i++) {
    char ch = value.charAt(i);
    if (Character.isWhitespace(ch)) {
        if (!lastWasSpace) {
            normalized.append(' ');
            indexMap.add(i);
            lastWasSpace = true;
        }
    } else {
        normalized.append(normalizePdfChar(ch));
        indexMap.add(i);
        lastWasSpace = false;
    }
}
```


---

### 2.3 `usedAnchors` 按 `anchorText` 去重会误删合法 annotation

**位置**：`AnnotatedStudentReportService.drawPdfAnnotationMarks()`

**问题描述**：

```java
if (usedAnchors.contains(ann.anchorText())) {
    continue;
}
```

现在定位精度已经提高，两个不同维度完全可能指向同一句原文但给出不同批注。例如：
- 维度 A：`{"anchor_text":"边缘检测描述准确", "type":"CHECK", "note":"表述清晰"}`
- 维度 B：`{"anchor_text":"边缘检测描述准确", "type":"WAVE", "note":"可补充阈值说明"}`

按 `anchorText` 去重会把第二个 annotation 丢弃。

**建议修复**：
去重键改为 `(anchorText, note)`，或移除去重、改为基于页面坐标的防重叠检测。

---

### 2.4 CROSS fallback 位置仍可能压住正文

**位置**：`AnnotatedStudentReportService.drawPdfAnnotationMarks()`

**问题描述**：
当 anchor 靠近页面顶部时，CROSS 会从「上方居中」fallback 到「右侧紧邻」：

```java
if (markY + size * 0.6f > box.getUpperRightY() - 16f) {
    markX = anchor.endX() + size * 0.4f;
    markY = anchor.baselineY() + size * 0.1f;  // 仅比 baseline 高 0.1*size
}
```

`markY` 只比 baseline 高 `size*0.1`，基本与文字同高，容易压住 anchor 后面的文字。

**建议修复**：
fallback 后的 markY 至少抬高到文字上方：

```java
markY = anchor.baselineY() + anchor.fontSize() + size * 0.5f;
```

---

### 2.5 批注文字可能与正文重叠

**位置**：`AnnotatedStudentReportService.drawPdfAnnotationMarks()`

**问题描述**：

```java
drawPdfMarginNote(stream, fontSelection, box, containingLine, anchor.endX() + 8f, ann.note());
```

批注从 anchor 子串末尾 `+8f` 处开始向右画。如果 note 较长，会覆盖该行后半部分文字。

**建议修复**：
在 `drawPdfMarginNote` 中增加碰撞检测：
1. 计算 note 所需宽度。
2. 如果 `anchor.endX() + 8f + noteWidth` 超出页面右边界，把批注放到行上方空白处。
3. 行上方空间也不够时，再考虑左侧或换页。

---

### 2.6 缺少模糊匹配 fallback

**位置**：`AnnotatedStudentReportService.PdfAnchorLocator`

**问题描述**：
当前只有「归一化后的精确子串匹配」。如果 AI 返回的 anchor 与 PDF 原文差一个字符（如 PDF 有顿号、AI 没有），定位失败，整个 annotation 被静默丢弃。

**建议修复**：
在精确匹配失败后，增加模糊匹配 fallback：
- 计算最长公共子串（LCS）或编辑距离。
- 取相似度 ≥ 0.8 的候选。
- 若仍失败，记录日志并回退到随机散布。

---

### 2.7 旧的 `drawPdfContentMarks` 路径未升级

**位置**：`AnnotatedStudentReportService.drawPdfContentMarks()`

**问题描述**：
当 AI 没有返回 annotations 时，系统走旧路径。该路径仍然：
- 波浪线覆盖整行；
- × 画在行末；
- 批注画在固定右侧位置。

**建议**：
旧路径是兼容性 fallback，可以保留原行为。如果资源允许，也可以让旧路径复用新的 `TextAnchor` 能力，但优先级较低。

---

## 3. 视觉优化需求：对勾 √ 再放大 1.5 倍

### 3.1 当前尺寸

- **PDF**：`size = 58f + random.nextInt(18)`，约 58~76 pt。
- **DOCX**：PNG 宽度约 320~400 EMU。

### 3.2 目标尺寸

按 1.5 倍放大：

- **PDF**：`size = 87f + random.nextInt(27)`，约 87~114 pt。
- **DOCX**：PNG 宽度约 480~600 EMU，对应高度等比例放大。

### 3.3 需要注意的问题

1. 放大后必须同步检查：
   - 对勾右侧是否有足够空白；
   - 对勾是否压住正文；
   - 是否超出页面边界。
2. 如果空间不足，应将对勾移到行上方或左侧，而不是缩小尺寸。
3. 手写感的旋转角度、笔触粗细可以维持当前比例，但需视觉上与放大后的尺寸协调。

---

## 4. 测试计划：制作一份“明显错误报告”测试 PDF

### 4.1 测试目标

验证以下功能在真实格式 PDF 上正确工作：

1. **精准打叉**：× 落在具体错误文字上方/右侧，不飘到行末。
2. **波浪线划线**：波浪线精确画在错误子串下方，不覆盖整行。
3. **错误内容批注**：批注文字显示在错误位置附近，不遮挡正文。
4. **正确内容打勾**：√ 大小合适、位置明显、不压住文字。

### 4.2 测试 PDF 格式要求

模仿 `"D:\Downloads\2023440415邹名格人工智能实验2 (1).pdf"` 的格式，包含：

#### 封面页
- 学校名称：AI大学
- 课程名称、实验名称
- 学生姓名、学号、班级、指导教师
- 实验成绩（用于测试分数定位）

#### 正文页（至少 3~4 页）

| 章节 | 内容要求 | 故意设置的错误 |
|---|---|---|
| 实验目的 | 1~2 段 | 无错，用于打勾测试 |
| 实验原理 | 公式 + 文字说明 | 公式写错一个符号；或概念表述错误 |
| 实验内容/步骤 | 分步骤列出 | 步骤顺序错误；或缺少关键步骤 |
| 实验代码 | 粘贴一段 Python/Matlab 代码 | 包含明显运行错误，如 `cv2.imread()` 路径错误、`for` 循环范围错误、未定义变量 |
| 运行结果 | 代码输出截图或文字 | 结果与代码逻辑不符；或结果解释错误 |
| 结果分析 | 2~3 段 | 结论与前面数据矛盾；或缺少对异常值的分析 |
| 实验总结 | 1 段 | 无错或轻微问题 |

#### 错误示例（供 AI 返回 anchor_text 使用）

1. **原理错误**：
   - 原文：`"Sobel 算子使用二阶微分来检测边缘"`
   - 正确：Sobel 是一阶微分。
   - AI 应返回 `type=CROSS`，anchor_text=`"二阶微分"`。

2. **代码错误**：
   - 原文：`"img = cv2.imread('C:/data/image.jpg')"`
   - 正确：路径不存在或应使用相对路径。
   - AI 应返回 `type=WAVE`，anchor_text=`"C:/data/image.jpg"`。

3. **结果分析错误**：
   - 原文：`"从图 1 可以看出，准确率达到了 100%"`
   - 正确：图 1 显示准确率只有 82%。
   - AI 应返回 `type=CROSS`，anchor_text=`"准确率达到了 100%"`。

4. **正确内容**：
   - 原文：`"实验环境为 Python 3.10 + OpenCV 4.8"`
   - AI 应返回 `type=CHECK`，anchor_text=`"Python 3.10 + OpenCV 4.8"`。

### 4.3 测试 PDF 文件位置

建议把测试 PDF 放入版本控制：

```
backend-repo/src/test/resources/reports/
└── 2023440415-邹名格-人工智能实验2-with-errors.pdf
```

测试代码中通过类路径读取，避免使用绝对路径：

```java
InputStream is = getClass().getResourceAsStream("/reports/2023440415-邹名格-人工智能实验2-with-errors.pdf");
```

### 4.4 测试用例设计

#### 用例 1：精准打叉

```java
@Test
void renderPdfDrawsCrossOnWrongFormula() throws Exception {
    byte[] source = loadTestReport();
    var annotations = List.of(
        new AnnotationEntry("ev-1", "CROSS",
            "Sobel 是一阶微分算子，不是二阶",
            "二阶微分", false)
    );
    var rendered = service.render("report.pdf", source, "测试学生",
        new BigDecimal("75"), "评语", List.of(), "张老师", annotations);

    // 断言：渲染后的 PDF 第 N 页右侧/上方存在红色叉像素
    // 且红色叉的重心位于 "二阶微分" 文字区域附近
}
```

#### 用例 2：波浪线精确划线

```java
@Test
void renderPdfDrawsWavyUnderlineOnWrongPath() throws Exception {
    byte[] source = loadTestReport();
    var annotations = List.of(
        new AnnotationEntry("ev-1", "WAVE",
            "建议使用相对路径或检查文件是否存在",
            "C:/data/image.jpg", true)
    );
    var rendered = service.render("report.pdf", source, "测试学生",
        new BigDecimal("75"), "评语", List.of(), "张老师", annotations);

    // 断言：波浪线只覆盖 "C:/data/image.jpg" 子串区域，不覆盖整行
}
```

#### 用例 3：错误批注不遮挡正文

```java
@Test
void renderPdfNoteDoesNotOverlapBodyText() throws Exception {
    byte[] source = loadTestReport();
    var annotations = List.of(
        new AnnotationEntry("ev-1", "CROSS",
            "图 1 准确率显示为 82%，与结论不符\n请核对数据和结论",
            "准确率达到了 100%", false)
    );
    var rendered = service.render("report.pdf", source, "测试学生",
        new BigDecimal("75"), "评语", List.of(), "张老师", annotations);

    // 断言：批注文字像素不与正文文字像素重叠（可通过渲染后图像分析或 PDFBox 文本提取验证）
}
```

#### 用例 4：对勾大小 1.5 倍且可见

```java
@Test
void renderPdfCheckMarkIsLarger() throws Exception {
    byte[] source = loadTestReport();
    var rendered = service.render("report.pdf", source, "测试学生",
        new BigDecimal("85"), "评语", List.of("优点：实验环境说明完整"), "张老师");

    // 断言：红色对勾像素区域面积大于旧版本的 1.5 倍
    // 或通过对勾 bounding box 尺寸断言
}
```

### 4.5 视觉验收方式

1. 测试输出渲染后的 PDF 到 `target/test-artifacts/`。
2. 用 `PDFRenderer` 生成 PNG，人工抽查或像素分析。
3. 建立“错误报告标准样本”，每次改造后都用同一份 PDF 跑一遍，对比渲染结果。

---

## 5. 批次总评与批改完成状态联动需求

### 5.1 当前问题

批次批改完成后，界面上长时间显示 **“AI 批次总评”**，等了好几个小时状态仍未更新。这给用户造成批改未完成的错觉。

根因：
- 批次总评 Agent 目前可能需要手动触发，或自动触发后状态未正确回写。
- 前端“批改完成”判定只依赖单个 submission 的评分状态，未等待批次总评完成。

### 5.2 业务需求

1. **批改完成后自动生成批次总评**：
   - 当一个 `GradingTask` 下的所有 submission 都评分完成后，系统自动触发 `GradingBatchReviewService` 生成总评。
   - 不需要教师手动点击“生成总评”。

2. **完成状态合并**：
   - 任务级别的“完成/成功”状态应同时满足：
     - 所有 submission 评分完成（`status = SCORED` 或 `COMPLETED`）；
     - 批次总评生成完成（`batchReviewStatus = COMPLETED`）。
   - 只要批次总评还在生成中，界面就应显示“AI 批次总评生成中”，而不是“已完成”。

3. **失败重试与兜底**：
   - 如果批次总评生成失败，应记录失败原因，并允许教师手动重试。
   - 失败不应阻塞学生报告的查看，但任务状态应显示为“总评生成失败”。

### 5.3 涉及模块

| 模块 | 文件/类 | 改动点 |
|---|---|---|
| 后端任务完成判定 | `GradingTaskService` 或相关状态机 | 增加“所有 submission 完成且 batchReviewStatus=COMPLETED”才视为任务完成 |
| 批次总评触发 | `GradingBatchReviewService` | 在所有 submission 完成后自动触发，无需 Controller 手动调用 |
| 批次总评状态 | `GradingTaskEntity` | `batchReviewStatus` 状态机：PENDING → GENERATING → COMPLETED/FAILED |
| 前端状态展示 | `GradingCenter.vue` / `GradingDetail.vue` | 根据合并后的状态显示“AI 批次总评生成中”或“已完成” |
| 异步执行 | Spring `@Async` / 消息队列 | 避免阻塞评分主流程 |

### 5.4 状态流转建议

```
GradingTask 状态：
  GRADING          → 还有 submission 未评分
  BATCH_REVIEWING  → 所有 submission 已评分，正在生成批次总评
  COMPLETED        → 所有 submission 已评分 + 批次总评已完成
  FAILED           → 评分或总评生成失败
```

如果现有状态字段不想改，可以保留现有任务状态，通过 `batchReviewStatus` 字段表达总评进度：

```java
public boolean isTaskFullyCompleted() {
    return allSubmissionsScored() && "COMPLETED".equals(batchReviewStatus);
}
```

### 5.5 验收标准

- [ ] 一个任务下最后一份报告评分完成后，5 分钟内自动触发批次总评生成。
- [ ] 批次总评生成完成前，任务列表/详情页显示“AI 批次总评生成中”。
- [ ] 批次总评生成完成后，任务状态变为“已完成”，并展示总评卡片。
- [ ] 批次总评生成失败时，显示失败原因和“重新生成”按钮。
- [ ] 教师无需手动点击任何按钮即可看到最终总评。

---

## 6. 后续任务清单

| 编号 | 任务 | 优先级 | 负责人 |
|---|---|---|---|
| T1 | 修复 `normalizePdfTextWithIndexMap`：保留单个空格 | P0 | 待定 |
| T2 | 修复分数定位：限制在第一页搜索 `"实验成绩"` | P0 | 待定 |
| T3 | 修改 `usedAnchors` 去重键为 `(anchorText, note)` | P1 | 待定 |
| T4 | 优化 CROSS fallback 位置，避免压文字 | P1 | 待定 |
| T5 | 批注 note 增加碰撞检测，避免覆盖正文 | P1 | 待定 |
| T6 | 对勾 √ 尺寸放大 1.5 倍 | P1 | 待定 |
| T7 | 增加模糊匹配 fallback | P2 | 待定 |
| T8 | 制作“明显错误报告”测试 PDF 并加入 `src/test/resources` | P1 | 待定 |
| T9 | 编写精准打叉、波浪线、批注不遮挡的测试用例 | P1 | 待定 |
| T10 | 实现批次总评自动触发 + 完成状态合并 | P1 | GPT / 待定 |
| T11 | 前端根据合并状态展示“AI 批次总评生成中” | P1 | GPT / 待定 |

---

## 7. 附录：测试 PDF 模板（文字稿）

以下内容可用于快速生成测试 PDF：

```
AI大学
实验报告

课程名称：人工智能导论
实验名称：基于 Sobel 算子的边缘检测
学院：计算机科学与工程学院
专业班级：人工智能 2201
学生姓名：测试学生
学号：2023000000
指导教师：张老师
实验成绩：

一、实验目的
1. 掌握 Sobel 算子的基本原理；
2. 使用 OpenCV 实现图像边缘检测。

二、实验原理
Sobel 算子使用二阶微分来检测边缘，通过对图像进行高斯滤波后计算梯度幅值。
（错误：Sobel 是一阶微分算子。）

三、实验代码
import cv2
img = cv2.imread('C:/data/image.jpg')
gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
sobelx = cv2.Sobel(gray, cv2.CV_64F, 2, 0, ksize=3)
（错误：路径不存在；dx=2 错误，应为 1。）

四、运行结果
从图 1 可以看出，准确率达到了 100%。
（错误：图 1 实际准确率 82%。）

五、结果分析
实验结果说明 Sobel 算子对所有噪声都不敏感，适用于所有场景。
（错误：Sobel 对噪声敏感。）

六、实验总结
通过本次实验，掌握了边缘检测的基本流程。
```

将以上内容排版成与学生报告一致的 PDF 即可作为标准测试样本。

---

## 7. 教师上传报告与学生身份匹配机制

### 7.1 当前机制

目前系统通过解析上传文件的 **文件名** 来尝试匹配学生：

1. `GradingTaskService.createTask()` 接收教师上传的 PDF/DOCX 文件。
2. `extractStudentName(originalFilename)` 去掉扩展名，把整段文件名当作 `studentName`。
3. `applyUnifiedSubmissionIdentity()` 调用 `GradingUnifiedLinkService.resolveSubmissionIdentity()`，按以下优先级匹配：
   - 优先用 `submission.studentId`（如果之前已绑定）
   - 其次从文件名或 `studentName` 中提取 **6~20 位数字**作为学号
   - 最后用 `studentName` 在班级/课程花名册中做唯一姓名匹配
4. 匹配成功后，把 `studentProfileId`、`studentNo`、`studentName`、`className` 写回 `grading_submission`。

相关代码：

```java
// GradingTaskService.java
sub.setStudentName(extractStudentName(originalFilename));
applyUnifiedSubmissionIdentity(task, sub);

// GradingUnifiedLinkService.java
private String extractCandidateStudentNo(GradingSubmissionEntity submission) {
    String direct = normalizeStudentNo(submission.getStudentNo());
    if (direct != null) return direct;
    String fromFilename = extractStudentNoFromText(submission.getOriginalFilename());
    if (fromFilename != null) return fromFilename;
    return extractStudentNoFromText(submission.getStudentName());
}
```

### 7.2 当前机制的问题

| 问题 | 说明 | 示例 |
|---|---|---|
| 依赖文件名格式 | 如果文件名不含学号，只能靠姓名匹配 | `"张三-实验报告.pdf"` 没有学号 |
| 重名学生无法区分 | 同一班级可能出现同名学生 | 两个 "王磊" |
| 文件名不规范导致匹配失败 | 学生命名各式各样 | `"报告最终版.pdf"`、`"2023实验2.pdf"` |
| 匹配过程对教师不可见 | 教师不知道系统把这份报告对应到了哪个学生 | 匹配错误也无法及时发现 |
| 自动发布风险 | 如果匹配错了，分数会发到错误学生账号 | 需要教师确认后再发布 |

### 7.3 期望的完整流程

建议改为：**自动匹配 + 教师确认 + 手动发布**。

```
教师上传报告 → 系统自动解析文件名并匹配学生 → 展示匹配预览 → 教师确认/修正 → 教师点击「发布」→ 结果写回学生端
```

#### 第一步：上传时绑定班级/课程（已部分支持）

创建批改任务时，教师需要选择：
- 实验/课程（`experimentId`）
- 班级（`classId`）

系统根据 `experimentId + classId` 解析出 `assignment_offering_id`，从而拿到该班级的学生花名册。

#### 第二步：自动匹配策略（增强版）

在现有策略基础上，增加更智能的匹配方式：

1. **文件名正则匹配学号**
   - 支持更多学号格式：纯数字、带前缀（如 `2023440415`、`<学号>2023440415`）
   - 支持分隔符：`-`、`_`、` `、`—`

2. **文件名匹配姓名**
   - 从文件名中提取中文姓名，与花名册 `real_name` 匹配
   - 例如 `"2023440415-邹名格-人工智能实验2.pdf"` 应同时提取学号和姓名

3. **PDF 内容辅助匹配**
   - 如果文件名无法匹配，尝试从 PDF 第一页提取姓名、学号
   - 通常在报告封面有「学生姓名」「学号」字段

4. **置信度评分**
   - 每条匹配给出置信度：高（学号+姓名都匹配）、中（仅学号或仅姓名）、低（未匹配）

#### 第三步：教师确认匹配结果

在 `GradingDetail.vue` 的提交列表中，增加一列「对应学生」：

| 文件名 | 系统匹配学生 | 置信度 | 操作 |
|---|---|---|---|
| 2023440415-邹名格-实验2.pdf | 邹名格（2023440415） | 高 | 确认 / 修改 |
| 张三-实验报告.pdf | 张三（2023100001） | 中 | 确认 / 修改 |
| 报告最终版.pdf | 未匹配 | 低 | 手动选择 |

- 高置信度：默认已确认，教师可修改。
- 中/低置信度：需要教师手动确认后才能发布。
- 未匹配：教师从花名册下拉选择学生。

#### 第四步：教师手动发布

教师查看完 AI 批改结果后，点击「发布成绩给学生」按钮，系统才执行：

1. 调用 `GradingSubmissionController.publishToStudentReport(submissionId)`
2. 把总分写回 `score` 表或学生实验记录
3. 把学生可查看的批注版报告写入存储
4. 更新 `grading_submission.published_at`

**注意**：当前后端已经有 `POST /api/grading/submissions/{id}/publish-report` 接口，前端需要增加对应的发布按钮和状态展示。

### 7.4 数据模型建议

在 `GradingSubmissionEntity` 增加以下字段：

```java
@Column(name = "matched_student_id")
private Long matchedStudentId;  // 教师确认后绑定的学生

@Column(name = "match_confidence")
private String matchConfidence; // HIGH / MEDIUM / LOW

@Column(name = "match_status")
private String matchStatus;     // AUTO_MATCHED / MANUAL_CONFIRMED / UNMATCHED

@Column(name = "published_at")
private Instant publishedAt;    // 发布时间

@Column(name = "is_published")
private boolean published;      // 是否已发布给学生
```

### 7.5 前端交互设计

#### 1. 匹配预览弹窗

上传文件后、创建任务前，先弹窗展示匹配结果：

```
┌─────────────────────────────────────────────┐
│  已识别 35 份报告，匹配情况如下                │
├─────────────────────────────────────────────┤
│  ✅ 高置信度匹配：30 份                        │
│  ⚠️  需手动确认：4 份                          │
│  ❌ 未匹配：1 份                               │
├─────────────────────────────────────────────┤
│  [查看并修正匹配]    [直接创建任务]            │
└─────────────────────────────────────────────┘
```

#### 2. 任务详情页匹配状态

在 `GradingDetail.vue` 的提交表格中：

- 显示「对应学生」列
- 未确认的行用黄色背景高亮
- 已发布的行显示「已发布」标签

#### 3. 发布按钮

两种发布粒度：

- **单份发布**：在 `SubmissionReview.vue` 或提交表格行内，点击「发布给学生」。
- **批量发布**：在 `GradingDetail.vue` 顶部，点击「批量发布已确认的成绩」。

按钮状态：

- 如果还有未确认匹配的学生，按钮置灰并提示「请先确认学生匹配」。
- 如果已全部发布，按钮显示「已发布」。

### 7.6 安全与权限

1. 教师只能发布自己创建的任务下的成绩。
2. 发布前必须校验 `submission.task.teacherId == currentTeacherId`。
3. 发布后学生才能看到成绩和批注报告；未发布前学生端不显示。
4. 支持「撤回发布」：教师可以撤销已发布成绩，学生端恢复为未发布状态。

### 7.7 验收标准

- [ ] 上传文件名 `"学号-姓名-实验名.pdf"` 能自动高置信度匹配到对应学生。
- [ ] 上传文件名 `"姓名-实验名.pdf"` 能按姓名在班级花名册中匹配（唯一时自动确认，重名时提示教师选择）。
- [ ] 上传文件名无法识别时，教师可以手动选择学生。
- [ ] 匹配结果未确认前，不能发布成绩。
- [ ] 教师在 `SubmissionReview` 页面可以单份发布成绩。
- [ ] 教师在 `GradingDetail` 页面可以批量发布已确认的成绩。
- [ ] 发布后，学生端 `ExperimentDetail` 能看到分数和批注报告下载入口。
- [ ] 支持撤回已发布成绩。

---

## 8. 学生端查看批改结果

### 8.1 当前状态

目前学生端 `ExperimentDetail.vue` 顶部显示 `currentExp.score`，但没有专门的「AI 批改结果」标签页。学生无法查看：
- 各维度得分
- 教师评语
- 分项批注
- 红笔批注版报告

### 8.2 建议改造

在 `ExperimentDetail.vue` 增加一个标签页：**「AI 批改结果」**。

展示内容：
1. **总评分**：`总分 / 满分`
2. **各维度得分**：维度名称、得分、评语
3. **教师总评**：`finalReviewComment`
4. **下载批注报告**：提供下载 AI 批改版 PDF/DOCX 的入口
5. **证据概览**：展示关键证据截图/文本（可选）

如果成绩尚未发布，显示：

```
教师正在批改中，请耐心等待...
```

### 8.3 API 需求

新增学生端 API：

```
GET /api/student/experiments/{experimentId}/grading-result
```

返回：

```json
{
  "published": true,
  "totalScore": 84,
  "maxScore": 100,
  "finalReviewComment": "整体完成较好...",
  "dimensions": [
    {"name": "实验原理理解", "score": 14, "maxScore": 15, "comment": "..."}
  ],
  "annotatedReportUrl": "/api/grading/reports/{reportId}"
}
```

---

## 9. 更新后的完整任务清单

| 编号 | 任务 | 优先级 |
|---|---|---|
| T1 | 修复归一化保留空格 | P0 |
| T2 | 修复分数定位限制在第一页 | P0 |
| T3 | 修改 `usedAnchors` 去重键 | P1 |
| T4 | 优化 CROSS fallback 位置 | P1 |
| T5 | 批注 note 碰撞检测 | P1 |
| T6 | 对勾 √ 放大 1.5 倍 | P1 |
| T7 | 增加模糊匹配 fallback | P2 |
| T8 | 制作错误报告测试 PDF | P1 |
| T9 | 编写定位测试用例 | P1 |
| T10 | 批次总评自动触发 + 完成状态合并 | P1 |
| **T11** | **增强文件名解析：同时提取学号和姓名** | **P1** |
| **T12** | **上传后展示匹配预览，支持教师确认/修正** | **P1** |
| **T13** | **增加发布按钮：单份发布 + 批量发布** | **P1** |
| **T14** | **学生端增加「AI 批改结果」标签页** | **P1** |
| **T15** | **支持撤回发布** | **P2** |

---

## 10. 错误演示动画方案选型：AI 生成动画 vs Python Tutor 式代码可视化

### 10.1 需求背景

当 AI 在学生实验报告中发现代码错误时，希望生成一段动画向学生展示「为什么错了」。例如：
- `cv2.imread('C:/data/image.jpg')` 路径错误
- `for (int i = 0; i <= n; i++)` 数组越界
- `Sobel(gray, CV_64F, 2, 0, 3)` 参数错误

有两种技术路线可选：

1. **AI 生成 HTML 动画**（类似 `reference-ai-video-agent`，也是当前 `backend-repo` 中 `AnimationExplainService` 的做法）
2. **Python Tutor 式代码执行可视化**（真实执行/模拟执行代码，逐步展示变量、内存、调用栈）

### 10.2 两种方案对比

| 维度 | AI 生成 HTML 动画 | Python Tutor 式代码可视化 |
|---|---|---|
| **核心原理** | 用 LLM 生成 HTML/CSS/JS 动画代码 | 真实执行或符号执行代码，捕获每一步状态 |
| **准确性** | 中-低，可能画错变量值、步骤顺序 | 高，展示的是真实执行状态 |
| **可信度** | 学生可能怀疑“动画是不是编的” | 高，因为来自真实执行 |
| **适用场景** | 概念讲解、算法直觉、 motivation | 代码调试、变量追踪、错误定位 |
| **生成成本** | 高，每段错误都要调 LLM 生成动画 | 中，执行一次即可，前端用现成渲染器 |
| **延迟** | 10~60 秒 | 1~5 秒 |
| **对 C 语言支持** | 通用，但需要 prompt 描述 C 语义 | 需要 C 执行后端（可用 Python Tutor 的 C 后端或 GDB） |
| **与现有系统集成** | `AnimationExplainService` 已存在，可复用 | 需要新增 C 代码执行沙箱 |
| **错误展示能力** | 可展示“错误后果”的示意图 | 可精确定位到哪一行、哪个变量出错 |

### 10.3 判断结论

**对于“代码错误演示”这个具体场景，强烈推荐 Python Tutor 式代码可视化，而不是 AI 生成动画。**

原因：

1. **错误演示需要精确性**：学生需要看到具体哪一行、哪个变量、哪个指针出了问题。AI 生成动画可能画得很漂亮，但变量值可能是幻觉。
2. **C 语言代码不适合用动画“表演”**：C 语言错误（数组越界、空指针、类型转换、内存泄漏）本质上是执行状态问题，用逐步执行可视化最自然。
3. **Python Tutor 已经被验证**：全球数百万学生用它学习编程，教育效果有数据支撑。
4. **成本更低、更稳定**：执行代码比生成动画便宜，速度也更快。

**AI 生成动画适合作为补充，而不是替代**：
- 在展示代码错误前，用 5~10 秒动画介绍背景概念。
- 在代码可视化后，用动画总结“正确写法应该是什么”。
- 对于非代码类错误（如实验原理理解错误），用 AI 生成动画解释概念。

### 10.4 推荐架构：混合方案

```
错误类型识别
    │
    ├─ 代码类错误（C/Python） ──▶ Python Tutor 式执行可视化
    │                              ├─ 展示逐步执行
    │                              ├─ 高亮出错行
    │                              └─ 展示变量/内存变化
    │
    ├─ 概念/原理类错误 ─────────▶ AI 生成 HTML 动画（复用 AnimationExplainService）
    │                              ├─ 用动画解释正确概念
    │                              └─ 用对比展示错误 vs 正确
    │
    └─ 结果分析类错误 ──────────▶ 图文对比 + 简单动画
```

### 10.5 Python Tutor 式方案的具体实现建议

#### 方案 A：复用 Python Tutor 开源组件（推荐）

Python Tutor 有开源版本，支持 Python、Java、JavaScript、C、C++。核心流程：

1. 前端使用 `js/pytutor.js` 渲染执行轨迹。
2. 后端调用 Python Tutor 的执行后端（如 `c_trace.py` 或 `pg_logger.py`）。
3. 把代码和输入传给后端，后端返回 JSON 格式的执行轨迹。
4. 前端用 `Visualizer` 组件渲染。

优点：
- 成熟、稳定、教育效果好。
- 支持 C 语言（通过 C 后端）。

缺点：
- 需要部署 Python Tutor 的执行后端。
- C 语言沙箱需要安全配置（限制执行时间、内存、禁止危险函数）。

#### 方案 B：基于 GDB 自定义 C 代码可视化

如果只需要支持 C 语言，可以：

1. 用 `gcc -g` 编译学生代码。
2. 用 `gdb` 逐行执行，捕获变量值和堆栈。
3. 把 `gdb` 输出解析成执行轨迹 JSON。
4. 前端用 D3.js 或 Canvas 绘制内存、变量、指针图。

优点：
- 完全可控，可以自定义展示样式。
- 可以集成到现有 Java 后端（通过调用外部进程）。

缺点：
- 开发工作量大。
- 需要处理 GDB 输出解析、沙箱安全、崩溃恢复。

#### 方案 C：轻量级伪执行 + AI 解释

如果学生代码比较简单（数组、循环、指针基础操作），可以：

1. 用正则/AST 解析代码结构。
2. 模拟执行关键变量变化。
3. 生成每一步的变量状态表。
4. 前端用表格/简单动画展示。

优点：
- 实现简单，不需要真实执行 C 代码。
- 速度快，安全。

缺点：
- 只能处理简单模式。
- 复杂代码（递归、指针、库函数）无法准确模拟。

### 10.6 与现有 AnimationExplainService 的结合

`backend-repo` 已经有一个 `AnimationExplainService`，它可以根据主题生成 HTML 动画讲解。这个服务可以继续用于：

- **非代码类错误**：如 Sobel 算子原理错误、实验步骤错误。
- **错误动画开场**：在展示 Python Tutor 可视化前，用 5~10 秒动画引入问题。
- **正确做法总结**：在代码可视化后，用动画总结正确写法。

建议不要改动现有 `AnimationExplainService`，而是**新增一个专门用于代码执行可视化的服务**：

```
backend-repo/src/main/java/com/tap/backend/service/animation/
├── AnimationExplainService.java        # 已有：概念讲解动画
├── CodeExecutionVisualizerService.java # 新增：代码执行可视化
└── ErrorAnimationOrchestrator.java     # 新增：根据错误类型选择可视化方式
```

### 10.7 学生端展示

在「AI 批改结果」标签页中，对于代码类错误，增加一个「查看执行过程」按钮：

```
┌─────────────────────────────────────────┐
│  ❌ 代码错误：数组越界                      │
│  位置：第 12 行                            │
│  说明：循环条件 i <= n 导致访问 a[n]        │
│                                         │
│  [查看执行过程]  [查看正确写法]             │
└─────────────────────────────────────────┘
```

点击后弹窗或新标签页展示 Python Tutor 式可视化：
- 左侧：代码，高亮当前执行行
- 右侧：变量面板、内存图
- 底部：播放/暂停/上一步/下一步

### 10.8 安全考虑

真实执行学生提交的 C 代码存在安全风险：
- 无限循环
- 恶意系统调用
- 内存耗尽
- 栈溢出

必须配置沙箱：
- 使用 Docker 容器或 seccomp 限制系统调用。
- 设置 CPU 时间限制（如 2 秒）。
- 设置内存限制（如 64MB）。
- 禁止网络访问、文件写入。
- 对危险函数（`system`、`exec`、`fork`、`open` 等）做静态检查或运行时拦截。

### 10.9 更新后的任务清单

| 编号 | 任务 | 优先级 |
|---|---|---|
| **T16** | **调研 Python Tutor 开源方案对 C 语言的支持程度** | **P1** |
| **T17** | **搭建 C 代码执行沙箱（Docker/seccomp + 资源限制）** | **P1** |
| **T18** | **实现代码执行轨迹提取服务** | **P1** |
| **T19** | **前端实现 Python Tutor 式可视化播放器** | **P1** |
| **T20** | **根据错误类型自动选择动画 or 代码可视化** | **P2** |
| **T21** | **用 AnimationExplainService 为代码错误生成引导动画** | **P2** |

### 10.10 最终建议

**优先做 Python Tutor 式代码可视化。** 它是这个场景下最准确、最可信、教育效果最好的方案。AI 生成动画可以作为点缀和概念补充，复用现有 `AnimationExplainService` 即可。

