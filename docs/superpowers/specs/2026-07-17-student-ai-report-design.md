# 学生 AI 实验报告接口设计

## 背景

学生端 `/student/ai-report` 已调用以下接口：

- `POST /api/experiments/{id}/report/generate`
- `GET /api/experiments/{id}/report`

Java 后端尚未提供这两个接口，因此前端生成和读取报告都会失败。本次保持前端契约不变，在后端补齐完整实验报告能力。

## 范围

本次实现仅覆盖当前登录学生针对单个实验生成和读取 Markdown 报告：

- 复用现有登录身份、实验、提交记录和 DeepSeek 配置。
- 报告持久化到该学生对应实验的最新 `Submission.report`。
- 不修改教师批阅报告、Word 导出和 AI 代码点评接口。
- 不引入新表或新依赖。

## API 契约

### 生成报告

`POST /api/experiments/{id}/report/generate`

请求体允许包含前端现有用户资料字段：`studentName`、`studentId`、`className`、`experimentContent`。服务端以已认证身份和数据库数据为准，不信任请求体提供的身份来访问其他学生的数据。

成功响应：

```json
{
  "success": true,
  "message": "AI报告生成成功",
  "report": "# 实验报告...",
  "data": {
    "report": "# 实验报告...",
    "studentName": "...",
    "studentId": "...",
    "className": "...",
    "labName": "...",
    "labTime": "..."
  }
}
```

### 查询报告

`GET /api/experiments/{id}/report`

返回当前登录学生该实验最新提交中的报告，响应沿用相同的 `success`、`message`、`report`、`data` 结构，以兼容前端 `normalizeReportResponse`。

## 数据流

1. 从 Spring Security 获取当前用户名并解析学生身份。
2. 校验实验存在，并查找该学生在该实验下的最新提交。
3. 生成时确认存在可用于报告的提交代码；读取时确认已保存报告。
4. 组织实验名称、实验描述、学生信息和代码，调用现有 DeepSeek Chat Completions 配置。
5. 校验模型响应非空，保存至最新提交的 `report` 字段。
6. 返回前端既有响应结构。

## 报告格式

提示词要求模型输出 Markdown，并至少包含前端解析所需章节：

- 实验目的
- 实验环境
- 实验内容或实验任务
- 实验总结或心得体会

学生源代码由前端现有报告预览逻辑单独展示；提示词可分析代码，但不得伪造运行结果或成绩。

## 错误处理

业务失败使用项目现有响应风格返回 `success: false` 和可读 `message`，覆盖：

- 未认证或无法解析学生身份。
- 实验不存在。
- 尚无提交或提交代码为空。
- 尚未生成报告。
- DeepSeek 配置缺失、上游调用失败或模型返回空内容。
- 报告保存失败。

日志不得输出 API Key、Token 或完整敏感请求内容。

## 代码结构

采用最小改动方案：

- 在现有 `ApiController` 增加两个路由，复用其中的身份解析、实验详情和 DeepSeek HTTP 客户端模式。
- 若现有 `SubmissionService` 缺少按当前学生和实验保存最新报告的明确方法，则增加一个职责单一的服务方法，并由控制器调用。
- 不重构与本功能无关的旧接口。

## 测试与验收

按 TDD 实施：

1. 先增加接口回归测试，验证路由存在、成功响应契约及当前用户数据隔离，并确认测试因接口缺失而失败。
2. 增加未提交代码、无既有报告和 AI 失败等错误路径测试。
3. 编写最小实现使测试通过。
4. 运行相关测试、后端完整测试和 Maven 编译/打包验证。

验收标准：前端现有 `generateExperimentReport` 与 `getExperimentReport` 无需修改即可消费后端响应；生成后的报告能够再次通过查询接口获取。
