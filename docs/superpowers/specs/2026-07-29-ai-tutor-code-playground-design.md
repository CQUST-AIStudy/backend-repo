# AI 助教 · 代码演示（手动输入错误代码 → 执行/错误动画）设计

- 状态：已通过 brainstorming 澄清与方案评审（采用方案 A）
- 影响仓库：`backend-repo`（核心：执行/持久化/安全）、`frontend-repo`（新页面/菜单/API）
- 关联既有能力：`code_tracer.py`（gcc 插桩真实执行）、`ConceptStepsWorkflow`（LLM 概念动画）、`ErrorDemonstrationPlayer.vue` + `PythonTutorRenderer.vue`（统一步骤播放器/渲染器）、`StudentCodeDemoService`（真实执行优先→LLM 兜底 合成逻辑）

## 背景与动机

PTA/爬虫只保留并同步学生**最终正确代码**（错误代码不爬取、也存不下），因此现有"按题代码演示"（跑已入库代码）**天然无法演示错误**。本功能新增一个入口：学生在 **AI 助教**里手动粘贴"错误代码 + 题目描述"，系统用同一套动画引擎生成执行/错误演示，并保存历史供回看。

## 已确认的关键决策

1. 执行方式：**真实执行优先 + LLM 兜底**。能编译就用 gcc 插桩真实执行；编译失败/无有效轨迹则回退 `ConceptStepsWorkflow` 的 LLM 概念动画（错误代码常编译不过，正好走错误演示）。
2. 位置：AI 助教「AI 智能助手」分组下**新增独立子页**（非聊天集成）。
3. 存储：**保存历史，可回看**（按学生存多条）。
4. 沙箱：**轻量加固**——在现有容器内对 gcc 编译与运行的子进程加资源限制（非 root + 墙钟超时 + `setrlimit` CPU/内存/文件大小 + 输出截断 + 源码/输入大小上限）。强隔离（一次性容器/nsjail）留作后续。

## 架构与数据流

```
学生页面(CodePlayground.vue)
  输入: code(必填) + problemMd(选填) + stdin(选填) + title(选填)
        │  POST /api/student/ai-assistant/code-demo/generate
        ▼
CodePlaygroundController → CodePlaygroundService
        │  1) 解析学生身份(StudentPrincipalResolver)
        │  2) stdin 为空 → CodeDemoComposer.autoStdin(problemMd, code)  (LLM/题面样例)
        │  3) CodeDemoComposer.buildDemonstration(code, stdin, title)   (真实执行优先→LLM 兜底)
        │  4) 存一条 student_code_playground 历史
        ▼
返回 view {id,status,title,workflow,stdin,demonstration}
        ▼
前端用 ErrorDemonstrationPlayer(variant="plain", readonly) 播放；左侧历史列表可回看/删除
```

## 后端设计（backend-repo）

### 复用与重构（DRY）
现有 `StudentCodeDemoService` 内的 `buildDemonstration / autoStdin / llmGenerateStdin / sanitizeStdin / demonstration / ensureMainFunction / resolveStdin / cleanSample` 抽取为一个可注入组件 **`CodeDemoComposer`**（`com.tap.backend.service.animation`）。`StudentCodeDemoService`（按题演示）与新的 `CodePlaygroundService`（手动输入）都注入并复用它，避免逻辑重复。组件不含持久化，只负责"输入代码/ stdin/题面 → demonstration Map"。

### 数据表（Flyway 迁移）
`student_code_playground`（按学生存历史）：
- `id BIGINT PK AUTO_INCREMENT`
- `student_profile_id BIGINT NOT NULL`（归属，用于鉴权与列表过滤）
- `title VARCHAR(512)`、`problem_md TEXT`、`source_code LONGTEXT`、`stdin_text TEXT`
- `workflow VARCHAR(32)`、`frames_json LONGTEXT`、`explanation TEXT`、`error_line INT NOT NULL DEFAULT 0`
- `status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED'`、`created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP`
- 索引：`INDEX idx_scp_student_created (student_profile_id, created_at DESC)`
- 迁移版本号：实现时取当前 `origin/main` 迁移目录最大号 +1（预计 V71，务必现查避免与他人撞号）。

### 实体/仓库/服务/控制器
- `StudentCodePlaygroundEntity`（`domain.animation`）；`StudentCodePlaygroundRepository`（`repo`，`findByIdAndStudentProfileId`、`findTop50ByStudentProfileIdOrderByCreatedAtDesc`）。
- `CodePlaygroundService`：`generate(...)`（合成+存库+返回视图）、`history(principal)`、`detail(id, principal)`、`delete(id, principal)`；所有按 id 的操作都校验 `student_profile_id` 归属，不匹配返回 404。
- `StudentCodePlaygroundController` `@RequestMapping("/api/student/ai-assistant/code-demo")`：
  - `POST /generate`：`@RequestBody GenerateRequest{ String title; String problemMd; String code; String stdin; }`；`code` 空 → 400。返回 `ApiResponse.of(view)`。
  - `GET /history`：返回列表项 `{id,title,workflow,createdAt}`。
  - `GET /history/{id}`：返回详情视图（含 demonstration）。
  - `DELETE /history/{id}`：删除自己的记录。
  - 统一 `@AuthenticationPrincipal UserPrincipal`。

### 安全
- `SecurityConfig` 放行 `/api/student/ai-assistant/**` 给 `hasRole(STUDENT)`。
- **沙箱加固**（`code_tracer.py`，同时惠及现有 tracer）：C 编译与运行的 `subprocess.run` 通过 `preexec_fn` 调用 `resource.setrlimit` 设置 `RLIMIT_CPU`（如 5s）、`RLIMIT_AS`（如 512MB）、`RLIMIT_FSIZE`（如 8MB）；保留现有墙钟超时；截断 stdout/stderr（如 ≤256KB）；对源码长度/ stdin 长度设上限（后端侧，如源码 ≤64KB）。非 root 已具备。仅 Linux 生效（Windows 本地开发跳过 setrlimit）。
- 残留风险（记录在案）：仍在同一后端容器内执行，非完全隔离；面向教育内网、配合上述限制可接受。

## 前端设计（frontend-repo）
- 路由：`StudentLayout` 子路由新增 `{ path: 'code-playground', name: 'StudentCodePlayground', component: CodePlayground.vue }`（即 `/student/code-playground`）。
- 菜单：`Layout.vue` 的「AI 智能助手」分组 children 增加 `{ path: '/student/code-playground', label: '代码演示' }`。其为独立菜单路径，`activeMenu` 无需特殊映射。
- 页面 `CodePlayground.vue`：
  - 输入区：代码框（必填、等宽）、题目描述框、stdin 框（占位"留空则由 AI 自动生成"）、标题框（选填，留空自动取题目首行/时间）。
  - `生成演示` 按钮：复用 `CodeDemoPage.vue` 的加载动效（旋转 loader、滚动提示、骨架）；空代码禁用。
  - 结果区：`ErrorDemonstrationPlayer :demonstrations="[demo]" variant="plain" readonly`；顶部显示"生成方式：真实执行 / AI 概念动画"与"本次输入"。
  - 历史区：左侧/顶部列表（标题+时间+方式），点击加载详情，支持删除；生成后自动刷新并置顶。
- `api/index.js`（走 apiClient，JSON）：`generatePlaygroundDemo({title,problemMd,code,stdin})`(timeout 180000)、`getPlaygroundHistory()`、`getPlaygroundDemo(id)`、`deletePlaygroundDemo(id)`。
- 渲染器零改动，仅复用。

## 测试
- 后端 `CodePlaygroundServiceTest`（Mockito，参照 `StudentCodeDemoServiceTest`）：
  - 真实执行有帧 → workflow=PYTHON_TUTOR 且存库；
  - 执行失败 → 回退 CONCEPT_STEPS 且存库；
  - `history/detail` 只返回本人记录；跨学生按 id 访问/删除 → 404；
  - 空代码 → 400。
- 前端：`vue-cli-service lint` 通过。

## 明确不做（YAGNI）
- 不改动现有"按题代码演示"页与其数据链；
- 不新增 C/Python 之外的语言；
- 不做分享/协作/评论；
- 不做强隔离沙箱（一次性容器/nsjail）——列为后续增强。

## 假设与依赖
- 真实执行依赖容器内 `gcc`/`python3`/`code_tracer.py`（121 已具备）；LLM 依赖 AI Key（121 已配 DeepSeek）。
- 迁移版本号需实现时现查最新，避免与并行分支撞号（曾发生 V68 撞号）。
- 本功能为前后端改动，需重建 121 前后端容器才生效（部署由用户负责）。
