# Student AI Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为学生端现有调用补齐可生成、持久化并重新读取 AI 实验报告的后端接口。

**Architecture:** 新增职责单一的 `AiReportService`，负责按已认证学号读取实验与最新提交、调用可注入的 `AiReportGenerator`、保存报告并构造稳定结果；`ApiController` 只负责会话解析和 HTTP 响应。MyBatis DAO 增加按主键更新报告的方法，避免插入重复提交。

**Tech Stack:** Java 17、Spring Boot 3.4、MyBatis、OkHttp、Gson、JUnit 5、Mockito、Maven。

## Global Constraints

- 保持前端 `POST /api/experiments/{id}/report/generate` 与 `GET /api/experiments/{id}/report` 不变。
- 服务端身份一律来自 `StudentSessionResolver`，不得信任请求体中的学生身份。
- 报告保存到当前学生该实验最新 `Submission.report`，不新增数据库表。
- 不修改教师批阅、Word 导出或 AI 点评接口，不新增依赖。
- 不记录 API Key、Token 或完整敏感请求。

---

### Task 1: 报告持久化更新

**Files:**
- Modify: `src/main/java/com/tap/backend/academic/dao/SubmissionDao.java`
- Modify: `src/main/resources/mappers/SubmissionMapper.xml`
- Test: `src/test/java/com/tap/backend/academic/dao/SubmissionMapperContractTest.java`

**Interfaces:**
- Produces: `int updateReport(int submissionId, String report)`，按 `submission_id` 更新且返回影响行数。

- [ ] **Step 1: 编写失败的 Mapper 契约测试**

创建读取 `SubmissionMapper.xml` 的测试，断言存在 `id="updateReport"`、SQL 包含 `UPDATE submission`、`SET report = #{report}` 和 `WHERE submission_id = #{submissionId}`。

- [ ] **Step 2: 验证测试因映射缺失而失败**

Run: `mvn -Dtest=SubmissionMapperContractTest test`

Expected: FAIL，提示缺少 `updateReport`。

- [ ] **Step 3: 添加最小 DAO 与 XML 映射**

DAO 方法：

```java
int updateReport(@Param("submissionId") int submissionId, @Param("report") String report);
```

Mapper：

```xml
<update id="updateReport">
    UPDATE submission
    SET report = #{report}
    WHERE submission_id = #{submissionId}
</update>
```

- [ ] **Step 4: 运行契约测试并确认通过**

Run: `mvn -Dtest=SubmissionMapperContractTest test`

Expected: PASS。

- [ ] **Step 5: 提交持久化改动**

```bash
git add src/main/java/com/tap/backend/academic/dao/SubmissionDao.java src/main/resources/mappers/SubmissionMapper.xml src/test/java/com/tap/backend/academic/dao/SubmissionMapperContractTest.java
git commit -m "feat: persist generated experiment reports"
```

### Task 2: 可测试的 AI 报告生成服务

**Files:**
- Create: `src/main/java/com/tap/backend/academic/service/AiReportGenerator.java`
- Create: `src/main/java/com/tap/backend/academic/service/AiReportResult.java`
- Create: `src/main/java/com/tap/backend/academic/service/AiReportService.java`
- Create: `src/main/java/com/tap/backend/academic/service/impl/DeepSeekAiReportGenerator.java`
- Test: `src/test/java/com/tap/backend/academic/service/AiReportServiceTest.java`

**Interfaces:**
- Produces: `AiReportGenerator.generate(Experiment experiment, Submission submission, Map<String,Object> userData)`。
- Produces: `AiReportService.generate(String studentNo, int experimentId, Map<String,Object> userData)`。
- Produces: `AiReportService.get(String studentNo, int experimentId)`。
- Produces: `AiReportResult(boolean success, String message, String report, Map<String,Object> data)`。

- [ ] **Step 1: 编写服务失败测试**

覆盖四个独立行为：实验不存在返回失败；提交或代码为空返回失败；生成成功时调用 `updateReport` 并返回 report/data；读取时只使用传入的 `studentNo` 查询且返回已保存报告。

- [ ] **Step 2: 验证测试因服务类型不存在而失败**

Run: `mvn -Dtest=AiReportServiceTest test`

Expected: FAIL/编译失败，提示 `AiReportService` 等类型不存在。

- [ ] **Step 3: 实现结果类型和服务最小逻辑**

`generate` 使用 `experimentService.findExperimentById(experimentId)` 和 `submissionDao.findByUsernameAndExperimentId(studentNo, experimentId)`；生成器返回非空 Markdown 后调用 `submissionDao.updateReport(submission.getSubmission_id(), report)`，影响行数不是 1 时返回保存失败。`get` 不生成默认内容，空报告返回“尚未生成报告”。`data` 至少包含 `report` 以及请求体中允许回显的 `studentName`、`studentId`、`className`、`labName`、`labTime`。

- [ ] **Step 4: 实现 DeepSeek 生成器**

使用 `tap.ai.openai.api-key`、`tap.ai.openai.base-url`、`tap.ai.openai.model`，向 `/chat/completions` 发送非流式请求。提示词强制包含“实验目的、实验环境、实验内容、实验总结”章节，代码最多截取 6000 字符；配置缺失、非 2xx、空 choices 均抛出明确异常，日志不输出密钥或完整代码。

- [ ] **Step 5: 运行服务测试并确认通过**

Run: `mvn -Dtest=AiReportServiceTest test`

Expected: PASS，0 failures。

- [ ] **Step 6: 提交服务实现**

```bash
git add src/main/java/com/tap/backend/academic/service src/test/java/com/tap/backend/academic/service/AiReportServiceTest.java
git commit -m "feat: generate student AI experiment reports"
```

### Task 3: HTTP 接口契约

**Files:**
- Modify: `src/main/java/com/tap/backend/academic/controller/ApiController.java`
- Test: `src/test/java/com/tap/backend/academic/controller/AiReportControllerTest.java`

**Interfaces:**
- Consumes: `StudentSessionResolver.requireStudentId(HttpServletRequest)`。
- Consumes: `AiReportService.generate(...)` 与 `AiReportService.get(...)`。
- Produces: `POST /api/experiments/{id}/report/generate`、`GET /api/experiments/{id}/report`。

- [ ] **Step 1: 编写失败的 MockMvc 接口测试**

用 `@WebMvcTest(ApiController.class)`/必要 mock 验证：POST 将解析出的 `studentNo` 和请求体传给服务并返回 `success/report/data`；GET 将解析出的 `studentNo` 传给服务；请求体伪造的 `studentId` 不改变查询身份；服务失败保留 `success:false` 与 message。

- [ ] **Step 2: 验证路由缺失导致测试失败**

Run: `mvn -Dtest=AiReportControllerTest test`

Expected: FAIL，POST/GET 返回 404。

- [ ] **Step 3: 添加最小控制器路由**

注入 `AiReportService`。两个方法先调用 `studentSessionResolver.requireStudentId(request)`，随后调用服务，并将 `AiReportResult` 映射为：

```java
Map.of(
    "success", result.success(),
    "message", result.message(),
    "report", result.report(),
    "data", result.data()
)
```

空 report/data 需使用允许 null 的 `HashMap`，避免 `Map.of` 抛出异常。业务失败保持 HTTP 200 以匹配前端现有解析；认证异常沿用 resolver 的 401/403。

- [ ] **Step 4: 运行控制器测试并确认通过**

Run: `mvn -Dtest=AiReportControllerTest test`

Expected: PASS，0 failures。

- [ ] **Step 5: 提交接口实现**

```bash
git add src/main/java/com/tap/backend/academic/controller/ApiController.java src/test/java/com/tap/backend/academic/controller/AiReportControllerTest.java
git commit -m "feat: expose student AI report endpoints"
```

### Task 4: 联合验证

**Files:**
- Verify only; no planned production changes.

**Interfaces:**
- Verifies: 前端调用路径与后端路由完全一致，所有后端回归测试通过。

- [ ] **Step 1: 运行报告相关测试**

Run: `mvn -Dtest=SubmissionMapperContractTest,AiReportServiceTest,AiReportControllerTest test`

Expected: PASS，0 failures/errors。

- [ ] **Step 2: 运行后端完整测试**

Run: `mvn test`

Expected: BUILD SUCCESS，0 failures/errors。

- [ ] **Step 3: 运行后端打包验证**

Run: `mvn -DskipTests package`

Expected: BUILD SUCCESS。

- [ ] **Step 4: 核对前后端路径**

Run: `Select-String -Path src/main/java/com/tap/backend/academic/controller/ApiController.java,../../frontend-repo/AI_Ds-vue/src/api/index.js -Pattern 'report/generate|experiments/.*/report'`

Expected: 前端与后端均包含同一 POST 和 GET 路径。

- [ ] **Step 5: 检查最终差异并提交遗留测试调整（如有）**

Run: `git status --short && git diff --check`

Expected: 无意外文件、无空白错误。
