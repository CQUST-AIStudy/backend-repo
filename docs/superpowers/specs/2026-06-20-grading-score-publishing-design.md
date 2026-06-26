# 批改成绩发布与学生匹配设计

## 目标

为 AI 批改任务增加明确的“学生匹配确认”和“成绩发布”生命周期。教师可以单份或批量发布、撤回；学生只能看到已发布且属于自己的成绩与批注报告。

## 数据模型

在 `grading_submission` 增加：

- `match_status`：`UNMATCHED`、`AUTO_CONFIRMED`、`MANUAL_CONFIRMED`、`AMBIGUOUS`。
- `published_at`：发布时间，未发布为 `NULL`。
- `published_by`：发布教师 `tap_user.id`，未发布为 `NULL`。

已有 `student_id` 指向最终匹配的 `student_profile.id`。自动匹配成功时同步写入 `student_id/student_no/student_name/class_name`；人工匹配时由教师选择班级花名册中的学生并写入相同字段。

## 匹配规则

1. 从文件名提取 6-20 位学号。若学号对应当前教学班唯一学生，记为 `AUTO_CONFIRMED`。
2. 无学号时，从文件名中提取姓名候选。若当前班级内姓名唯一，记为 `AUTO_CONFIRMED`。
3. 同名多个学生时记为 `AMBIGUOUS`，返回候选列表。
4. 无法识别或查不到学生时记为 `UNMATCHED`。
5. 教师手动选择学生后记为 `MANUAL_CONFIRMED`。

自动确认不需要教师再次点击；`UNMATCHED/AMBIGUOUS` 必须人工确认。

## 后端接口

- `GET /api/grading/tasks/{taskId}/match-candidates`：返回班级花名册。
- `PUT /api/grading/submissions/{id}/student-match`：人工确认学生。
- `POST /api/grading/submissions/{id}/publish`：单份发布。
- `DELETE /api/grading/submissions/{id}/publish`：撤回单份发布。
- `POST /api/grading/tasks/{taskId}/publish-confirmed`：批量发布当前任务内已确认且已评分的提交。
- `DELETE /api/grading/tasks/{taskId}/publish`：批量撤回当前任务内已发布提交。
- `GET /api/student/grading-results/experiments/{experimentId}`：当前学生读取已发布成绩和报告。
- `GET /api/student/grading-results/submissions/{submissionId}/report`：当前学生下载已发布批注报告。

所有教师接口从登录态解析教师 ID，并校验 `submission.task.teacherId == currentTeacherId`。学生接口从登录态解析当前学生，不接受前端传入学生 ID。

## 发布语义

发布前要求：

- 匹配状态为 `AUTO_CONFIRMED` 或 `MANUAL_CONFIRMED`；
- `student_id` 非空；
- 批改状态为 `SCORED`，总分非空；
- 任务属于当前教师。

发布时确保总评和批注报告存在，继续同步旧版成绩表以兼容现有页面，然后写入 `published_at/published_by`。重复发布保持幂等。撤回只清空发布字段；旧表保留历史兼容数据，但学生新接口严格受发布字段控制。

## 前端行为

- `SubmissionReview.vue` 显示匹配状态、人工选择学生、发布给学生/撤回发布。
- `GradingDetail.vue` 表格显示匹配与发布状态；顶部提供“批量发布已确认的成绩”和“批量撤回”。存在未确认匹配时批量发布按钮置灰并提示“请先确认学生匹配”；全部已发布时显示“已发布”。
- `ExperimentDetail.vue` 请求学生发布结果；仅已发布时显示成绩、教师总评和批注报告下载入口。

## 验证

- 后端单元测试覆盖学号匹配、唯一姓名、重名、无法识别、人工确认、越权发布、单份发布、批量发布、撤回和学生可见性。
- 前端构建通过，并使用教师 `teacher1` 与学生账号完成真实 API 冒烟测试。
- 部署执行 V46 Flyway 迁移，验证后端健康、前端静态资源、发布后学生可见以及撤回后不可见。
