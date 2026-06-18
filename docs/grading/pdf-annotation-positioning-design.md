# PDF 批注精确定位与标记类型设计文档

> 本文档说明实验报告批改系统中，PDF 批注（√ / × / 波浪线）的位置信息如何产生、如何传递给 AI、以及后端如何基于这些信息进行精确渲染。
> 目标：把当前「基于文本子串搜索」的粗放定位，升级为「带结构化位置引用的精确锚点系统」，确保 × 和波浪线这类对位置敏感的标记落在正确的文字上。

---

## 1. 当前 PDF 批改到底支持哪些标记类型？

**常见误区**：PDF 批改不是只会打勾。当前 `AnnotatedStudentReportService` 已经支持三种标记：

| 类型 | 含义 | 当前代码路径 | 备注 |
|---|---|---|---|
| `CHECK` | 红色对勾 √ | `drawPdfCheckStroke()` | 用于优点/正确处 |
| `CROSS` | 红色叉 × | `drawPdfCrossStroke()` | 用于错误/缺失处 |
| `WAVE` | 波浪线 | `drawPdfWavyUnderline()` | 用于警告/存疑处，通常和文字批注一起出现 |

相关入口：

- `drawPdfAnnotationMarks()`：处理 AI 返回的 `annotations`，按 `type` 字段决定画 √ / × / 波浪线。
- `drawPdfContentMarks()`：旧路径，基于 `dimensionComments` 随机散布标记，也会根据“优点/建议”分别画 √ 和 ×。

但当前实现存在两个明显问题：

1. **`WAVE` 类型在画波浪线的同时还画了 ×**（`case "WAVE" -> drawPdfCrossStroke(...)`）。从语义上讲，`WAVE` 应该只画波浪线，而不应该画叉。这是需要修正的地方。
2. **波浪线的 `startX / endX` 来自整行文本**，而不是 `anchor_text` 这个子串在整行中的精确坐标。如果一行里包含多句，波浪线会覆盖整行，无法精确指向具体句子。

---

## 2. 为什么 × 和波浪线对位置精度要求更高？

### 2.1 对勾 √ 的容错性较高

- 对勾通常放在行末或行右侧空白处，表示“这行/这段是对的”。
- 即使偏上或偏下一点，教师也能理解它指向哪一行。
- 对勾不需要压在具体文字上。

### 2.2 叉 × 和波浪线必须压在目标文字上

- **叉 ×**：教师看到 × 会默认它正下方的文字是错的。如果 × 落在行末空白处，学生会误以为整行都错，或者找不到具体错在哪。
- **波浪线**：波浪线必须画在被质疑的具体文字下方。如果画成整行下划线，就失去“指出具体问题”的意义，变成装饰线。

因此，对于 `CROSS` 和 `WAVE`，我们必须知道：

- `anchor_text` 在页面上的**精确 bbox**（`x0, y0, x1, y1`）
-  ideally，精确到子串级别，而不是整行级别

---

## 3. 精确位置信息从哪里来？

当前 Worker 提取证据时，位置信息比较粗：

| 证据类型 | 当前位置信息 | 缺少 |
|---|---|---|
| PDF 文本 | `page` | 段落索引、行索引、bbox |
| PDF 图片 | `page`、`bbox` | 与附近文本的空间关系 |
| DOCX 文本 | 虚拟 `page`、`paragraphIndex` | 真实页码、bbox |
| DOCX 图片 | 虚拟 `page`、`paragraphIndex` | bbox |

要支持精确批注，需要在 Worker 端把位置挖深一层。

### 3.1 PDF 文本：从 `get_text("text")` 升级到 `get_text("dict")`

当前 `pdf_parser.py`：

```python
text = page.get_text("text") or ""
```

建议改为：

```python
page_dict = page.get_text("dict")
```

`fitz` 返回的结构示例：

```python
{
  "blocks": [
    {
      "type": 0,  # 0 = 文本块
      "bbox": [100, 200, 500, 260],
      "lines": [
        {
          "bbox": [100, 200, 500, 220],
          "spans": [
            {
              "text": "边缘特征主要反映",
              "bbox": [100, 200, 260, 220],
              "font": "SimSun",
              "size": 12,
              "flags": 0
            },
            {
              "text": "灰度变化较大的区域",
              "bbox": [260, 200, 500, 220],
              "font": "SimSun",
              "size": 12
            }
          ]
        }
      ]
    }
  ]
}
```

基于这个结构，可以为每页文本证据附加**行级位置表**：

```json
{
  "page": 2,
  "paragraphIndex": 3,
  "lines": [
    {
      "lineIndex": 0,
      "text": "边缘特征主要反映灰度变化较大的区域",
      "bbox": [100, 200, 500, 220]
    },
    {
      "lineIndex": 1,
      "text": "Sobel 算子对噪声比较敏感",
      "bbox": [100, 222, 360, 242]
    }
  ]
}
```

这样 AI 返回 `anchor_text` 时，后端可以直接匹配到具体 `lineIndex` 和 `bbox`。

### 3.2 子串级 bbox：PDF 行内定位

如果一行里有多个句子，仅知道整行 bbox 还不够。需要计算 `anchor_text` 在行内的子串 bbox。

两种方案：

#### 方案 A：Worker 端预计算（推荐）

在 Worker 端遍历 `spans`，按字符宽度累加，提前算出常见子串的 bbox。但子串组合爆炸，不现实。

更实际的做法是：把每行拆成**语义片段**（按标点、按词组），给每个片段预计算 bbox。例如：

```json
{
  "lineIndex": 1,
  "segments": [
    {"text": "Sobel 算子", "bbox": [100, 222, 180, 242]},
    {"text": "对噪声比较敏感", "bbox": [182, 222, 360, 242]}
  ]
}
```

AI 返回的 `anchor_text` 尽量落在这些片段上。

#### 方案 B：后端 PDFBox 实时计算

后端 `AnnotatedStudentReportService` 已经用 PDFBox 遍历 `TextPosition`。可以在 `PdfAnchorLocator` 里，根据 `anchor_text` 在 `writeString(text, positions)` 中的字符偏移，直接取出子串对应的 `TextPosition` 列表，从而计算精确 bbox。

```java
int idx = text.indexOf(anchorText);
TextPosition first = positions.get(idx);
TextPosition last = positions.get(idx + anchorText.length() - 1);
float anchorStartX = first.getXDirAdj();
float anchorEndX = last.getXDirAdj() + last.getWidthDirAdj();
float anchorY = pageHeight - last.getYDirAdj();
```

这是**最低成本、最精确**的方案，因为 PDFBox 的 `TextPosition` 已经包含每个字符的精确坐标。

**当前代码其实已经做了这件事**，但它把结果存进了 `TextLine.startX / endX`，而 `TextLine.text` 又保留了整行文本，命名上容易造成误解。后面需要把「整行信息」和「anchor 子串信息」拆成两个对象。

### 3.3 DOCX 文本：以段落为锚点

DOCX 没有固定页面坐标，但段落结构稳定。建议：

- 继续用 `paragraphIndex` 作为主要锚点。
- 在段落内部，用 `runIndex` + `offset` 定位到具体 run。
- 对于波浪线/×，由于 DOCX 是流式布局，优先给整段文字加红色下划线或段落后插入批注，而不是追求像素级 bbox。

---

## 4. 喂给 AI 的位置信息长什么样？

### 4.1 EvidenceBlock 结构升级

```json
{
  "evidence_id": "ev-20240618-001",
  "kind": "text",
  "page": 2,
  "content": "边缘特征主要反映灰度变化较大的区域。Sobel 算子对噪声比较敏感。",
  "confidence": null,
  "location": {
    "page": 2,
    "paragraphIndex": 3,
    "lineIndex": 0,
    "bbox": [100, 200, 500, 220],
    "segments": [
      {"text": "边缘特征主要反映灰度变化较大的区域", "bbox": [100, 200, 500, 220]}
    ],
    "origin": "fitz_text_dict"
  }
}
```

### 4.2 Prompt 里喂给 AI 的 evidence_blocks

```json
[
  {
    "dimension_id": 1,
    "name": "实验原理理解",
    "max_score": 15,
    "evidence_blocks": [
      {
        "evidence_id": "ev-20240618-001",
        "kind": "text",
        "page": 2,
        "content": "边缘特征主要反映灰度变化较大的区域。Sobel 算子对噪声比较敏感。",
        "location": {
          "page": 2,
          "paragraphIndex": 3,
          "lineIndex": 0,
          "bbox": [100, 200, 500, 220]
        }
      }
    ]
  }
]
```

### 4.3 Prompt 新增约束示例

```text
Inline annotation rules:
- 每个 annotation 必须引用一个 evidence_id。
- anchor_text 必须是 evidence.content 中真实出现的连续短片段。
- 返回 annotation 时，建议同时提供 location_hint，例如 {"page": 2, "paragraphIndex": 3, "lineIndex": 0}。
- type 只能是 CHECK / CROSS / WAVE 之一。
- CHECK 可放在行右侧空白处；CROSS 和 WAVE 必须精确指向 anchor_text。
- WAVE 只画波浪线，不画叉。
```

### 4.4 AI 返回结构

```json
{
  "dimension_id": 1,
  "score": 12,
  "max_score": 15,
  "comment": "...",
  "evidence_ids": ["ev-20240618-001"],
  "status": "SCORED",
  "annotations": [
    {
      "evidence_id": "ev-20240618-001",
      "type": "CHECK",
      "note": "边缘检测描述准确",
      "anchor_text": "边缘特征主要反映灰度变化较大的区域",
      "location_hint": {"page": 2, "paragraphIndex": 3, "lineIndex": 0},
      "wavy": false
    },
    {
      "evidence_id": "ev-20240618-001",
      "type": "WAVE",
      "note": "Sobel 算子噪声敏感性未展开说明",
      "anchor_text": "Sobel 算子对噪声比较敏感",
      "location_hint": {"page": 2, "paragraphIndex": 3, "lineIndex": 1},
      "wavy": true
    }
  ]
}
```

---

## 5. 后端精确渲染方案

### 5.1 数据对象拆分

当前 `TextLine` 混用了「整行信息」和「anchor 子串信息」。建议拆成两个对象：

```java
/** 页面上的一整行文本 */
private record TextLine(
    int pageIndex,
    String text,
    float startX,
    float endX,
    float baselineY,
    float fontSize
) {}

/** anchor_text 在页面上的精确位置 */
private record TextAnchor(
    int pageIndex,
    String anchorText,
    float startX,
    float endX,
    float baselineY,
    float fontSize,
    TextLine containingLine  // 所在整行，用于上下文
) {}
```

`PdfAnchorLocator` 返回 `TextAnchor`，而不是 `TextLine`。

### 5.2 `PdfAnchorLocator` 增强

```java
private static final class PdfAnchorLocator extends PDFTextStripper {
    private final String anchorText;
    private TextAnchor anchor;

    @Override
    protected void writeString(String text, List<TextPosition> positions) throws IOException {
        if (anchor != null || text == null || positions == null || positions.isEmpty()) {
            return;
        }
        String normalizedText = normalizePdfText(text);
        String normalizedAnchor = normalizePdfText(anchorText);
        int idx = normalizedText.indexOf(normalizedAnchor);
        if (idx < 0) {
            return;
        }
        // 字符级精确定位
        TextPosition first = positions.get(Math.min(idx, positions.size() - 1));
        int endIdx = idx + normalizedAnchor.length() - 1;
        TextPosition last = positions.get(Math.min(endIdx, positions.size() - 1));
        float pageHeight = getCurrentPage().getMediaBox().getHeight();

        float lineStartX = positions.get(0).getXDirAdj();
        float lineEndX = positions.get(positions.size() - 1).getXDirAdj()
                       + positions.get(positions.size() - 1).getWidthDirAdj();

        TextLine line = new TextLine(
            getCurrentPageNo() - 1,
            text.trim(),
            lineStartX,
            lineEndX,
            pageHeight - last.getYDirAdj(),
            last.getFontSizeInPt()
        );

        anchor = new TextAnchor(
            getCurrentPageNo() - 1,
            anchorText,
            first.getXDirAdj(),
            last.getXDirAdj() + last.getWidthDirAdj(),
            pageHeight - last.getYDirAdj(),
            last.getFontSizeInPt(),
            line
        );
    }
}
```

注意 `normalizePdfText()`：需要处理 PDF 提取时的空格、全角半角、换行等噪声。

### 5.3 波浪线精确到子串

```java
private void drawPdfWavyUnderline(PDPageContentStream stream,
                                  TextAnchor anchor) throws IOException {
    if (anchor.endX() - anchor.startX() < 24f) {
        return;
    }
    stream.setStrokingColor(RED_LIGHT);
    stream.setLineWidth(1.1f);
    float waveLen = 9f;
    float amplitude = 2.2f;
    float x = anchor.startX();
    float y = anchor.baselineY() - 3f;
    stream.moveTo(x, y);
    boolean up = true;
    while (x + waveLen <= anchor.endX()) {
        float midX = x + waveLen / 2f;
        float ctrlY = up ? y + amplitude : y - amplitude;
        stream.curveTo(midX, ctrlY, midX, ctrlY, x + waveLen, y);
        x += waveLen;
        up = !up;
    }
    stream.stroke();
}
```

关键改进：波浪线从 `anchor.startX` 画到 `anchor.endX`，而不是 `containingLine.startX` 到 `containingLine.endX`。

### 5.4 叉 × 精确落在 anchor 文字上

当前叉画在行末右侧。建议改为画在 anchor 子串的上方或右侧紧邻处：

```java
float size = 42f;
// 默认放在 anchor 子串上方居中
float markX = (anchor.startX() + anchor.endX()) / 2f;
float markY = anchor.baselineY() + anchor.fontSize() * 0.8f + size * 0.1f;

// 如果上方空间不足（靠近页面顶部），改放到子串右侧
PDRectangle box = visibleBox(page);
if (markY + size * 0.6f > box.getHeight() - 16f) {
    markX = anchor.endX() + size * 0.4f;
    markY = anchor.baselineY() + size * 0.1f;
}

// 如果右侧也放不下，放到左侧
if (markX + size * 0.6f > box.getUpperRightX() - 16f) {
    markX = anchor.startX() - size * 0.4f;
}

drawPdfCrossStroke(stream, markX, markY, size, angle);
```

这样 × 要么压在目标文字上方，要么紧邻目标文字右侧，指向性非常明确。

### 5.5 批注文字位置

批注文字应该放在 × 或波浪线附近，而不是行末：

```java
float noteX = anchor.endX() + 8f;
float noteY = anchor.baselineY();

// 右侧空间不够，放到 anchor 上方
if (noteX + noteWidth > box.getUpperRightX() - 16f) {
    noteX = Math.max(box.getLowerLeftX() + 16f, box.getUpperRightX() - 16f - noteWidth);
    noteY = anchor.baselineY() + anchor.fontSize() + 4f + noteHeight;
}
```

---

## 6. 数据模型与代码改造清单

### 6.1 Worker 层

| 文件 | 改动 |
|---|---|
| `_inspect_grading_worker/grading_worker/pipeline/pdf_parser.py` | `parse_pdf()` 使用 `page.get_text("dict")` 提取行级结构；为每页文本证据生成 `line_locations` |
| `_inspect_grading_worker/grading_worker/pipeline/document_parser.py` | DOCX 继续用 `paragraphIndex`，可额外记录段落在虚拟页内的行号 |
| `_inspect_grading_worker/grading_worker/models/pipeline_models.py` | `EvidenceBlock.location` 增加 `lineIndex`、`bbox`、`segments` |
| `_inspect_grading_worker/grading_worker/tasks.py` | 创建 `EvidenceBlock` 时填充新的 `location` 字段 |
| `_inspect_grading_worker/grading_worker/pipeline/scorer.py` | Prompt 增加 `location` 输入和 `location_hint` 输出要求 |

### 6.2 后端 Java

| 文件 | 改动 |
|---|---|
| `backend-repo/src/main/java/com/tap/backend/domain/grading/EvidenceBlockEntity.java` | `locationJson` 字段已存在，DB 已支持 |
| `backend-repo/src/main/java/com/tap/backend/service/AnnotatedStudentReportService.java` | 新增 `TextAnchor` record；增强 `PdfAnchorLocator` 做子串级定位；修改 `drawPdfAnnotationMarks` 让 WAVE 只画波浪线；CROSS 精确落在 anchor 上 |

### 6.3 前端（可选）

| 文件 | 改动 |
|---|---|
| `frontend-repo/src/views/teacher/SubmissionReview.vue` | evidence 卡片展示更细粒度的位置：第 X 页第 Y 段第 Z 行 |

---

## 7. 需要修正的现有代码问题

### 7.1 WAVE 类型不应该画 ×

当前代码（`AnnotatedStudentReportService.java` 约 854-857 行）：

```java
switch (ann.type().toUpperCase(Locale.ROOT)) {
    case "CROSS" -> drawPdfCrossStroke(stream, markX, markY, size, angle);
    case "WAVE" -> drawPdfCrossStroke(stream, markX, markY, size, angle);  // 这里应该去掉
    default -> drawPdfCheckStroke(stream, markX, markY, size, angle);
}
```

应改为：

```java
switch (ann.type().toUpperCase(Locale.ROOT)) {
    case "CROSS" -> drawPdfCrossStroke(stream, markX, markY, size, angle);
    case "WAVE" -> { /* WAVE 只画波浪线，不画标记 */ }
    default -> drawPdfCheckStroke(stream, markX, markY, size, angle);
}
```

### 7.2 `TextLine` 命名误导

当前 `PdfAnchorLocator` 返回的 `TextLine` 中，`startX / endX` 实际上已经是 anchor 子串的坐标，但 `text` 是整行文本。建议改为返回 `TextAnchor`，语义更清晰。

---

## 8. 验收指标

| 指标 | 目标 | 说明 |
|---|---|---|
| Anchor 命中率 | ≥ 95% | AI 返回的 annotation 能成功定位到文档位置 |
| 子串级定位准确率 | ≥ 90% | 波浪线/× 的落点与 `anchor_text` 实际位置偏差 ≤ 10pt |
| WAVE 不误画 × | 100% | WAVE 类型只画波浪线 |
| 批注不遮挡正文 | ≥ 99% | 批注文字不覆盖正文行 |
| 向后兼容 | 100% | 无 `location_hint` 的旧提交仍能正常生成报告 |

---

## 9. 附录：坐标系说明

- **PyMuPDF (Worker)**：`bbox` 原点在页面左上角，y 轴向下。
- **PDFBox (后端 Java)**：默认坐标系原点在页面左下角，y 轴向上。

因此 Worker 提取的 `bbox` 传给后端后，需要做转换：

```java
float pdfBoxY = pageHeight - fitzY;
```

当前 `AnnotatedStudentReportService` 的 `PdfLineCollector` 已经做了这种转换：

```java
float pageHeight = getCurrentPage().getMediaBox().getHeight();
baselineY = pageHeight - last.getYDirAdj();
```

Worker 端如果直接把 `bbox` 写入数据库，需要约定是 **PyMuPDF 坐标**还是**PDFBox 坐标**，建议统一用 PyMuPDF 坐标存储，后端使用时转换。

---

## 10. 总结

1. **PDF 批改当前已经支持 √ / × / 波浪线三种标记**，不是只会打勾。
2. **× 和波浪线必须精确落在 `anchor_text` 上**，不能只是行末或整行下划线。
3. 精确位置的核心来源是 **PDFBox 的 `TextPosition` 字符级坐标**（后端）和 **PyMuPDF 的 `get_text("dict")` 行级结构**（Worker）。
4. 需要把 `TextLine` 拆分为 `TextLine` + `TextAnchor`，让波浪线和 × 使用子串级坐标。
5. 需要修正 `WAVE` 类型错误画 × 的问题。
6. 建议先实施后端的 `TextAnchor` 改造，这是成本最低、收益最大的第一步。
