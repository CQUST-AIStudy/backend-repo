# DeepL 可选配置设计

## 背景

当前主配置将 `TRANS_PROVIDER` 默认设置为 `deepl`。应用启动时，`DeepLConfig` 会创建必需的 `DeepLClient` Bean；如果未提供 `DEEPL_API_KEY`，Bean 创建直接抛出异常，导致整个后端无法启动。

DeepL 翻译功能已经停用，因此缺少 DeepL Key 不应阻止其他业务启动和运行。同时，若仍有调用方请求翻译，系统不应返回模拟翻译或静默产生错误数据，而应明确告知翻译功能未启用。

## 目标

- 未配置 `DEEPL_API_KEY` 时，后端能够正常启动。
- 默认情况下翻译功能处于禁用状态。
- 调用禁用状态下的翻译能力时，明确抛出“翻译功能未启用”错误。
- 显式配置有效 DeepL Key 后，保留现有真实翻译行为。
- 保留 `mock` provider，供开发或测试环境显式使用。

## 非目标

- 不删除翻译接口、翻译 Service 或已有数据库结构。
- 不调整 DeepL HTTP 请求、限流、分段、缓存或配额逻辑。
- 不把缺少 Key 的情况自动切换成模拟翻译。
- 不修改与翻译无关的配置和业务代码。

## 方案

### Provider 状态

`DeepLConfig` 支持以下三种 provider：

- `disabled`：返回禁用客户端，不要求 API Key。
- `mock`：返回现有 `MockDeepLClient`。
- `deepl`：API Key 有效时返回现有 `DeepLHttpClient`；API Key 缺失或为空时返回禁用客户端，不阻止应用启动。

无法识别的 provider 仍视为配置错误并在启动时抛出异常，避免拼写错误被静默忽略。

### 默认配置

将 `application.yml` 中 `TRANS_PROVIDER` 的默认值从 `deepl` 改为 `disabled`。这样未提供任何翻译配置时，系统状态与“功能已停用”的事实一致。

`application-dev.yml` 中显式配置的 `mock` 保持不变。

### 禁用客户端

新增 `DisabledDeepLClient`，继续实现现有 `DeepLClient` 接口：

- `name()` 返回 `disabled`，使缓存键和结果 provider 能反映真实状态。
- `translateText(...)` 抛出明确的 `IllegalStateException`，消息说明翻译功能未启用；只有实际调用翻译时才失败。

继续提供一个 `DeepLClient` Bean 可以保持 `DocumentTranslateService` 的依赖关系不变，避免把可选依赖处理扩散到 Service 和 Controller。

## 数据流与错误处理

应用启动时，配置层根据 provider 和 Key 选择客户端。`disabled` 或 `deepl + 空 Key` 都能成功完成 Bean 创建，因此不影响启动。

当翻译接口被调用时，现有 `DocumentTranslateService` 最终调用 `DeepLClient.translateText(...)`。禁用客户端在该边界抛出明确异常，不请求 DeepL，也不生成模拟结果或保存翻译段落。

未知 provider 仍然启动失败，因为它代表配置拼写或部署错误，而不是可接受的“功能未启用”状态。

## 测试策略

新增针对配置选择和禁用行为的单元测试，至少覆盖：

1. provider 为 `disabled` 时无需 Key，并创建禁用客户端。
2. provider 为 `deepl` 且 Key 为空时，不抛启动异常，并创建禁用客户端。
3. 禁用客户端仅在调用 `translateText(...)` 时抛出“翻译功能未启用”异常。
4. provider 为 `deepl` 且 Key 有效时，仍创建真实 DeepL 客户端。
5. provider 为未知值时，仍抛出不支持 provider 的配置错误。

实现遵循测试先行：先新增并运行失败测试，再做最小生产代码修改，最后运行相关测试和项目现有回归测试。

## 兼容性与风险

- 未设置 `TRANS_PROVIDER` 的环境会从“默认尝试 DeepL”变为“默认禁用翻译”，这是本次预期行为。
- 显式设置 `TRANS_PROVIDER=deepl` 但漏配 Key 时不再启动失败，问题会延迟到调用翻译时以明确错误暴露。
- 如果上层存在统一异常映射，HTTP 状态码和响应结构继续由现有映射决定；本次不扩展接口协议。
