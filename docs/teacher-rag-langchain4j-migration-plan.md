# 教师端 RAG 迁移到 LangChain4j 的执行方案

## 1. 文档目标

本文档用于指导 `AI_Ds` 教师端 RAG 从当前自研编排方案，迁移到 `LangChain4j` 驱动的可维护实现。

本文档关注的不是“重新做一个知识库”，而是：

- 保留现有业务模型、权限模型、文档处理链路和索引资产
- 用 `LangChain4j` 替换当前脆弱、难维护的模型调用与问答编排层
- 分阶段完成迁移，避免推倒重来导致长时间不可用
- 为后续修复教师端 RAG 提供一份可以直接执行的技术方案

---

## 2. 当前实现概况

当前教师端 RAG 不是基于 `LangChain4j`、`LangChain` 或 `Spring AI` 实现，而是 `Spring Boot + Milvus + Lucene + 自研编排`。

### 2.1 当前关键模块

- 课程空间与权限
  - `src/main/java/com/tap/backend/service/CourseSpaceService.java`
  - `src/main/java/com/tap/backend/api/rag/CourseSpaceController.java`
  - `src/main/java/com/tap/backend/api/rag/RagChatController.java`
- 文档入库与文本抽取
  - `src/main/java/com/tap/backend/service/DocumentIngestService.java`
  - `src/main/java/com/tap/backend/infra/text/FileTextExtractor.java`
  - `src/main/java/com/tap/backend/infra/text/PdfFallbackTextExtractor.java`
- RAG 文档处理
  - `src/main/java/com/tap/backend/service/RagDocumentProcessor.java`
- 向量检索与 BM25
  - `src/main/java/com/tap/backend/rag/MilvusSearchService.java`
  - `src/main/java/com/tap/backend/rag/LuceneBm25Service.java`
- 查询编排
  - `src/main/java/com/tap/backend/rag/RagOrchestratorService.java`
  - `src/main/java/com/tap/backend/rag/RagRetrievalService.java`
  - `src/main/java/com/tap/backend/rag/FusionRankService.java`
  - `src/main/java/com/tap/backend/rag/TopRerankService.java`
  - `src/main/java/com/tap/backend/rag/EvidenceCompressService.java`
- 其他业务规则
  - `src/main/java/com/tap/backend/rag/ModeDecisionService.java`
  - `src/main/java/com/tap/backend/rag/CoverageCalculator.java`
  - `src/main/java/com/tap/backend/rag/DocChunkAnnotationService.java`
  - `src/main/java/com/tap/backend/rag/IntentClassifyService.java`
  - `src/main/java/com/tap/backend/rag/WebFallbackService.java`

### 2.2 当前主要问题

当前问题的本质不是“没有 RAG 框架”，而是链路闭环和可维护性较差：

1. 文档处理、切块、向量化、BM25、问答编排耦合过深。
2. 模型调用使用直接 HTTP 方式，错误处理、切换、流式输出和后续维护成本高。
3. 问答编排逻辑集中在 `RagOrchestratorService` 中，职责过重，不利于调试和迭代。
4. 教师端业务规则已经与当前数据模型和检索链深度绑定，无法通过简单替换框架直接解决稳定性问题。
5. 当前实现已经有 Hybrid RAG 能力，若全量重写，风险高于收益。

---

## 3. 为什么选择 LangChain4j

将教师端 RAG 迁移到 `LangChain4j` 的主要目的不是“追求新框架”，而是为了解决以下问题：

- 统一 `ChatModel`、`StreamingChatModel`、`EmbeddingModel` 抽象
- 降低直接维护 HTTP 请求、JSON 拼装、流式响应解析的成本
- 让 Prompt、Retriever、RAG Pipeline 的边界更清晰
- 为后续替换模型供应商、补充重试策略和观测能力提供更稳定的结构

### 3.1 迁移后能获得的收益

- 模型适配层标准化
- 更容易拆分问答编排组件
- 更容易进行单元测试和 A/B 比较
- 后续演进到更成熟的高级 RAG 结构时阻力更小

### 3.2 迁移后仍然不能自动解决的问题

`LangChain4j` 不会自动修复以下问题，这些仍然要由本项目业务层负责：

- PDF / OCR 文本抽取质量
- 课程空间、班级作用域、教师权限
- 文档处理状态机与重试机制
- Milvus / Lucene / MySQL 的一致性
- 引用格式、反馈统计、分析面板

结论：迁移方案必须是“保留业务与索引层，替换模型和编排层”，而不是重写整条链。

---

## 4. 当前系统与 LangChain4j 的边界划分

### 4.1 必须保留的模块

以下模块与教师端业务绑定紧密，迁移期间必须保留：

- `CourseSpaceService`
- `CourseSpaceController`
- `RagChatController`
- `DocumentIngestService`
- `FileTextExtractor`
- `PdfFallbackTextExtractor`
- `RagDocumentProcessor`
- `MilvusSearchService`
- `LuceneBm25Service`
- `CourseSpaceEntity`
- `CourseSpaceDocumentEntity`
- `DocChunkEntity`
- `QaLogEntity`
- `DocChunkAnnotationEntity`

### 4.2 优先替换的模块

以下模块应逐步迁移到 `LangChain4j` 结构下：

- `DashScopeEmbeddingClient`
- `RagOrchestratorService` 中的 Prompt 拼接与 LLM 调用部分
- `RagOrchestratorService` 中的流式输出拼装部分
- 部分基于大模型的意图识别与辅助生成逻辑

### 4.3 暂不迁移的模块

在第一阶段不建议迁移以下内容：

- 文档上传与抽取
- 文档切块逻辑
- Milvus Collection 生命周期管理
- Lucene 索引重建逻辑
- `qa_log`、反馈、分析统计逻辑

---

## 5. 目标架构

### 5.1 迁移后的总体结构

```text
教师上传资料
  -> DocumentIngestService
  -> FileTextExtractor / PdfFallbackTextExtractor
  -> RagDocumentProcessor
  -> LuceneBm25Service + MilvusSearchService

教师提问
  -> RagChatController
  -> CourseSpaceService 解析 teacher/class scope
  -> TeacherRagFacade
      -> TeacherHybridRetriever
          -> MilvusSearchService
          -> LuceneBm25Service
          -> FusionRankService
          -> TopRerankService
      -> TeacherRagPromptService
      -> LangChain4j StreamingChatModel
      -> TeacherRagCitationService
  -> SSE 输出
  -> QaLogEntity 落库
```

### 5.2 推荐的包结构

新增包建议如下：

- `com.tap.backend.rag.lc4j.config`
- `com.tap.backend.rag.lc4j.model`
- `com.tap.backend.rag.lc4j.retriever`
- `com.tap.backend.rag.lc4j.prompt`
- `com.tap.backend.rag.lc4j.service`
- `com.tap.backend.rag.lc4j.dto`

建议新增类：

- `LangChain4jRagConfig`
- `TeacherRagFacade`
- `TeacherHybridRetriever`
- `TeacherRagPromptService`
- `TeacherRagAnswerService`
- `TeacherRagCitationService`
- `TeacherRagStreamingAdapter`
- `TeacherRagExecutionContext`
- `TeacherRagExecutionResult`

---

## 6. 版本与依赖策略

### 6.1 当前项目约束

当前项目使用：

- Java 17
- Spring Boot 3.4.4

因此迁移时要特别注意 `LangChain4j` 的 `Spring Boot starter` 兼容性。

### 6.2 依赖策略

第一阶段不建议直接依赖 `LangChain4j Spring Boot starter` 的自动装配。

更稳妥的策略是：

1. 先引入 `LangChain4j` 核心依赖
2. 手工编写 `@Configuration` 和 `@Bean`
3. 在本项目现有 Spring Boot 环境下验证流式输出、模型调用、Embedding 调用
4. 如果后续项目升级到兼容版本，再考虑使用官方 starter

### 6.3 建议的依赖分层

第一阶段建议只引入：

- `langchain4j`
- 对应模型供应商的 LangChain4j integration
- 如有必要再引入 `langchain4j-milvus`

不建议第一阶段同时引入太多组件，否则定位问题会更困难。

---

## 7. 迁移原则

### 7.1 迁移原则一：先替换模型调用，再替换编排

先让系统从“手写 HTTP 调模型”转成“LangChain4j 管理模型接口”，再处理更复杂的 RAG 编排。

### 7.2 迁移原则二：保留现有 Hybrid Retrieval

当前教师端的核心价值不是单纯向量检索，而是：

- Milvus 向量召回
- Lucene BM25
- Fusion 排序
- Top rerank
- 注释加权
- coverage 计算
- mode 决策

这些逻辑不应被 LangChain4j 默认的简单 RAG 替代。

### 7.3 迁移原则三：继续保留现有数据模型

迁移期间不得破坏以下数据结构的语义：

- `course_space`
- `course_space_document`
- `doc_chunk`
- `qa_log`
- `doc_chunk_annotation`

### 7.4 迁移原则四：接口兼容优先

前端教师端已经依赖当前 `/api/rag/chat` 的 SSE 输出和引用协议。迁移期间，接口协议必须兼容，不能要求前端同步大改。

---

## 8. 分阶段执行方案

## 8.1 阶段 0：POC 与兼容性验证

目标：确认 `LangChain4j` 可以在当前工程里稳定接管模型调用。

执行内容：

1. 新建独立分支进行验证。
2. 引入最小依赖集合。
3. 写一个最小 `ChatModel` Bean。
4. 写一个最小 `StreamingChatModel` Bean。
5. 写一个最小 `EmbeddingModel` Bean。
6. 用一个独立测试类验证：
   - 普通问答
   - 流式问答
   - embedding 结果能返回
7. 验证与当前模型供应商的兼容性。

阶段输出：

- 可用的 LangChain4j 最小配置
- 一个最小测试入口
- 结论：是否可以进入阶段 1

验收标准：

- 能调用聊天模型
- 能流式返回
- 能拿到 embedding
- 不影响现有系统启动

---

## 8.2 阶段 1：替换模型调用层

目标：保留现有检索和业务逻辑，仅把模型访问改成 LangChain4j。

执行内容：

1. 新增 `LangChain4jRagConfig`
2. 新增 `TeacherRagAnswerService`
3. 新增 `TeacherRagStreamingAdapter`
4. 将 `RagOrchestratorService` 中直接 HTTP 调 LLM 的代码替换为 `StreamingChatModel`
5. 将 `DashScopeEmbeddingClient` 的职责逐步迁移到 `EmbeddingModel` 封装

本阶段保持不变的内容：

- `RagRetrievalService`
- `MilvusSearchService`
- `LuceneBm25Service`
- `FusionRankService`
- `TopRerankService`
- `CoverageCalculator`
- `ModeDecisionService`

风险控制：

- 所有新逻辑放在新类中
- 保留旧实现一段时间，通过开关或新旧服务并存完成切换

验收标准：

- `/api/rag/chat` 行为基本不变
- SSE 还能工作
- `qa_log` 还能写入
- 引用信息结构不变

---

## 8.3 阶段 2：封装自定义 Retriever

目标：把当前 Hybrid Retrieval 封装为 `LangChain4j` 可用的 Retriever 层，但内部仍用现有服务。

执行内容：

1. 新增 `TeacherHybridRetriever`
2. 其内部组合调用：
   - `MilvusSearchService`
   - `LuceneBm25Service`
   - `FusionRankService`
   - `TopRerankService`
3. 输出统一的检索结果对象：
   - evidence 文本
   - parent chunk 信息
   - citation 元信息
   - retrieved chunk ids
4. 从 `RagOrchestratorService` 中抽出检索部分逻辑

注意事项：

- 本阶段不强行使用 LangChain4j 默认的简单向量检索
- 不重建 Milvus collection
- 不改 Lucene 索引结构

验收标准：

- 同一 query 的 topN 检索结果与旧实现高度一致
- 引用构造数据与旧实现一致
- 教师端问答质量不低于当前版本

---

## 8.4 阶段 3：重构问答编排层

目标：用 LangChain4j 驱动新的问答编排，同时保留业务规则。

执行内容：

1. 新增 `TeacherRagFacade`
2. 新增 `TeacherRagPromptService`
3. 新增 `TeacherRagExecutionContext`
4. 新增 `TeacherRagExecutionResult`
5. 逐步缩小 `RagOrchestratorService` 的职责，最终由新 facade 替代
6. 保留以下业务决策组件并继续调用：
   - `IntentClassifyService`
   - `ModeDecisionService`
   - `CoverageCalculator`
   - `DocChunkAnnotationService`
   - `CourseSpaceService.RagChatScope`

建议的新执行顺序：

1. 解析 scope
2. 识别 intent
3. Hybrid retrieval
4. coverage 计算
5. mode 决策
6. prompt 组装
7. LangChain4j 流式生成
8. 引用写回
9. `qa_log` 落库

验收标准：

- 问答主流程不再依赖旧的直接 HTTP LLM 调用代码
- 新旧实现可以灰度切换
- 低覆盖率处理与严格模式处理保持一致

---

## 8.5 阶段 4：统一引用与输出协议

目标：保证前端和日志侧无感知切换。

执行内容：

1. 新增 `TeacherRagCitationService`
2. 将 citation 组装与 `<!--CITATIONS:...-->` 输出协议独立出来
3. 保持 `/api/rag/chat` 的 SSE 输出兼容
4. 保持前端 `tap.js` 和教师端知识库页可继续工作

必须保持兼容的点：

- 文本流输出格式
- citation JSON 结构
- `qa_log` 的字段写入逻辑

验收标准：

- 前端无需改动或仅做极小兼容改动
- 引用标签、引用弹出、反馈功能不退化

---

## 8.6 阶段 5：评估是否迁移 Embedding Store 适配层

目标：评估是否将当前 `MilvusSearchService` 抽象进一步迁移到 `LangChain4j`。

建议：

- 第一轮不迁移
- 等阶段 1 到阶段 4 全部稳定后再做

原因：

- 当前项目已经有自定义 collection 管理、upsert、delete、rebuild 逻辑
- 这部分和业务恢复、重建流程绑定较深
- 贸然改为框架默认存储抽象，容易破坏现有控制能力

验收标准：

- 只有在自定义 Milvus 服务成为维护负担时，才考虑进一步迁移

---

## 9. 文件级实施清单

### 9.1 预计新增文件

- `src/main/java/com/tap/backend/rag/lc4j/config/LangChain4jRagConfig.java`
- `src/main/java/com/tap/backend/rag/lc4j/service/TeacherRagFacade.java`
- `src/main/java/com/tap/backend/rag/lc4j/service/TeacherRagAnswerService.java`
- `src/main/java/com/tap/backend/rag/lc4j/service/TeacherRagCitationService.java`
- `src/main/java/com/tap/backend/rag/lc4j/service/TeacherRagStreamingAdapter.java`
- `src/main/java/com/tap/backend/rag/lc4j/retriever/TeacherHybridRetriever.java`
- `src/main/java/com/tap/backend/rag/lc4j/prompt/TeacherRagPromptService.java`
- `src/main/java/com/tap/backend/rag/lc4j/dto/TeacherRagExecutionContext.java`
- `src/main/java/com/tap/backend/rag/lc4j/dto/TeacherRagExecutionResult.java`

### 9.2 预计修改文件

- `pom.xml`
- `src/main/java/com/tap/backend/api/rag/RagChatController.java`
- `src/main/java/com/tap/backend/rag/RagOrchestratorService.java`
- `src/main/java/com/tap/backend/rag/DashScopeEmbeddingClient.java`
- `src/main/resources/application.yml`
- 如有必要增加测试文件到 `src/test/java/com/tap/backend/rag/`

### 9.3 第一批不应改动的文件

- `DocumentIngestService.java`
- `RagDocumentProcessor.java`
- `MilvusSearchService.java`
- `LuceneBm25Service.java`
- 数据库 migration 文件

---

## 10. 风险与回退策略

### 10.1 风险点

主要风险包括：

1. `LangChain4j` 与当前 `Spring Boot 3.4.4` 的集成细节不稳定
2. 当前模型供应商在 `LangChain4j` 下的兼容性可能不如手写 HTTP
3. SSE 流式输出适配可能出现中断、缓冲或粘包问题
4. 新旧 citation 协议不一致导致前端渲染异常
5. 新旧检索排序行为变化导致问答质量波动

### 10.2 回退原则

每个阶段都必须允许回退：

- 保留旧服务实现
- 通过配置开关控制使用旧版或新版
- 不在同一个提交里同时做大范围结构变更和行为切换

### 10.3 灰度策略

建议使用配置开关，例如：

- `tap.rag.langchain4j.enabled=false`
- `tap.rag.langchain4j.use-streaming=true`
- `tap.rag.langchain4j.use-embedding-model=false`

通过配置控制：

- 是否启用 LangChain4j
- 是否启用新流式问答
- 是否启用新 embedding 层

---

## 11. 验收标准

迁移完成后至少应满足以下标准：

1. 教师端 `/api/rag/chat` 可稳定回答问题
2. SSE 流式输出可用
3. 引用信息完整且前端可正常解析
4. `qa_log` 记录完整
5. 在相同 `courseSpaceId` 与 query 下，回答质量不低于旧版
6. 严格模式、低覆盖率、Web fallback 行为不退化
7. 文档上传、重处理、BM25 重建链路不受影响

---

## 12. 推荐执行顺序

推荐按以下顺序执行：

1. 做最小 POC，验证 `LangChain4j` 与当前项目兼容
2. 接入 `ChatModel` 和 `StreamingChatModel`
3. 替换问答阶段的模型调用
4. 抽出自定义 `TeacherHybridRetriever`
5. 抽出 `TeacherRagPromptService`
6. 新建 `TeacherRagFacade`，逐步替换旧 orchestrator
7. 保持引用和日志协议兼容
8. 完成 A/B 验证
9. 再决定是否迁移 embedding store 抽象

---

## 13. 不建议的做法

以下做法不建议采用：

- 直接推倒重写整个教师端 RAG
- 一开始就迁移文档处理、切块、索引、问答编排全部层
- 强行使用 LangChain4j 默认 Easy RAG 取代现有 Hybrid Retrieval
- 不做灰度与回退就直接切换生产路径
- 在未验证 Spring Boot 兼容性的情况下大量接入 starter

---

## 14. 结论

本次迁移的正确目标不是“把当前系统完全框架化”，而是：

- 保留已经和教师端业务深度绑定的业务层、索引层、数据层
- 用 `LangChain4j` 取代当前最难维护的模型调用层与问答编排层
- 按分阶段方案逐步迁移，保证任何阶段都可回退

按照本方案执行，可以在不重做教师端知识库系统的前提下，把当前自研 RAG 逐步演进到更清晰、可维护、可继续修复和扩展的结构。
