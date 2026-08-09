CREATE TABLE student_experiment_reflection (
  id BIGINT NOT NULL AUTO_INCREMENT,
  offering_id BIGINT NOT NULL,
  student_id BIGINT NOT NULL,
  reflection_text MEDIUMTEXT NOT NULL,
  source VARCHAR(32) NOT NULL DEFAULT 'AI_REPORT',
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_student_experiment_reflection UNIQUE (offering_id, student_id),
  CONSTRAINT fk_student_experiment_reflection_offering
    FOREIGN KEY (offering_id) REFERENCES assignment_offering(id) ON DELETE CASCADE,
  CONSTRAINT fk_student_experiment_reflection_student
    FOREIGN KEY (student_id) REFERENCES student_profile(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 先将既有 AI 报告末尾的“实验总结”持久化为独立心得字段。
INSERT INTO student_experiment_reflection (offering_id, student_id, reflection_text, source)
SELECT offering_id,
       student_id,
       TRIM(CASE
         WHEN LOCATE('## 实验总结', report_md) > 0
           THEN SUBSTRING(report_md, LOCATE('## 实验总结', report_md) + CHAR_LENGTH('## 实验总结'))
         WHEN LOCATE('## 六、实验总结', report_md) > 0
           THEN SUBSTRING(report_md, LOCATE('## 六、实验总结', report_md) + CHAR_LENGTH('## 六、实验总结'))
         WHEN LOCATE('## 心得体会', report_md) > 0
           THEN SUBSTRING(report_md, LOCATE('## 心得体会', report_md) + CHAR_LENGTH('## 心得体会'))
         ELSE ''
       END),
       'AI_REPORT'
FROM ai_experiment_report
WHERE LOCATE('## 实验总结', report_md) > 0
   OR LOCATE('## 六、实验总结', report_md) > 0
   OR LOCATE('## 心得体会', report_md) > 0;

-- 为已有 PTA 提交但尚无 AI 报告总结的学生插入持久化演示心得。
-- 内容只引用数据库中真实的题数、通过数和代码提交数；source 明确标记为 SYSTEM_BACKFILL。
INSERT INTO student_experiment_reflection (offering_id, student_id, reflection_text, source)
SELECT sa.offering_id,
       sa.student_id,
       CONCAT(
         '完成“', COALESCE(NULLIF(ao.title_override, ''), '本次 PTA 实验'), '”后，我对相关数据结构的操作过程和程序实现有了更直观的认识。',
         '本次共练习 ', COUNT(DISTINCT ap.id), ' 道题，其中 ',
         COUNT(DISTINCT CASE WHEN sps.accepted_at IS NOT NULL OR UPPER(COALESCE(sps.latest_status, '')) IN ('C','AC','ACCEPTED','CORRECT','PASS','PASSED','100') THEN ap.id END),
         ' 道通过，已提交代码 ', COUNT(DISTINCT CASE WHEN sps.latest_code_artifact_id IS NOT NULL THEN ap.id END), ' 道。',
         CASE
           WHEN COUNT(DISTINCT CASE WHEN sps.latest_status IS NOT NULL OR sps.latest_code_artifact_id IS NOT NULL THEN ap.id END) = 0
             THEN '当前还没有形成有效的代码提交记录。这次实验提醒我需要先完成题意分析和基本实现，再通过真实运行与判题反馈检验自己的理解。'
           WHEN COUNT(DISTINCT CASE WHEN sps.accepted_at IS NOT NULL OR UPPER(COALESCE(sps.latest_status, '')) IN ('C','AC','ACCEPTED','CORRECT','PASS','PASSED','100') THEN ap.id END) = COUNT(DISTINCT ap.id)
             THEN '现有题目均已通过，但我还需要继续关注代码可读性、复杂度分析和异常输入处理，而不只停留在通过测试。'
           ELSE '从判题结果看，我仍需逐题复盘未通过部分，重点检查题意理解、边界条件和实现细节，并通过补充测试验证修改是否有效。'
         END,
         '后续我会先梳理算法步骤，再完成编码与自测，形成从分析、实现到验证的完整实验过程。'
       ),
       'SYSTEM_BACKFILL'
FROM student_assignment sa
JOIN assignment_offering ao ON ao.id = sa.offering_id AND ao.pta_problem_set_id IS NOT NULL
JOIN assignment_problem ap ON ap.offering_id = sa.offering_id AND ap.status = 'ACTIVE'
LEFT JOIN student_problem_state sps
  ON sps.offering_id = sa.offering_id AND sps.problem_id = ap.id AND sps.student_id = sa.student_id
LEFT JOIN student_experiment_reflection existing
  ON existing.offering_id = sa.offering_id AND existing.student_id = sa.student_id
WHERE existing.id IS NULL
GROUP BY sa.offering_id, sa.student_id, ao.title_override;
