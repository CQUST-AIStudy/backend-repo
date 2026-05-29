# 学生画像增强与 LeetCode 个性化推荐实施方案（可执行版 v2）

## 1. 项目背景

### 1.1 当前基础
- 后端项目：`AI_Ds`（Spring Boot + MyBatis + MySQL）。
- 已有推荐读取链路：`AISuggestedProblemDao`、`AISuggestedProblemService`、相关 API。
- LeetCode 数据已完成清洗与入库。
  - 清洗脚本：`datasets/leetcode/clean_solutions.py`
  - 入库脚本：`datasets/leetcode/import_cleaned_to_mysql.py`
  - 当前高质量可用题目：`506` 条。

### 1.2 目标
- 构建可持续更新的学生技能画像。
- 基于 506 题实现个性化推荐。
- 输出可解释推荐理由，并形成反馈闭环。

### 1.3 设计原则
- MVP 优先：先规则+打分，后续再上复杂模型。
- 可解释优先：每条推荐都能解释“为什么推荐”。
- 安全优先：推荐按登录态学生身份执行，不信任前端 studentId。
- 兼容优先：保留旧接口，新链路逐步接管。

---

## 2. 总体架构

```text
学生行为数据(提交/成绩/实验)
          |
          v
学生画像计算服务 -----> student_skill_state
          |
          v
题库标准化服务 -----> leetcode_problem_bank + leetcode_problem_tag
          |
          v
召回服务 -> 排序服务 -> 多样性重排
          |
          v
recommend_request/item 落库
          |
          v
API 输出 + feedback 回流 + 指标评估
```

---

## 3. 数据模型（补全）

> 说明：以下是新增表，不破坏现有 `AISuggestedProblem` 读取逻辑。

### 3.1 题库主表

```sql
CREATE TABLE IF NOT EXISTS leetcode_problem_bank (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_key VARCHAR(64) NOT NULL COMMENT '如 id:1234 / title:xxx',
    problem_code VARCHAR(32) NULL COMMENT '原始题号，如 LCR 002',
    numeric_id INT NULL COMMENT '纯数字题号',
    title_main VARCHAR(255) NOT NULL,
    title_alt VARCHAR(255) NULL,
    problem_text MEDIUMTEXT NOT NULL,
    solution_text MEDIUMTEXT NOT NULL,
    source_url VARCHAR(600) NULL,
    difficulty ENUM('Easy','Medium','Hard','Unknown') NOT NULL DEFAULT 'Unknown',
    estimated_minutes INT NOT NULL DEFAULT 30,
    quality_score DECIMAL(5,4) NOT NULL DEFAULT 0.8000,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_source_key (source_key),
    KEY idx_numeric_id (numeric_id),
    KEY idx_difficulty (difficulty),
    KEY idx_quality (quality_score)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 3.2 题目标签表

```sql
CREATE TABLE IF NOT EXISTS leetcode_problem_tag (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    problem_id BIGINT NOT NULL,
    tag_name VARCHAR(64) NOT NULL,
    tag_category ENUM('algorithm','data_structure','technique') NOT NULL,
    relevance_score DECIMAL(5,4) NOT NULL DEFAULT 1.0000,
    is_primary TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_problem_tag (problem_id, tag_name),
    KEY idx_problem (problem_id),
    KEY idx_tag (tag_name),
    CONSTRAINT fk_problem_tag_problem
      FOREIGN KEY (problem_id) REFERENCES leetcode_problem_bank(id)
      ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 3.3 学生技能状态表（画像核心）

```sql
CREATE TABLE IF NOT EXISTS student_skill_state (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    student_id INT NOT NULL,
    tag_name VARCHAR(64) NOT NULL,
    mastery_score DECIMAL(5,2) NOT NULL DEFAULT 50.00 COMMENT '0~100',
    forgetting_score DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '0~100',
    confidence_score DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '0~100',
    attempt_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    avg_attempts_to_success DECIMAL(8,3) NULL,
    last_practice_at DATETIME NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_student_tag (student_id, tag_name),
    KEY idx_student (student_id),
    KEY idx_student_mastery (student_id, mastery_score),
    KEY idx_student_forgetting (student_id, forgetting_score)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 3.4 推荐请求与结果表

```sql
CREATE TABLE IF NOT EXISTS leetcode_recommend_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id CHAR(36) NOT NULL,
    student_id INT NOT NULL,
    scene VARCHAR(32) NOT NULL DEFAULT 'default',
    request_limit INT NOT NULL DEFAULT 20,
    status ENUM('pending','completed','failed') NOT NULL DEFAULT 'pending',
    error_message VARCHAR(512) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at DATETIME NULL,
    UNIQUE KEY uk_request_id (request_id),
    KEY idx_student_created (student_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

```sql
CREATE TABLE IF NOT EXISTS leetcode_recommend_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id CHAR(36) NOT NULL,
    student_id INT NOT NULL,
    rank_no INT NOT NULL,
    problem_id BIGINT NOT NULL,
    score_total DECIMAL(8,4) NOT NULL,
    score_need_match DECIMAL(8,4) NOT NULL,
    score_difficulty_fit DECIMAL(8,4) NOT NULL,
    score_success_prob DECIMAL(8,4) NOT NULL,
    score_novelty DECIMAL(8,4) NOT NULL,
    score_quality DECIMAL(8,4) NOT NULL,
    reason_text VARCHAR(512) NOT NULL,
    reason_json JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_request_rank (request_id, rank_no),
    KEY idx_request (request_id),
    KEY idx_student_created (student_id, created_at),
    CONSTRAINT fk_recommend_item_problem
      FOREIGN KEY (problem_id) REFERENCES leetcode_problem_bank(id)
      ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 3.5 反馈行为表

```sql
CREATE TABLE IF NOT EXISTS leetcode_recommend_feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id CHAR(36) NOT NULL,
    student_id INT NOT NULL,
    problem_id BIGINT NOT NULL,
    session_id VARCHAR(64) NULL,
    action ENUM('exposure','click','start','complete','skip','dislike') NOT NULL,
    action_at DATETIME NOT NULL,
    extra_json JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_student_time (student_id, action_at),
    KEY idx_request (request_id),
    KEY idx_problem_time (problem_id, action_at),
    CONSTRAINT fk_recommend_feedback_problem
      FOREIGN KEY (problem_id) REFERENCES leetcode_problem_bank(id)
      ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

---

## 4. 数据同步与标准化

### 4.1 同步来源
- 以现有 `leetcode_solutions` 作为源表。
- 同步到 `leetcode_problem_bank`。

### 4.2 同步规则
- `source_key` 唯一。
- 增量写入使用 `INSERT ... ON DUPLICATE KEY UPDATE`。
- 标签抽取失败时不丢题，保留 `Unknown` 难度和空标签。

### 4.3 标签抽取规则（MVP）
- 关键词匹配题干 + 题解。
- 严格白名单标签，禁止未知标签写入。
- 每题至少 1 个主标签，最多 5 个标签。

---

## 5. 学生画像建模

### 5.1 指标定义
- `mastery_score`：掌握度（0~100）。
- `forgetting_score`：遗忘度（0~100，随时间增长）。
- `confidence_score`：置信度（0~100，与样本量正相关）。

### 5.2 更新规则
- 成功提交：提高 mastery，降低 forgetting，提高 confidence。
- 失败提交：小幅降低 mastery，小幅提高 forgetting。
- 近 30 天行为权重高于历史行为。

### 5.3 并发安全
- 画像更新采用原子 SQL。
- 禁止无锁“先查后改再写”。
- 建议使用行锁或乐观锁版本号。

---

## 6. 推荐算法（修正版）

### 6.1 召回层
- 弱项标签召回：60%。
- 难度进阶召回：25%。
- 探索召回：15%。

### 6.2 排序层

定义：
- `need(tag) = 0.60*(1-mastery_norm) + 0.25*forgetting_norm + 0.15*course_weight`
- `need_match(problem) = sum(need(tag_i)*relevance_i) / sum(relevance_i)`
- `difficulty_fit = exp(-abs(target_difficulty - problem_difficulty))`
- `success_prob`：近 30 天同难度通过率估计（无数据回退 0.60）。
- `novelty`：近期未接触则升高。
- `repeat_penalty`：7 天内重复推荐且无交互则扣分。

最终打分：

```text
score =
  0.45 * need_match
+ 0.20 * difficulty_fit
+ 0.15 * success_prob
+ 0.10 * novelty
+ 0.10 * quality_score
- 0.15 * repeat_penalty
```

### 6.3 多样性重排
- 相邻推荐题主标签不能完全相同。
- Top10 默认配比：Easy >= 2，Medium >= 4，Hard <= 2（按能力可放宽）。

### 6.4 空弱项兜底（防除零）

```java
if (weakSkills == null || weakSkills.isEmpty()) {
    return recallByDifficultyProgression(studentId, limit);
}
int eachLimit = Math.max(1, limit / weakSkills.size());
```

---

## 7. API 设计与安全约束

### 7.1 生成推荐
- `POST /api/recommendations/leetcode/generate`
- 入参：`limit`、`scene`（可选）。
- 学生身份来自登录态/JWT，不从 body 信任 `studentId`。
- 出参：`requestId`、`status=pending`。

### 7.2 查询推荐结果
- `GET /api/recommendations/leetcode/result/{requestId}`
- 状态：`pending / completed / failed`。

### 7.3 反馈采集
- `POST /api/recommendations/leetcode/exposure`
- `POST /api/recommendations/leetcode/feedback`
- `action`：`click / start / complete / skip / dislike`。

### 7.4 兼容策略
- 保留旧接口：
  - `/api/student/{studentId}/recommendedPractices`
  - `/api/student/{studentId}/experiment/{experimentId}/recommendedPractices`
- 新链路稳定后，逐步切换旧接口数据源。

---

## 8. 服务落地清单

### 8.1 新增服务
- `StudentSkillProfileService`
- `LeetCodeProblemFeatureService`
- `LeetCodeRecallService`
- `LeetCodeRankingService`
- `LeetCodeRecommendationService`
- `LeetCodeFeedbackService`

### 8.2 DAO 增加
- `LeetCodeProblemDao`
- `StudentSkillStateDao`
- `LeetCodeRecommendDao`
- `LeetCodeFeedbackDao`

### 8.3 迁移脚本
- `V20260312_01__create_leetcode_recommend_tables.sql`
- `V20260312_02__seed_skill_tags.sql`
- `V20260312_03__sync_leetcode_problem_bank.sql`

---

## 9. 实施计划（3 周）

### Week 1：数据与画像底座
- 完成建表 + Flyway。
- 506 题同步至 `leetcode_problem_bank`。
- 实现画像日更任务。
- 新增数据质量验证脚本（完整性、重复率、字段空值、标签覆盖率）。
- 建立基础监控与告警（任务失败率、接口错误率、数据库连接池告警）。

### Week 2：推荐主链路
- 实现召回 + 排序 + 重排。
- 完成异步生成与结果查询接口。
- 完成推荐理由输出。
- 准备 A/B 测试框架（实验分组、流量分桶、指标对比口径）。
- 增加推荐效果实时监控（CTR、Start Rate、Complete Rate、Dislike Rate）。

### Week 3：反馈闭环与联调
- 完成曝光/反馈埋点。
- 前后端联调与压测。
- 指标看板与参数调优。
- 准备发布回滚方案（DDL 回滚、配置开关回退、旧接口兜底）。
- 编写运维手册（部署步骤、告警处理、常见故障排查、应急联系人）。

### 9.4 发布保障（必须项）
- 发布前检查清单：迁移脚本校验、备份确认、灰度开关验证。
- 发布后 24 小时重点观察：错误率、慢查询、推荐生成耗时、反馈落库成功率。
- 回滚触发条件：错误率超阈值、核心接口不可用、推荐结果明显异常。

---

## 10. 验收标准

### 10.1 功能
- 可按学生返回 TopN 个性化推荐。
- 每题有推荐理由。
- 全行为链路可追踪。

### 10.2 性能
- 推荐生成 P95 < 3s（506 题规模）。
- 结果查询 P95 < 200ms。
- 并发 100 用户稳定。

### 10.3 效果（上线后 2 周）
- CTR >= 20%
- Start Rate >= 35%
- Complete Rate >= 25%
- Dislike Rate <= 15%

---

## 11. 风险与对策

- 数据量较小导致协同过滤信号弱。
  - 对策：MVP 以内容+规则为主，协同过滤仅加分。
- 标签抽取噪声导致误推。
  - 对策：标签白名单 + 抽检 + 低置信降权。
- 峰值请求造成任务堆积。
  - 对策：线程池限流 + 超时 + 降级返回最近一次结果。
- 越权访问推荐数据。
  - 对策：后端强校验登录态学生身份。

---

## 12. 给 Claude 的审核重点

- DDL 是否完整覆盖 request / item / feedback 全链路。
- 评分公式是否体现“补弱优先”。
- API 是否落实鉴权边界。
- 实施计划是否可在 3 周落地 MVP。
- 旧接口兼容策略是否平滑。

---

## 13. 立即执行建议

1. 先提交 Flyway 建表脚本。
2. 将 506 题同步到 `leetcode_problem_bank` 与 `leetcode_problem_tag`。
3. 先上线同步版本推荐（不引入复杂队列）验证效果。
4. 再补异步任务、反馈闭环、效果报表。
