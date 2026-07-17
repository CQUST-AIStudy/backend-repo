# Java 主后端 AI 统一到通义设计

## 目标

仅修改 `backend-repo/AI_Ds` Java 主后端，将其中仍然直接或间接使用 DeepSeek/OpenAI 配置的 AI 能力统一切换到阿里云百炼 DashScope。保持前端接口、业务请求和响应结构不变，不修改 RAG、错误分析、推荐、DeepL 翻译及其他独立服务。

## 统一配置

Java 主后端统一使用以下环境变量：

- `AI_PROVIDER=dashscope`
- `DASHSCOPE_API_KEY`：唯一的大模型凭据，不写入源码或示例文件
- `DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1`
- `DASHSCOPE_MODEL=qwen-plus`：默认文本模型
- `DASHSCOPE_VISION_MODEL=qwen-vl-max-latest`：视觉模型
- `ANIMATION_TTS_MODEL=qwen-tts`：语音模型

迁移完成后，Java 主后端业务代码不再依赖 `OPENAI_API_KEY`、`OPENAI_BASE_URL`、`OPENAI_MODEL`、`ARK_API_KEY` 或 `VOLCANO_API_KEY`。DeepL 和 arXiv 等非大模型配置保持原状。

## 迁移范围

### 直接调用迁移

1. `ChatController` 的 `/api/tap-chat` 与 `/api/tap-chat/stream` 改为读取 DashScope 配置。这覆盖教师端教学建议和教师通用对话。
2. `DeepSeekChatController` 的 `/api/chat` 保持路由和流式响应协议不变，内部改为读取 DashScope 配置并使用 `qwen-plus`。类名是否重命名不影响接口；为控制改动范围，可暂时保留类名并清理面向用户的 DeepSeek 文案。
3. `ApiController` 的实验 AI 点评改用 DashScope 配置，保留缓存、Markdown 输出和数据库写入逻辑；响应中的 `source` 改为 `dashscope`。
4. `LeetCodeAiEvaluationService` 改用 DashScope Key、Base URL 和文本模型，保留现有超时、JSON 解析与降级行为。
5. `AnimationAiClient` 的文本生成不再优先 OpenAI/DeepSeek，固定优先使用 DashScope；TTS 继续使用 `qwen-tts`。

### 通过统一 Provider 生效

将默认 Provider 切为 `dashscope` 后，以下使用 `AiProvider` 的能力自动使用通义，无需修改业务接口：

- 文件分类与摘要
- 文件夹智能整理
- 论文结构化总结
- 批改结果复核
- Rubric 草稿生成
- ZIP 文件智能整理
- 动画解释及代码提取

### 已使用通义

- PDF/VLM 识别继续使用 `qwen-vl-max-latest`
- 动画 TTS 继续使用 `qwen-tts`

### 明确排除

- `DouBaoAssistantController` 当前未启用 `@RestController`，不迁移、不重新启用
- `rag-service`
- `error-analysis-service`
- `recommendation-service`
- DeepL 翻译
- Tavily、arXiv 等非大模型第三方能力
- 前端代码和前端接口

## 组件与数据流

所有文本 AI 入口继续构造 OpenAI 兼容格式的 `/chat/completions` 请求，但目标地址、Bearer Key 和模型统一来自 DashScope 配置。调用链为：业务 Controller/Service → DashScope 配置解析 → DashScope OpenAI 兼容接口 → 保持现有解析器与业务响应。

视觉和 TTS 使用各自模型名，但共享同一个 `DASHSCOPE_API_KEY`。不同模型配置独立，避免将文本模型错误用于视觉或语音任务。

## 错误处理

- Key 缺失时明确报告 `DASHSCOPE_API_KEY` 未配置，不再提示 `OPENAI_API_KEY`。
- 保留各入口现有的超时、重试、流式输出和规则降级策略。
- 上游非 2xx 响应仅记录必要的状态和截断错误信息，不记录 Key。
- 删除实验点评中输出 Key 前缀的日志，避免凭据泄露。
- 教师端 `/api/tap-chat` 继续返回原有响应结构；模型失败时不能通过增加前端超时掩盖配置问题。

## 兼容性

- 所有 HTTP 路由不变。
- 前端请求字段、响应字段和流式协议不变。
- 数据库结构和已有 AI 点评缓存不变。
- `AiProvider` 接口保持不变，现有消费者不需要迁移。
- 本地真实 Key 仅更新在未跟踪的本地环境文件或进程环境中，仓库示例只保留空值。

## 测试与验收

采用测试先行：

1. 配置测试验证 `dashscope` Provider 使用 DashScope Base URL、Key 和 `qwen-plus`。
2. Controller/Service 测试验证教师聊天、学生聊天、实验点评和 LeetCode 评测不再读取 `tap.ai.openai.*`。
3. 动画客户端测试验证即使存在 OpenAI 配置，文本模型仍选择 DashScope；TTS 继续选择 DashScope。
4. 缺失 `DASHSCOPE_API_KEY` 时验证错误信息和降级行为。
5. 搜索生产源码与配置，确认启用路径中不再存在 DeepSeek URL、`deepseek-chat` 默认值及 OpenAI Key 依赖。
6. 运行相关 Maven 单元测试和主后端构建；若本地服务依赖齐全，再验证 `/api/tap-chat` 的通义实际请求。

## 安全要求

用户曾在聊天中展示过真实 DashScope Key。实现不得把该 Key 写入受版本控制文件、测试快照、日志或最终回复。上线前应在阿里云控制台轮换该 Key，并仅通过安全环境变量注入新 Key。
