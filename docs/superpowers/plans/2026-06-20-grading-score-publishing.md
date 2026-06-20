# Grading Score Publishing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现批改提交的学生匹配确认、单份/批量发布、撤回及学生端已发布结果查看。

**Architecture:** 以 `grading_submission` 作为发布事实来源，新增匹配与发布字段；教师 API 统一执行任务所有权校验，学生 API 只按登录身份读取已发布数据。保留旧版成绩同步以兼容现有页面，但学生批注结果由新发布状态控制。

**Tech Stack:** Spring Boot 3、JPA/JdbcTemplate、Flyway、JUnit 5、Vue 3、Axios、Vite。

---

### Task 1: 发布状态数据库模型

**Files:**
- Create: `src/main/resources/db/migration/V46__grading_submission_publish_state.sql`
- Create: `src/main/java/com/tap/backend/domain/grading/SubmissionMatchStatus.java`
- Modify: `src/main/java/com/tap/backend/domain/grading/GradingSubmissionEntity.java`

- [ ] 编写实体字段映射测试并确认缺少字段时失败。
- [ ] 增加 `match_status/published_at/published_by` 迁移、索引和外键。
- [ ] 增加实体枚举与访问器，运行聚焦测试。

### Task 2: 自动与人工学生匹配

**Files:**
- Modify: `src/main/java/com/tap/backend/service/GradingUnifiedLinkService.java`
- Modify: `src/main/java/com/tap/backend/service/GradingTaskService.java`
- Modify: `src/main/java/com/tap/backend/service/GradingSubmissionService.java`
- Modify: `src/main/java/com/tap/backend/api/grading/GradingSubmissionController.java`
- Modify: `src/main/java/com/tap/backend/api/grading/GradingTaskController.java`
- Test: `src/test/java/com/tap/backend/service/GradingUnifiedLinkServiceTest.java`

- [ ] 先写学号唯一、姓名唯一、重名和无法识别的失败测试。
- [ ] 返回明确的匹配决策和候选学生；上传时持久化匹配状态。
- [ ] 增加花名册查询和人工确认接口，确认后写入规范学生信息。
- [ ] 运行匹配服务测试。

### Task 3: 发布、批量发布与撤回

**Files:**
- Create: `src/main/java/com/tap/backend/service/GradingPublicationService.java`
- Create: `src/main/java/com/tap/backend/api/grading/StudentGradingResultController.java`
- Modify: `src/main/java/com/tap/backend/api/grading/GradingSubmissionController.java`
- Modify: `src/main/java/com/tap/backend/api/grading/GradingTaskController.java`
- Test: `src/test/java/com/tap/backend/service/GradingPublicationServiceTest.java`

- [ ] 先写未确认拒绝、越权拒绝、幂等发布、批量发布和撤回的失败测试。
- [ ] 实现发布前置校验、报告准备、旧表同步及发布字段写入。
- [ ] 实现单份/批量发布和撤回接口。
- [ ] 实现学生按登录身份查询已发布结果与下载报告。
- [ ] 运行发布服务与控制器相关测试。

### Task 4: 教师端发布交互

**Files:**
- Modify: `frontend-repo/src/api/tap/grading.js`
- Modify: `frontend-repo/src/views/teacher/SubmissionReview.vue`
- Modify: `frontend-repo/src/views/teacher/GradingDetail.vue`

- [ ] 增加匹配、发布、撤回和批量 API 封装。
- [ ] 单份页面增加匹配状态、人工选择、发布与撤回按钮。
- [ ] 任务详情增加匹配/发布列和批量按钮状态。
- [ ] 运行 `npm run build`。

### Task 5: 学生端已发布结果

**Files:**
- Modify: `frontend-repo/src/api/index.js`
- Modify: `frontend-repo/src/views/student/ExperimentDetail.vue`

- [ ] 增加当前学生发布结果查询和报告下载 API。
- [ ] 已发布时展示分数、总评和下载入口；未发布时不渲染相关内容。
- [ ] 运行 `npm run build`。

### Task 6: 集成验证与部署

**Files:**
- Build: `backend-repo/target/teaching-assistant-backend-0.0.1-SNAPSHOT.jar`
- Build: `frontend-repo/dist/`

- [ ] 运行 `backend-repo\\mvnw.cmd test` 与 `frontend-repo\\npm run build`。
- [ ] 备份并部署后端 JAR，允许 Flyway 执行 V46，检查服务与迁移日志。
- [ ] 部署前端静态资源，不修改 nginx、端口或其他服务。
- [ ] 使用真实教师会话验证单份/批量发布、越权门禁和撤回。
- [ ] 使用真实学生会话验证发布后可见、撤回后不可见。
