# 学生个性化力扣题目推荐系统设计（AI_Ds）

## 1. 文档目标

在现有 `AI_Ds` 后端与现有爬虫数据基础上，落地一个可实施、可迭代、可评估的学生个性化力扣推荐系统。

- V1：规则召回 + 可解释排序 + 反馈闭环。
- V2：引入向量召回/学习排序，持续优化效果。

---

## 2. 现状与问题

## 2.1 已有基础

- 已有学生画像能力（`ProfileService`）：`mastery/confidence/weakness/trend`。
- 已有推荐相关接口入口：
  - `/api/student/{studentId}/recommendedPractices`
  - `/api/current/recommendedPractices`
  - `/api/student/{studentId}/experiment/{experimentId}/recommendedPractices`
- 已有关键业务表：`submit_situation`、`score`、`student_code`。
- 已有力扣数据：`datasets/leetcode/solutions*.json`、`urls.json`。

## 2.2 当前核心问题

- 当前推荐主要是读已有文本，不是完整推荐引擎。
- `AISuggestedProblemMapper.xml` 中 `findByStudentIdAndExperimentId` 被注释，存在可用性风险。
- 力扣数据是半结构化（`instruction/input/output`），未完成标准化与标签化。

---

## 3. 总体架构

```text
LeetCode爬虫数据 + 学生行为数据(submit_situation/score)
      -> 题库标准化(lc_problem, lc_problem_tag)
      -> 学生画像快照(student_skill_snapshot)
      -> 召回层(薄弱点/难度/探索/热门)
      -> 排序层(多目标打分 + 多样性约束)
      -> 推荐结果存储(recommendation_result)
      -> 在线API输出(兼容旧接口)
      -> 行为反馈与曝光回流
      -> 指标评估与策略迭代
```

---

## 4. 数据模型设计

## 4.1 新增表

1. `lc_problem`
- `problem_id` 主键（力扣题号）
- `title`
- `slug`
- `url`
- `difficulty`（easy/medium/hard）
- `content`（题面）
- `solution_summary`
- `solution_raw`
- `source`
- `created_at`
- `updated_at`

2. `lc_problem_tag`
- `id` 主键
- `problem_id`
- `tag_type`（topic/skill/algo）
- `tag_value`

3. `student_skill_snapshot`
- `id` 主键
- `student_id`
- `snapshot_time`
- `dimension`
- `mastery`
- `confidence`
- `weak_points_json`

4. `recommendation_result`
- `id` 主键
- `request_id`
- `student_id`
- `experiment_id`（可空）
- `problem_id`
- `rank_index`
- `score_total`
- `score_breakdown_json`
- `reason_text`
- `created_at`

5. `recommendation_exposure`（仅曝光，去重用）
- `id` 主键
- `request_id`
- `student_id`
- `problem_id`
- `session_id`
- `exposed_at`

6. `recommendation_feedback`（行为事件，不去重）
- `id` 主键
- `request_id`
- `student_id`
- `problem_id`
- `action`（click/start/complete/skip/dislike）
- `session_id`（可空）
- `action_time`

## 4.2 索引建议

```sql
-- lc_problem
CREATE INDEX idx_lc_problem_difficulty ON lc_problem(difficulty);
CREATE INDEX idx_lc_problem_source ON lc_problem(source);

-- lc_problem_tag
CREATE INDEX idx_lc_problem_tag_problem ON lc_problem_tag(problem_id, tag_type, tag_value);
CREATE INDEX idx_lc_problem_tag_value ON lc_problem_tag(tag_value);

-- student_skill_snapshot
CREATE INDEX idx_student_snapshot_time ON student_skill_snapshot(student_id, snapshot_time DESC);

-- recommendation_result
CREATE UNIQUE INDEX idx_rec_result_req_rank ON recommendation_result(request_id, rank_index);
CREATE INDEX idx_rec_result_student_time ON recommendation_result(student_id, created_at DESC);
CREATE INDEX idx_rec_result_exp_time ON recommendation_result(experiment_id, created_at DESC);

-- recommendation_exposure（曝光去重）
CREATE UNIQUE INDEX idx_rec_exposure_dedup ON recommendation_exposure(request_id, problem_id, session_id);
CREATE INDEX idx_rec_exposure_student_time ON recommendation_exposure(student_id, exposed_at DESC);

-- recommendation_feedback（保留重复行为）
CREATE INDEX idx_rec_feedback_student_time ON recommendation_feedback(student_id, action, action_time DESC);
CREATE INDEX idx_rec_feedback_request ON recommendation_feedback(request_id);
```

说明：
- 曝光和行为分表，避免“曝光去重”误伤 click/start 等重复行为统计。
- `lc_problem.problem_id` 已为主键，不再重复创建唯一索引。

---

## 5. 推荐算法设计

## 5.1 特征层

学生特征（复用 `ProfileService`）：
- `mastery_d`
- `confidence_d`
- `error_pattern`
- `attempt_efficiency`
- `recent_trend`

题目特征：
- `difficulty_level`
- `topic_tags`
- `estimated_concept_weight`
- `quality_score`

## 5.2 召回层（Recall）

V1 四路召回并集：

1. 薄弱维度召回（召回配额 40%）
2. 难度梯度召回（召回配额 40%）
3. 探索性召回（召回配额 15%）
4. 热门题召回（召回配额 5%，可选）

建议规模：
- 总召回量 100~130。
- 去重后进入排序层约 80~100。

注意：
- 这里的“配额”是召回采样比例，不是最终排序权重。

## 5.3 排序层（Rank）

V1 打分公式：

```text
total_score =
0.35 * weakness_match +
0.25 * difficulty_match +
0.15 * novelty +
0.15 * diversity_bonus +
0.10 * quality_score
```

多样性约束：
- Top20 中单一标签占比 <= 40%。
- 至少覆盖 2~3 个相关技能标签。
- 连续 3 题不能同标签。
- 难度分布按学生 mastery 动态调整。

## 5.4 解释层

每题输出可解释理由：

```json
{
  "reason_type": "weakness_targeting",
  "reason_text": "你在树遍历维度 mastery=38.2，本题用于巩固前序/层序遍历。"
}
```

LLM 仅负责润色解释，不参与主排序决策。

---

## 6. 在线流程与 API 契约（统一版）

## 6.1 统一流程（异步主方案）

1. 客户端调用 `POST /api/recommendations/generate`。
2. 服务端快速返回 `requestId` 与状态 `pending`。
3. 异步任务执行：画像 -> 召回 -> 排序 -> 入库。
4. 客户端轮询 `GET /api/recommendations/result/{requestId}` 获取结果。
5. 前端上报曝光与行为。

说明：
- 为避免契约冲突，生产主方案统一使用“异步生成 + 结果查询”。
- 若需同步模式，仅用于调试，不作为默认契约。

## 6.2 新增接口

1. `POST /api/recommendations/generate`
- 入参：`studentId`、`experimentId`（可空）、`limit`（默认20）
- 出参：
```json
{ "requestId": "xxx", "status": "pending" }
```

2. `GET /api/recommendations/result/{requestId}`
- 出参状态：`pending/processing/completed/failed`
- `completed` 时返回推荐列表。

3. `POST /api/recommendations/exposure`
- 入参：`requestId`、`problemId`、`sessionId`
- 语义：记录曝光；按 `(requestId, problemId, sessionId)` 去重。

4. `POST /api/recommendations/feedback`
- 入参：`requestId`、`problemId`、`action`、`sessionId`（可空）
- `action`：`click/start/complete/skip/dislike`

## 6.3 兼容旧接口

保留：
- `/api/current/recommendedPractices`
- `/api/student/{studentId}/recommendedPractices`

策略：
- 旧接口内部调用新推荐服务，再转换为旧字段格式返回。

## 6.4 旧接口映射（实施前确认）

实施前必须补充真实线上返回样例（调用现有接口抓包）：

```json
// TODO: 在实施前替换为真实返回样例
```

映射示例（伪代码）：

```java
public LegacyRecommendationDto toLegacyFormat(RecommendationItem item, Student student) {
    return LegacyRecommendationDto.builder()
        .studentId(student.getStudent_id())
        .studentName(student.getName())
        .content(item.getProblem().getContent())
        .type("leetcode")
        .number(String.valueOf(item.getProblem().getProblemId()))
        .title(item.getProblem().getTitle())
        .url(item.getProblem().getUrl())
        .build();
}
```

---

## 7. 高并发与性能设计

## 7.1 异步与线程池

- 推荐任务异步执行。
- 四路召回并行执行（`CompletableFuture`）。
- 独立线程池，避免与 Web 请求线程争抢。

## 7.2 缓存

多级缓存：
- L1 本地缓存（Caffeine）
- L2 Redis

推荐缓存键必须包含全部影响参数：

```java
@Cacheable(
    value = "recommendations",
    key = "#studentId + '_' + (#experimentId == null ? 'null' : #experimentId) + '_' + #limit + '_' + #strategyVersion",
    unless = "#result == null"
)
```

## 7.3 限流与降级

- 接口限流（429）。
- 超时降级到通用题单/热门题单。
- 关键指标告警：
  - P95 > 3s
  - 缓存命中率 < 60%
  - 降级率 > 5%

---

## 8. 离线任务

1. 题库标准化任务（每日/手动）
- `solutions_sorted.json + urls.json` -> `lc_problem + lc_problem_tag`

2. 学生画像快照任务（每小时/提交后触发）
- 复用 `ProfileService`，产出 `student_skill_snapshot`

3. 推荐效果统计任务（每日）
- 聚合 CTR、StartRate、CompleteRate、7 日活跃

---

## 9. 与现有代码对接

## 9.1 第一阶段必须修复

1. 修复 `AISuggestedProblemMapper.xml`：补回 `findByStudentIdAndExperimentId`。
2. 补齐 `resultMap` 字段映射（`studentId/experimentId/content` 等）。
3. 新建 `RecommendationService`，避免 `ApiController` 继续膨胀。

## 9.2 推荐代码目录

```text
AI_Ds/src/main/java/com/tap/backend/academic/
  recommendation/
    controller/RecommendationController.java
    service/RecommendationService.java
    service/impl/RecommendationServiceImpl.java
    model/RecommendationItem.java
    model/ScoreBreakdown.java
  dao/
    RecommendationDao.java

AI_Ds/src/main/resources/
  mappers/
    RecommendationMapper.xml
```

---

## 10. 评估指标

推荐质量：
- CTR = 点击 / 曝光
- StartRate = 开始做题 / 点击
- CompleteRate = 完成通过 / 开始做题
- 新颖性、覆盖率、多样性

学习效果：
- 推荐后 7/14 天 mastery 变化
- 错误率下降
- 活跃持续性

系统性能：
- P50/P95/P99 响应时间
- 召回与排序耗时
- 缓存命中率
- 降级率

---

## 11. 风险与应对

1. 标签噪声高
- 先做 100~200 题人工种子标注，再扩展自动标注。

2. 冷启动
- 无提交学生走入门题单 + 班级平均画像兜底。

3. 推荐过度同质化
- 强制多样性约束 + 探索配额。

4. 性能波动
- 异步化、缓存、限流、降级、告警全链路配置。

---

## 12. 实施节奏（3 周）

Week 1：
- 建表 + 修 mapper + 打通异步 API（generate/result/exposure/feedback）
- 上线基础召回与排序

Week 2：
- 多样性重排与解释优化
- 兼容旧接口字段映射

Week 3：
- 指标看板 + 调权 + A/B 实验准备

---

## 13. MVP 验收标准

- 可按学生返回 Top20 个性化题目。
- 推荐理由可解释。
- 曝光/行为可追溯入库。
- 与现有前端接口兼容，不要求前端大改。
- 支持后续基于反馈调权迭代。

