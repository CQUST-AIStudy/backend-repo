# DashScope Backend AI Unification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Java 主后端所有启用中的文本 AI 调用统一到 DashScope，同时保持现有 HTTP 接口、流式协议和业务响应兼容。

**Architecture:** 继续使用 DashScope 的 OpenAI 兼容接口；共享 `AiProperties.Dashscope` 作为唯一配置来源。已有 `AiProvider` 消费者通过 `AI_PROVIDER=dashscope` 自动切换，直接调用模型的 Controller/Service 分别改为读取同一 DashScope 配置。

**Tech Stack:** Java 17、Spring Boot 3.4.4、JUnit 5、Mockito、OkHttp、Spring `RestClient`、Maven Wrapper

## Global Constraints

- 仅修改 `backend-repo/AI_Ds`，不修改前端或独立 Python/Go 服务。
- 不改变现有 HTTP 路由、请求字段、响应字段或 SSE 内容协议。
- 文本模型默认 `qwen-plus`，视觉模型保持 `qwen-vl-max-latest`，TTS 保持 `qwen-tts`。
- 真实 `DASHSCOPE_API_KEY` 不得写入受版本控制文件、测试、日志或提交信息。
- DeepL、arXiv、Tavily 等非大模型第三方能力保持原状。
- 每个生产代码行为变更必须先有失败测试。

---

### Task 1: 统一 DashScope Provider 默认配置

**Files:**
- Modify: `src/main/java/com/tap/backend/ai/AiConfig.java`
- Modify: `src/main/java/com/tap/backend/ai/AiProperties.java`
- Modify: `src/main/java/com/tap/backend/ai/OpenAiProvider.java`
- Modify: `src/main/java/com/tap/backend/infra/text/PdfFallbackTextExtractor.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.yml`
- Modify: `.env.example`
- Modify: `local.env.example.ps1`
- Modify: `docker-compose.yml`
- Test: `src/test/java/com/tap/backend/ai/AiConfigTest.java`

**Interfaces:**
- Consumes: `AiProperties(provider, openai, dashscope, arxiv)`
- Produces: `AiProvider` whose `name()` is `dashscope` and whose default model is `qwen-plus`

- [ ] **Step 1: 写失败测试，固定 DashScope Provider 的名称、模型和请求目标**

在 `AiConfigTest` 中用 JDK `HttpServer` 构造 `AiProperties("dashscope", null, new Dashscope(serverBaseUrl, "test-key", "qwen-plus", "qwen-vl-max-latest"), null)`，调用 `aiProvider.chat("hello", null)`，断言请求路径为 `/chat/completions`、Bearer 为 `test-key`、模型为 `qwen-plus`，并断言 `provider.name()` 为 `dashscope`。另加配置断言，证明 PDF/VLM 仍选择 `qwen-vl-max-latest`。

- [ ] **Step 2: 运行测试并确认因 Provider 名称仍为 `openai` 而失败**

Run: `./mvnw.cmd -Dtest=AiConfigTest test`

Expected: FAIL，`name()` 实际值为 `openai` 或测试类尚不存在。

- [ ] **Step 3: 最小实现 Provider 身份和默认配置**

给 `OpenAiProvider` 构造器增加 `providerName`，`name()` 返回该字段；`AiProperties.Dashscope` 增加 `visionModel` 字段；`AiConfig` 的 DashScope 分支传入 `dashscope`，文本默认模型改成 `qwen-plus`；`PdfFallbackTextExtractor` 只读取 `visionModel`，默认 `qwen-vl-max-latest`。配置文件默认值改为：

```yaml
tap:
  ai:
    provider: ${AI_PROVIDER:dashscope}
    dashscope:
      base-url: ${DASHSCOPE_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
      api-key: ${DASHSCOPE_API_KEY:}
      model: ${DASHSCOPE_MODEL:qwen-plus}
      vision-model: ${DASHSCOPE_VISION_MODEL:qwen-vl-max-latest}
```

示例环境文件只保留空 Key，并删除 OpenAI、Ark、Volcano 的启用示例。

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw.cmd -Dtest=AiConfigTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add src/main/java/com/tap/backend/ai/AiConfig.java src/main/java/com/tap/backend/ai/AiProperties.java src/main/java/com/tap/backend/ai/OpenAiProvider.java src/main/java/com/tap/backend/infra/text/PdfFallbackTextExtractor.java src/main/resources/application.yml src/main/resources/application-local.yml .env.example local.env.example.ps1 docker-compose.yml src/test/java/com/tap/backend/ai/AiConfigTest.java
git commit -m "refactor: make DashScope the backend AI provider"
```

### Task 2: 教师与学生聊天统一读取 DashScope 配置

**Files:**
- Modify: `src/main/java/com/tap/backend/api/chat/ChatController.java`
- Modify: `src/main/java/com/tap/backend/academic/controller/DeepSeekChatController.java`
- Test: `src/test/java/com/tap/backend/api/chat/ChatControllerDashscopeTest.java`
- Test: `src/test/java/com/tap/backend/academic/controller/StudentChatDashscopeTest.java`

**Interfaces:**
- Consumes: `AiProperties.Dashscope(baseUrl, apiKey, model)`
- Produces: unchanged `/api/tap-chat`, `/api/tap-chat/stream`, `/api/chat`

- [ ] **Step 1: 写教师聊天失败测试**

使用本地 HTTP 测试服务返回标准 `choices[0].message.content`，构造仅含 DashScope 配置的 `AiProperties` 和 `ChatController`。调用 `chat()` 后断言回复内容、请求模型 `qwen-plus`、请求路径 `/chat/completions`；同时不提供 OpenAI 配置。

- [ ] **Step 2: 运行教师聊天测试确认失败**

Run: `./mvnw.cmd -Dtest=ChatControllerDashscopeTest test`

Expected: FAIL，因为 `ChatController` 当前只读取 `props.openai()`。

- [ ] **Step 3: 修改教师聊天配置解析**

让 `ChatController` 从 `props.dashscope()` 读取 Base URL、Key、model，并将缺省值设置为 DashScope URL 与 `qwen-plus`。保留 90 秒非流式超时、5 分钟流式超时、响应结构和 arXiv 逻辑。

- [ ] **Step 4: 运行教师聊天测试确认通过**

Run: `./mvnw.cmd -Dtest=ChatControllerDashscopeTest test`

Expected: PASS。

- [ ] **Step 5: 写学生流式聊天失败测试**

用 `ReflectionTestUtils` 注入 DashScope 测试地址、Key 和模型，模拟 SSE：

```text
data: {"choices":[{"delta":{"content":"通义回复"}}]}
data: [DONE]
```

断言 `/api/chat` 输出 `通义回复`，请求模型为 `qwen-plus`，且缺 Key 文案提到 `DASHSCOPE_API_KEY`。

- [ ] **Step 6: 运行学生聊天测试确认失败**

Run: `./mvnw.cmd -Dtest=StudentChatDashscopeTest test`

Expected: FAIL，因为字段仍绑定 `tap.ai.openai.*` 和 DeepSeek 默认值。

- [ ] **Step 7: 修改学生聊天并清理敏感/过时文案**

把三个 `@Value` 改成 `tap.ai.dashscope.*`，默认 URL 和模型改为 DashScope/`qwen-plus`，日志标签改为 `[DashScope]`，缺 Key 提示改为 `DASHSCOPE_API_KEY`。保留类名以避免无关重命名。

- [ ] **Step 8: 运行两个聊天测试确认通过**

Run: `./mvnw.cmd -Dtest=ChatControllerDashscopeTest,StudentChatDashscopeTest test`

Expected: PASS。

- [ ] **Step 9: 提交**

```powershell
git add src/main/java/com/tap/backend/api/chat/ChatController.java src/main/java/com/tap/backend/academic/controller/DeepSeekChatController.java src/test/java/com/tap/backend/api/chat/ChatControllerDashscopeTest.java src/test/java/com/tap/backend/academic/controller/StudentChatDashscopeTest.java
git commit -m "fix: route teacher and student chat through DashScope"
```

### Task 3: 实验点评与 LeetCode 评测统一到 DashScope

**Files:**
- Modify: `src/main/java/com/tap/backend/academic/controller/ApiController.java`
- Modify: `src/main/java/com/tap/backend/academic/leetcode/execution/LeetCodeAiEvaluationService.java`
- Test: `src/test/java/com/tap/backend/academic/controller/ApiControllerDashscopeTest.java`
- Test: `src/test/java/com/tap/backend/academic/leetcode/execution/LeetCodeAiEvaluationServiceTest.java`

**Interfaces:**
- Consumes: `tap.ai.dashscope.base-url`, `tap.ai.dashscope.api-key`, `tap.ai.dashscope.model`
- Produces: unchanged experiment comment payload except `source="dashscope"`; unchanged `AiEvaluationResult`

- [ ] **Step 1: 写实验点评配置与日志安全失败测试**

通过反射验证 Controller 的 AI 字段绑定 DashScope 配置；对本地测试服务执行点评请求并断言模型为 `qwen-plus`、响应 `source` 为 `dashscope`。捕获日志/标准输出，断言不包含测试 Key 的任何前缀。

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw.cmd -Dtest=ApiControllerDashscopeTest test`

Expected: FAIL，因为当前字段、方法名、日志和响应 source 都是 DeepSeek。

- [ ] **Step 3: 最小迁移实验点评**

将字段改为 DashScope 配置，方法名改为 `callDashscopeForCodeReview`，默认模型 `qwen-plus`；删除打印 Key 前缀的日志；错误日志改用 DashScope；成功响应设置 `source="dashscope"`。缓存和数据库逻辑不变。

- [ ] **Step 4: 运行实验点评测试确认通过**

Run: `./mvnw.cmd -Dtest=ApiControllerDashscopeTest test`

Expected: PASS。

- [ ] **Step 5: 写 LeetCode 失败测试**

构造测试服务和 DashScope 参数，调用评测，断言 `/chat/completions` 请求使用 `qwen-plus`；再构造空 Key，断言保持现有不可用/降级结果而不是读取 `OPENAI_API_KEY`。

- [ ] **Step 6: 运行 LeetCode 测试确认失败**

Run: `./mvnw.cmd -Dtest=LeetCodeAiEvaluationServiceTest test`

Expected: FAIL，因为构造器仍注入 `tap.ai.openai.*`。

- [ ] **Step 7: 最小迁移 LeetCode 评测**

构造器属性改为 `tap.ai.dashscope.*`，环境变量后备改为 `DASHSCOPE_API_KEY`，默认 URL 和模型改为 DashScope 与 `qwen-plus`。保留请求体、JSON 提取、连接/读取/整体超时和降级逻辑。

- [ ] **Step 8: 运行两个测试确认通过**

Run: `./mvnw.cmd -Dtest=ApiControllerDashscopeTest,LeetCodeAiEvaluationServiceTest test`

Expected: PASS。

- [ ] **Step 9: 提交**

```powershell
git add src/main/java/com/tap/backend/academic/controller/ApiController.java src/main/java/com/tap/backend/academic/leetcode/execution/LeetCodeAiEvaluationService.java src/test/java/com/tap/backend/academic/controller/ApiControllerDashscopeTest.java src/test/java/com/tap/backend/academic/leetcode/execution/LeetCodeAiEvaluationServiceTest.java
git commit -m "fix: use DashScope for code review and evaluation"
```

### Task 4: 动画文本模型固定使用 DashScope

**Files:**
- Modify: `src/main/java/com/tap/backend/service/animation/AnimationAiClient.java`
- Test: `src/test/java/com/tap/backend/service/animation/AnimationAiClientTest.java`

**Interfaces:**
- Consumes: DashScope text model and existing `ANIMATION_TTS_MODEL`
- Produces: unchanged `chat(...)`, `tts(...)`, `isChatAvailable()`, `isTtsAvailable()`

- [ ] **Step 1: 写失败测试，证明 OpenAI 配置不能抢占 DashScope**

同时提供 OpenAI 和 DashScope 测试配置，调用 `chat()`，断言请求发往 DashScope 测试服务并使用 `qwen-plus`；调用 `tts()`，断言同样使用 DashScope 且模型为 `qwen-tts`。

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw.cmd -Dtest=AnimationAiClientTest test`

Expected: FAIL，因为当前文本生成优先选择 OpenAI 配置。

- [ ] **Step 3: 最小修改选择策略**

删除文本模型的 OpenAI 优先分支；聊天与 TTS 均只从 `AiProperties.Dashscope`/`DASHSCOPE_API_KEY` 解析凭据。聊天模型默认 `qwen-plus`，TTS 继续使用配置的 `qwen-tts`。缺配置错误只提示 `DASHSCOPE_API_KEY`。

- [ ] **Step 4: 运行动画测试确认通过**

Run: `./mvnw.cmd -Dtest=AnimationAiClientTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add src/main/java/com/tap/backend/service/animation/AnimationAiClient.java src/test/java/com/tap/backend/service/animation/AnimationAiClientTest.java
git commit -m "fix: keep animation AI on DashScope"
```

### Task 5: 清理启用路径并完成回归验证

**Files:**
- Modify if needed: `README.md`
- Modify if needed: `docs/deployment-guide.md`
- Test: all tests under `src/test/java`

**Interfaces:**
- Consumes: Tasks 1-4
- Produces: 可构建、可部署且仅需一个 DashScope 大模型 Key 的 Java 主后端

- [ ] **Step 1: 静态扫描启用路径**

Run:

```powershell
Get-ChildItem src/main/java,src/main/resources -Recurse -File | Select-String -Pattern 'api\.deepseek\.com|deepseek-chat|OPENAI_API_KEY|tap\.ai\.openai|ARK_API_KEY|VOLCANO_API_KEY'
```

Expected: 仅允许出现在明确禁用的豆包遗留代码、迁移说明或不启用的兼容定义中；所有实际业务调用路径无匹配。

- [ ] **Step 2: 检查真实密钥未进入 Git 差异**

Run: `git diff --check`，并人工检查 `git diff --cached`/`git diff` 不包含 `sk-` 凭据。

Expected: 无空白错误，无真实 Key。

- [ ] **Step 3: 运行相关测试集合**

Run:

```powershell
./mvnw.cmd -Dtest=AiConfigTest,ChatControllerDashscopeTest,StudentChatDashscopeTest,ApiControllerDashscopeTest,LeetCodeAiEvaluationServiceTest,AnimationAiClientTest test
```

Expected: PASS。

- [ ] **Step 4: 运行完整后端测试与构建**

Run: `./mvnw.cmd test`

Expected: BUILD SUCCESS。

Run: `./mvnw.cmd -DskipTests package`

Expected: BUILD SUCCESS，并生成 `target/teaching-assistant-backend-0.0.1-SNAPSHOT.jar`。

- [ ] **Step 5: 更新必要文档并提交**

只在现有文档仍指导用户配置 DeepSeek/OpenAI 时更新为 DashScope；不得写入真实 Key。

```powershell
git add README.md docs/deployment-guide.md
git commit -m "docs: document DashScope-only backend AI setup"
```

- [ ] **Step 6: 最终工作树核对**

Run: `git status --short`

Expected: 仅保留用户任务前已存在的未跟踪/未提交文件；本计划产生的文件均已提交。
