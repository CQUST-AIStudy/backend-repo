# 教师端 Hybrid RAG 详细设计与执行方案

## 1. 文档目标

本文档用于指导 `AI_Ds` 教师端 RAG 系统的重构与落地，目标不是做一个“能问答”的简单知识库，而是做一套可维护、可扩展、可观测的教师课程知识库系统。

最终目标：

- 教师上传课程资料后，系统自动完成文本抽取、切块、索引构建、向量化入库。
- 查询时采用 `Milvus + BM25 + rerank + evidence compression` 的混合检索链路。
- 回答必须可追溯到课程资料，默认带引用。
- 当单个环节失效时系统能够降级，不出现“整个教师端 RAG 不可用”的状态。

---

## 2. 当前现状

### 2.1 已有基础

项目目前已经具备以下基础能力：

- 课程空间与教师权限控制
  - `src/main/java/com/tap/backend/api/rag/CourseSpaceController.java`
  - `src/main/java/com/tap/backend/service/CourseSpaceService.java`
- 文档上传与文档实体存储
  - `src/main/java/com/tap/backend/service/DocumentIngestService.java`
  - `src/main/java/com/tap/backend/domain/document/DocumentEntity.java`
- 文本抽取
  - `src/main/java/com/tap/backend/infra/text/FileTextExtractor.java`
- 文档切块与 MySQL 落库
  - `src/main/java/com/tap/backend/service/RagDocumentProcessor.java`
- BM25 索引
  - `src/main/java/com/tap/backend/rag/LuceneBm25Service.java`
- 向量检索封装
  - `src/main/java/com/tap/backend/rag/MilvusSearchService.java`
- 融合排序、rerank、证据压缩等组件雏形
  - `src/main/java/com/tap/backend/rag/FusionRankService.java`
  - `src/main/java/com/tap/backend/rag/TopRerankService.java`
  - `src/main/java/com/tap/backend/rag/EvidenceCompressService.java`

### 2.2 现有核心问题

当前教师端不可用，不是因为完全没有 RAG 代码，而是主链路没有闭合：

1. 上传后虽然能分块和 BM25 建索引，但没有稳定把 `child chunk` 写入 Milvus。
2. `MilvusSearchService` 只有搜索能力，没有 collection 初始化、upsert、按文档删除、重建等生命周期能力。
3. `RagChatController` 当前是维护兜底控制器，不是真正的问答主链。
4. 文档抽取对扫描 PDF 不稳定，之前会直接导致资料入库失败。
5. 缺少统一的处理状态与重建机制，教师端无法判断“失败在哪一层”。

### 2.3 已完成的基础修复

本轮已完成一项必须前置的基础改造：

- PDF 文本抽取新增三级兜底：
  - 普通 PDF 文本抽取
  - 本地 Tesseract OCR
  - 千问多模态模型识别页图像

涉及文件：

- `src/main/java/com/tap/backend/infra/text/FileTextExtractor.java`
- `src/main/java/com/tap/backend/infra/text/PdfFallbackTextExtractor.java`
- `src/main/java/com/tap/backend/service/DocumentIngestProperties.java`
- `src/main/resources/application.yml`

这意味着后续设计可以默认“扫描 PDF 具备自动兜底能力”。

---

## 3. 目标架构

### 3.1 总体架构

```text
教师上传资料
  -> 文档入库(Document)
  -> 文本抽取(PDF文本 / OCR / Qwen-VL)
  -> 父子块切分(parent / child chunks)
  -> child embedding
  -> Milvus 向量入库
  -> Lucene BM25 建索引
  -> 查询改写 / 意图识别
  -> Hybrid Recall(Milvus + BM25)
  -> Fusion Rank
  -> Top Rerank
  -> Parent 聚合
  -> Evidence Compression
  -> LLM 回答 + 引用
  -> QA 日志 / 教师反馈 / 分析面板
```

### 3.2 设计原则

- 检索优先，不让大模型直接“猜”。
- 子块召回，父块回答，避免碎片化上下文。
- 默认严格模式，仅基于课程资料作答。
- 各层可降级：
  - Milvus 不可用时退化为 BM25-only
  - rerank 不可用时退化为 heuristic rank
  - LLM 不可用时输出证据型整理答案
- 结果必须带引用。

---

## 4. 数据模型设计

### 4.1 现有实体

已存在并可复用：

- `CourseSpaceEntity`
- `CourseSpaceDocumentEntity`
- `DocChunkEntity`
- `QaLogEntity`
- `DocChunkAnnotationEntity`

### 4.2 建议增强字段

#### course_space_document

建议补充或明确以下字段：

- `status`
  - `PENDING`
  - `PROCESSING`
  - `READY`
  - `FAILED`
- `chunk_count`
- `embedding_status`
  - `PENDING`
  - `RUNNING`
  - `READY`
  - `FAILED`
- `index_status`
  - `PENDING`
  - `RUNNING`
  - `READY`
  - `FAILED`
- `error_message`
- `last_indexed_at`
- `index_version`

#### doc_chunk

建议补充或确认字段：

- `chunk_type`
  - `parent`
  - `child`
- `parent_id`
- `chunk_index`
- `chapter_path`
- `page_range`
- `token_count`
- `embedding_model`
- `embedding_dim`
- `milvus_id`
- `is_deleted`

#### qa_log

建议确保记录：

- `query`
- `answer_text`
- `retrieved_chunk_ids`
- `citations_json`
- `top1_score`
- `coverage_score`
- `mode`
- `used_web`
- `intent_type`
- `feedback`
- `latency_ms`

### 4.3 Milvus collection 设计

建议 collection：`course_chunks`

字段设计：

- `chunk_id`：主键
- `vector`：浮点向量
- `course_space_id`
- `doc_id`
- `parent_id`
- `chunk_type`
- `chapter_path`
- `page_range`
- `doc_type`

建议：

- 只写入 `child chunk`
- 距离度量使用 `COSINE`
- 索引优先 `HNSW`，资源受限时可退到 `IVF_FLAT`

---

## 5. 文档处理链路设计

### 5.1 上传阶段

入口：

- `CourseSpaceController.uploadDocument`
- `CourseSpaceService.uploadDocument`
- `DocumentIngestService.ingestMultipartFiles`

处理流程：

1. 上传原始文件到对象存储。
2. 创建 `DocumentEntity`。
3. 抽取全文文本。
4. 将截断版写入 `document.extracted_text`。
5. 将完整文本按需写入对象存储。
6. 创建 `CourseSpaceDocumentEntity` 并触发异步处理。

### 5.2 文本抽取策略

顺序如下：

1. 常规 PDF 文本提取
2. OCR 提取
3. Qwen-VL 页图像识别

策略要求：

- OCR 页数上限可配置。
- 多模态页数上限可配置。
- 对抽取结果做质量评估。
- 如果提取结果仍不可用，明确标记为 `FAILED`，错误信息指向扫描质量问题，而不是笼统地提示“不支持”。

### 5.3 Chunk 设计

采用父子块结构：

- `parent chunk`
  - 用于最终回答上下文
  - 建议 1200 到 1800 字符
- `child chunk`
  - 用于召回
  - 建议 250 到 450 字符

切分原则：

- 先按段落和标题切。
- 再做滑动窗口补足。
- 保留 overlap。
- `chapter_path` 尽量由标题、编号、章节识别得到。

### 5.4 异步处理状态机

建议将 `RagDocumentProcessor` 分成更明确的阶段：

1. `TEXT_READY`
2. `CHUNK_READY`
3. `VECTOR_READY`
4. `INDEX_READY`
5. `READY`

如果暂时不扩展数据库字段，也应在日志中清晰打印阶段，便于排障。

---

## 6. 检索与回答链路设计

### 6.1 查询预处理

查询进入系统后先做：

1. 清洗
2. 意图识别
3. Query rewrite
4. 模式决策

可复用现有组件：

- `IntentClassifyService`
- `ModeDecisionService`

意图类型建议：

- 概念解释
- 步骤说明
- 对比问题
- 调试定位
- 课程总结
- 资料定位

### 6.2 召回层

召回采用两路并行：

1. Dense recall
  - query embedding
  - Milvus topK child chunks
2. Sparse recall
  - Lucene BM25 topK child chunks

过滤条件：

- `course_space_id`
- `doc_type`
- 文档可见性
- 未来可扩展班级 / 学期 / 章节过滤

### 6.3 融合排序

使用 `FusionRankService` 聚合两路召回结果。

融合分可考虑：

- vector score
- bm25 score
- chapter/path 命中
- 文档类型权重
- 教师标注权重
- chunk 长度惩罚

### 6.4 Rerank

对 topN parent 候选做二次排序：

- 默认：heuristic rerank
- 可选：cross-encoder HTTP rerank

当前可复用：

- `TopRerankService`
- `CrossEncoderRerankClient`

### 6.5 Parent 聚合

因为检索命中的是 `child chunk`，最终用于作答的上下文应该回溯到 `parent chunk`。

规则：

- 同一 `parent_id` 的 child 只保留最高分或做加权聚合。
- 最终仅保留少量 parent 进入回答链。

### 6.6 Evidence Compression

不能把整个 parent chunk 原样全部塞给模型。

压缩策略：

- 保留最相关句子
- 保留章节名
- 保留页码或范围
- 保留来源文档名

目标：

- 降低 token 成本
- 提高引用准确率

### 6.7 回答生成

默认模式：

- 严格模式：只基于证据回答
- 如证据不足，明确说“资料中未找到充分依据”

兜底模式：

- LLM 不可用时，不返回空。
- 至少返回结构化证据摘要：
  - 命中文档
  - 命中章节
  - 关键摘录
  - 建议教师补充资料

### 6.8 引用格式

建议响应中包含：

- `answer`
- `citations`
- `coverage`
- `sources`
- `mode`

每条 citation 建议包含：

- `docName`
- `chapterPath`
- `pageRange`
- `score`
- `sourceType`

---

## 7. 教师端产品设计

### 7.1 知识库管理页

教师端页面建议展示：

- 课程空间基本信息
- 文档列表
- 文档处理状态
- chunk 数量
- 最后索引时间
- 失败原因
- 重试/重建按钮

### 7.2 证据管理页

教师可查看：

- 文档章节
- parent chunk
- child chunk
- 命中次数
- 标注入口

标注类型建议：

- `important`
- `exam_focus`
- `error_prone`
- `deprecated`

### 7.3 RAG 分析页

分析面板指标建议：

- 查询总量
- 首命中分均值
- 引用覆盖率
- 无答案率
- 失败文档数
- 热门问题
- 资料缺口问题

---

## 8. 后端模块拆分方案

### 8.1 文档处理模块

已有：

- `DocumentIngestService`
- `FileTextExtractor`
- `RagDocumentProcessor`

建议新增或增强：

- `PdfFallbackTextExtractor`
- `ChunkEmbeddingService`
- `MilvusIndexService`

职责拆分：

- `DocumentIngestService`
  - 原始文件存储
  - 文本抽取
- `RagDocumentProcessor`
  - 分块
  - 落库
  - 触发 embedding / indexing
- `ChunkEmbeddingService`
  - 批量 embedding
- `MilvusIndexService`
  - collection 初始化
  - upsert
  - deleteByDocument
  - rebuildByCourseSpace

### 8.2 检索模块

已有：

- `RagRetrievalService`
- `MilvusSearchService`
- `LuceneBm25Service`
- `FusionRankService`
- `TopRerankService`
- `EvidenceCompressService`

建议改造：

- `RagRetrievalService`
  - 统一编排 hybrid recall
  - 输出证据对象，而不是只依赖单一路向量检索
- `MilvusSearchService`
  - 从“只搜索”扩展成“完整向量存储服务”

### 8.3 问答模块

当前：

- `RagChatController` 是维护兜底控制器

目标：

- 恢复为真实问答控制器
- 支持 SSE 输出
- 支持 citation 输出
- 支持失败降级输出

建议控制器职责：

- 参数校验
- 权限校验
- 调用 retrieval pipeline
- 调用 answer pipeline
- 记录 `qa_log`

---

## 9. 分阶段实施计划

### Phase 0：文档抽取兜底

目标：

- 解决扫描 PDF 进不来知识库的问题

状态：

- 已完成

验收标准：

- 图片型 PDF 可通过 OCR 进入处理链
- OCR 不足时可继续走 Qwen-VL

### Phase 1：Milvus 入库主链

目标：

- 上传后自动把 `child chunk` 向量写入本地 Milvus

改造点：

- 新增 `MilvusIndexService`
- 增强 `MilvusSearchService`
- 在 `RagDocumentProcessor` 中接入 embedding + upsert

输出：

- 文档 READY 后，Milvus 中存在对应 child vectors

验收标准：

- 按 `course_space_id` 可查到对应向量
- 删除文档后能从 Milvus 清掉旧数据

### Phase 2：Hybrid Retrieval 打通

目标：

- 联通 `Milvus + BM25 + Fusion + Rerank`

改造点：

- 重写 `RagRetrievalService`
- 规范 evidence 对象
- 对 parent 聚合和引用元数据做统一封装

验收标准：

- 同一问题可同时利用关键词命中与语义命中
- 返回结果包含稳定 citation 元数据

### Phase 3：RagChatController 恢复为真实问答链

目标：

- 让教师端聊天真正可用

改造点：

- 恢复问答控制器
- 加入严格模式
- 加入模型失败降级
- 加入 `qa_log` 完整记录

验收标准：

- 页面可得到回答
- 回答携带 citations
- 模型失败时仍返回证据型答案

### Phase 4：教师端页面完善

目标：

- 让教师看到知识库的可用状态，不再黑箱

改造点：

- `KnowledgeBase.vue`
- `RagAnalytics.vue`

验收标准：

- 教师可看到文档状态、错误、索引状态、chunk 数、问答命中情况

### Phase 5：运营与质量闭环

目标：

- 让系统具备持续优化能力

改造点：

- 热点问题统计
- 低覆盖问题统计
- 教师反馈闭环

验收标准：

- 可识别哪些问题高频但没有足够证据

---

## 10. 详细执行顺序

建议严格按以下顺序执行：

1. 固化 OCR/VLM 基线
2. 实现 Milvus collection 管理与 upsert
3. 上传后打通向量化入库
4. 联通 hybrid retrieval
5. 恢复 `RagChatController`
6. 做教师端联调
7. 做日志和分析收口

这样做的原因：

- 不先打通 Milvus 入库，后面的检索全是空转。
- 不先恢复问答链，教师端看起来仍然是“不能用”。
- 不先做状态可视化，后续排错成本会很高。

---

## 11. 每阶段建议改动文件

### Phase 1

- `src/main/java/com/tap/backend/rag/MilvusSearchService.java`
- `src/main/java/com/tap/backend/rag/DashScopeEmbeddingClient.java`
- `src/main/java/com/tap/backend/service/RagDocumentProcessor.java`
- `src/main/java/com/tap/backend/domain/rag/CourseSpaceDocumentEntity.java`
- `src/main/resources/application.yml`

### Phase 2

- `src/main/java/com/tap/backend/rag/RagRetrievalService.java`
- `src/main/java/com/tap/backend/rag/FusionRankService.java`
- `src/main/java/com/tap/backend/rag/TopRerankService.java`
- `src/main/java/com/tap/backend/rag/EvidenceCompressService.java`

### Phase 3

- `src/main/java/com/tap/backend/api/rag/RagChatController.java`
- `src/main/java/com/tap/backend/repo/QaLogRepository.java`
- `src/main/java/com/tap/backend/domain/rag/QaLogEntity.java`

### Phase 4

- `AI_Ds-vue/src/views/teacher/KnowledgeBase.vue`
- `AI_Ds-vue/src/views/teacher/RagAnalytics.vue`

---

## 12. 风险与注意事项

### 12.1 技术风险

- Milvus Java SDK 与当前版本兼容性问题
- 向量维度与 embedding 模型不一致
- 文档重复上传导致旧向量残留
- Lucene 内存索引重启丢失
- 多模态识别页数过多导致成本和时延上升

### 12.2 业务风险

- 扫描材料质量过差，OCR/VLM 仍无法获得有效文本
- 教师误以为“上传成功”等于“知识库可问答”
- 没有引用时用户对答案信任度低

### 12.3 规避策略

- 所有关键阶段明确状态
- 所有异常写入错误信息
- 所有问答记录可追溯
- 所有回答默认带 citations

---

## 13. 本轮执行建议

根据当前代码状态，下一步应执行 `Phase 1`。

本轮具体任务：

1. 设计并实现 Milvus collection 生命周期接口
2. 将 `child chunk` embedding 后写入 Milvus
3. 增加按文档删除和按课程空间重建能力
4. 验证上传后 Milvus 中确实有数据

本轮暂不做的内容：

- 不先恢复复杂的聊天输出格式
- 不先做前端大改
- 不先追求 cross-encoder 最优效果

优先级判断：

- 先让“上传资料后可检索”成立
- 再让“检索后可回答”成立
- 最后再做“回答质量优化”

---

## 14. 验收口径

教师端 RAG 可认为“可用”，至少要满足：

1. 上传扫描 PDF 能自动进入知识库
2. 文档状态能区分 READY 与 FAILED
3. 上传后能在 Milvus 中查到 child vectors
4. 查询时能命中课程资料
5. 回答带引用
6. LLM 失败时系统仍返回证据型结果

只做到“有页面、有按钮、有接口”不算可用。

