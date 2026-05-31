# ptadatabase 数据库表说明

生成时间：2026-05-31  
数据库：`ptadatabase`  
说明：本文档根据当前数据库表名、字段、外键关系和项目模块推断整理，用于快速理解每张表的用途。实际业务含义以代码逻辑为准。

## 总览

当前数据库主要分为这些模块：

| 模块 | 说明 |
|---|---|
| 系统与用户 | 用户、教师、学生、班级、课程、学期 |
| 作业与 PTA 同步 | PTA 导入、作业发布、学生作业完成状态、题目尝试记录 |
| 旧版实验系统 | 原始学生、教师、实验、提交、成绩、AI 评语等表 |
| 智能批改 | 批改任务、评分规则、评分项、证据、报告文件 |
| RAG 与课程资料 | 课程空间、文档、切片、向量检索、问答日志 |
| 文件与资料整理 | 上传文件夹、文档、Agent 整理、Zip 整理 |
| LeetCode 推荐 | 题库、标签、推荐请求、推荐结果、反馈 |
| 论文资料库 | arXiv 论文、作者、分类、收藏 |
| 审计与配额 | 操作审计、每日额度统计 |
| 迁移管理 | Flyway 数据库迁移历史 |

## 系统与用户

| 表名 | 用途 | 关键字段/关系 |
|---|---|---|
| `tap_user` | 新版统一用户表，主要用于教师、管理员登录，也保存 PTA 凭据密文。 | `username`、`display_name`、`role`、`password_hash`、`pta_username`、`pta_password_ciphertext` |
| `user` | 旧版用户表，保存学生/教师/管理员账号。 | `username`、`password`、`role`、`usernum`、`classname` |
| `student_profile` | 新版统一学生档案表，按学号管理学生身份。 | `student_no`、`real_name`、`user_id -> tap_user.id` |
| `student` | 旧版学生表，保存学生账号、姓名、班级。 | `student_id`、`username`、`password`、`name`、`class_name` |
| `teacher` | 旧版教师表，保存教师账号和班级字段。 | `teacher_id`、`teacher_name`、`username`、`classroom` |
| `teaching_class` | 新版教学班表，保存班级、教师、课程、学期、PTA 同步状态。 | `teacher_id -> tap_user.id`、`course_id -> course.id`、`term_id -> academic_term.id`、`class_code`、`pta_keyword` |
| `class_member` | 新版班级成员关系表，表示某学生属于某教学班。 | `class_id -> teaching_class.id`、`student_id -> student_profile.id` |
| `class_student` | 班级学生兼容表，保存班级下的学生姓名、学号和用户关联。 | `class_id -> teaching_class.id`、`student_name`、`student_num`、`user_id` |
| `course` | 课程主数据表。 | `course_code`、`name`、`subject`、`status` |
| `academic_term` | 学期主数据表。 | `term_code`、`name`、`start_date`、`end_date`、`status` |
| `external_identity_binding` | 外部系统身份绑定表，用于把本系统实体和 PTA 等外部 ID 关联。 | `entity_type`、`entity_id`、`source_system`、`external_id`、`confidence` |

## 作业与 PTA 同步

| 表名 | 用途 | 关键字段/关系 |
|---|---|---|
| `assignment_template` | 作业模板表，保存可复用的作业定义。 | `title`、`category`、`language`、`created_by -> tap_user.id` |
| `assignment_offering` | 作业发布表，表示某模板发布到某个班级。 | `template_id -> assignment_template.id`、`class_id -> teaching_class.id`、`teacher_id -> tap_user.id`、`pta_problem_set_id` |
| `assignment_problem` | 作业中的题目表。 | `offering_id -> assignment_offering.id`、`problem_no`、`title`、`max_score` |
| `student_assignment` | 学生维度的作业完成状态汇总。 | `offering_id -> assignment_offering.id`、`student_id -> student_profile.id`、`submission_status`、`best_total_score`、`completion_evidence` |
| `student_problem_attempt` | 学生每次 PTA 题目提交/尝试记录。 | `offering_id`、`problem_id`、`student_id`、`judge_status`、`score`、`raw_row_id -> pta_raw_submission_row.id` |
| `student_problem_state` | 学生在某作业题目上的最新/最佳状态。 | `latest_attempt_id`、`best_attempt_id`、`best_score`、`attempt_count` |
| `import_job` | PTA 或其他外部数据导入任务记录。 | `source_system`、`job_type`、`class_id -> teaching_class.id`、`triggered_by -> tap_user.id`、`status` |
| `import_source_file` | 导入任务中的源文件记录。 | `import_job_id -> import_job.id`、`file_role`、`relative_path`、`sha256`、`parse_status` |
| `pta_raw_submission_row` | PTA 提交列表的原始行数据。 | `import_job_id`、`source_file_id`、`pta_user_id`、`pta_problem_id`、`judge_status`、`raw_json` |
| `pta_raw_transcript_row` | PTA 成绩单/总分表的原始行数据。 | `student_no`、`student_name`、`total_score_text`、`ranking_text`、`raw_json` |
| `pta_raw_answer_sheet` | PTA 答题详情、HTML、代码、测试报告等原始资料。 | `student_no`、`problem_key`、`html_artifact_id -> artifact.id`、`code_artifact_id -> artifact.id` |
| `artifact` | 通用文件/文本制品表，用于保存导入过程中抽取出的 HTML、代码、报告等。 | `owner_type`、`owner_id`、`artifact_type`、`object_key`、`text_content`、`source_system` |

## 旧版实验系统

这些表大多是早期实验/提交/成绩功能使用的表，当前项目中仍有兼容逻辑。

| 表名 | 用途 | 关键字段/关系 |
|---|---|---|
| `experiment` | 实验/作业基础信息。 | `experiment_id`、`name`、`deadline`、`describe`、`requirements`、`teacher_id` |
| `submission` | 学生提交的代码和报告。 | `username`、`experiment_id`、`code`、`report`、`submit_time` |
| `student_code` | 按学生和实验保存代码内容。 | `experiment_id`、`student_id`、`code` |
| `score` | 实验成绩表。 | `username`、`experiment_id`、`score`、`plagiarism_rate`、`status` |
| `submit_situation` | PTA/提交情况明细表。 | `student_id`、`experiment_id`、`situation`、`score`、`runtime_ms`、`memory_kb` |
| `problem_score_detail` | 学生按题目的得分明细。 | `experiment_id`、`student_id`、`problem_label`、`actual_score`、`ranking` |
| `problems_sets` | 实验题目集合，通常以长文本保存题目信息。 | `experiment_id`、`problem` |
| `tk` | 题库/题目 URL 表。 | `problems_id`、`problems_content`、`problems_url` |
| `plagiarism_check_table` | 查重结果表。 | `student_id`、`experiment_id`、`Plagiarism_Rate` |
| `total_submission_analysis` | 总体提交情况分析文本。 | `total_analysis` |
| `ai_remarks` | 单个学生某实验的 AI 评语。 | `student_id`、`experiment_id`、`airemark` |
| `ai_submission_analysis` | 某实验整体提交情况的 AI 分析。 | `experiment_id`、`AI_analysis` |
| `ai_suggested_problems` | 针对学生和实验生成的推荐题目。 | `student_id`、`experiment_id`、`suggested_problems` |
| `ai_pta_suggested_url` | 按学生保存推荐 PTA 题目链接。 | `student_name`、`PTAURLS` |
| `profile_ai_feedback` | 学生画像相关 AI 反馈。 | `student_id`、`feedback`、`profile_json` |

## 智能批改

| 表名 | 用途 | 关键字段/关系 |
|---|---|---|
| `grading_rubric` | 批改规则/评分标准主表。 | `teacher_id -> tap_user.id`、`name`、`subject`、`custom_prompt` |
| `rubric_dimension` | 评分标准下的具体评分维度。 | `rubric_id -> grading_rubric.id`、`name`、`max_score`、`weight` |
| `grading_task` | 一次批改任务。 | `teacher_id`、`assignment_offering_id`、`rubric_id`、`status`、`total_count`、`completed_count` |
| `grading_submission` | 批改任务中的单个学生提交。 | `task_id -> grading_task.id`、`student_id -> student_profile.id`、`pdf_object_key`、`total_score`、`status` |
| `score_item` | 某个提交在某个评分维度上的得分。 | `submission_id -> grading_submission.id`、`dimension_id -> rubric_dimension.id`、`score`、`comment` |
| `score_override` | 教师人工改分记录。 | `score_item_id -> score_item.id`、`teacher_id -> tap_user.id`、`old_score`、`new_score`、`reason` |
| `evidence_block` | 批改证据块，如 PDF 页码、文本、截图坐标等。 | `submission_id -> grading_submission.id`、`evidence_id`、`kind`、`content` |
| `report_file` | 批改报告文件记录。 | `task_id -> grading_task.id`、`submission_id -> grading_submission.id`、`file_type`、`object_key` |
| `grading_trace` | 批改流程追踪日志，记录每一步耗时、模型和错误。 | `submission_id -> grading_submission.id`、`step`、`status`、`duration_ms`、`model_used` |

## RAG 与课程资料

| 表名 | 用途 | 关键字段/关系 |
|---|---|---|
| `course_space` | 教师课程知识库空间。 | `teacher_id -> tap_user.id`、`name`、`course_name`、`default_mode`、`allow_web_search` |
| `course_space_class` | 课程知识库和教学班的关联。 | `course_space_id -> course_space.id`、`class_id -> teaching_class.id` |
| `course_space_document` | 课程知识库中的文档关联和处理状态。 | `course_space_id`、`document_id`、`doc_type`、`status`、`chunk_count` |
| `document` | 上传文档元数据和抽取文本。 | `user_id -> tap_user.id`、`upload_folder_id`、`filename`、`sha256`、`object_key`、`extracted_text` |
| `doc_chunk` | 文档切片，用于 BM25/向量检索。 | `document_id`、`course_space_id`、`chunk_type`、`content`、`milvus_id` |
| `doc_chunk_annotation` | 教师对文档切片的标注。 | `chunk_id -> doc_chunk.id`、`teacher_id -> tap_user.id`、`annotation_type`、`note` |
| `chapter_summary` | 章节摘要，支持按文档章节组织内容。 | `doc_id -> document.id`、`course_space_id`、`chapter_path`、`summary_text` |
| `qa_log` | RAG 问答日志。 | `student_id`、`course_space_id`、`query`、`answer_text`、`citations_json`、`feedback` |
| `structured_summary` | AI 生成的结构化摘要缓存。 | `scope_type`、`scope_key`、`provider`、`model`、`summary_json`、`markdown` |
| `translation_segment` | 文档翻译分段缓存。 | `document_id -> document.id`、`target_lang`、`source_text`、`target_text`、`provider` |

## 文件上传与资料整理

| 表名 | 用途 | 关键字段/关系 |
|---|---|---|
| `upload_folder` | 用户上传文件夹记录。 | `user_id -> tap_user.id`、`folder_name`、`original_structure_json` |
| `agent_job` | AI 文件整理任务。 | `user_id -> tap_user.id`、`upload_folder_id`、`status`、`progress`、`zip_object_key` |
| `agent_job_file` | AI 整理任务中的文件。 | `job_id -> agent_job.id`、`document_id -> document.id`、`filename`、`object_key`、`status` |
| `agent_file_extract` | AI 整理前对文件抽取出的标题、摘要、元数据。 | `job_file_id -> agent_job_file.id`、`title_candidate`、`headings_json`、`metadata_json` |
| `agent_organize_plan` | AI 生成的文件整理计划。 | `job_id`、`job_file_id`、`target_object_key`、`new_filename`、`confidence`、`review_flag` |
| `agent_result` | AI 整理任务最终结果。 | `job_id -> agent_job.id`、`topic`、`tags_json`、`summary`、`result_json` |
| `zip_organize_job` | ZIP 包整理任务。 | `user_id -> tap_user.id`、`original_filename`、`input_object_key`、`status`、`result_json` |
| `zip_organize_item` | ZIP 包中的单个文件整理结果。 | `job_id -> zip_organize_job.id`、`original_path`、`doc_kind`、`topic`、`new_filename`、`final_path` |

## LeetCode 推荐

| 表名 | 用途 | 关键字段/关系 |
|---|---|---|
| `leetcode_problem_bank` | LeetCode 题库主表。 | `source_key`、`problem_code`、`title_main`、`difficulty`、`problem_text`、`solution_text` |
| `leetcode_problem_tag` | 题目标签表。 | `problem_id -> leetcode_problem_bank.id`、`tag_name`、`tag_type` |
| `student_skill_state` | 学生技能掌握度状态。 | `student_id`、`tag_name`、`mastery_score`、`attempt_count`、`success_count` |
| `leetcode_recommend_request` | 一次推荐请求。 | `request_id`、`student_id`、`scene`、`request_limit`、`status` |
| `leetcode_recommend_item` | 推荐请求返回的具体题目及推荐理由。 | `request_id`、`problem_id -> leetcode_problem_bank.id`、`rank_no`、`score`、`reason_text` |
| `leetcode_recommend_feedback` | 学生/用户对推荐题目的反馈。 | `student_id`、`problem_id -> leetcode_problem_bank.id`、`feedback_type`、`feedback_value` |

## 论文资料库

| 表名 | 用途 | 关键字段/关系 |
|---|---|---|
| `paper` | arXiv 论文主表。 | `arxiv_id`、`title`、`abstract_text`、`pdf_url`、`published_at` |
| `paper_author` | 论文作者表。 | `paper_id -> paper.id`、`author_name` |
| `paper_category` | 论文分类表。 | `paper_id -> paper.id`、`category` |
| `library_item` | 用户收藏/保存的论文资料。 | `user_id -> tap_user.id`、`paper_id -> paper.id`、`saved_at`、`note` |

## 审计、额度与迁移

| 表名 | 用途 | 关键字段/关系 |
|---|---|---|
| `audit_event` | 用户操作审计日志。 | `user_id -> tap_user.id`、`action`、`target_type`、`ip`、`trace_id` |
| `user_daily_quota_usage` | 用户每日额度使用量。 | `user_id -> tap_user.id`、`usage_date`、`translation_chars`、`ai_requests` |
| `flyway_schema_history` | Flyway 自动建表/数据库迁移历史表。不要手动业务写入。 | `version`、`description`、`script`、`checksum`、`success` |

## 当前非空表

当前多数业务表为空，非空表如下：

| 表名 | 当前行数 | 说明 |
|---|---:|---|
| `flyway_schema_history` | 27 | 已成功执行 27 个 Flyway 迁移脚本 |
| `tap_user` | 2 | 开发种子用户，如管理员、教师 |
| `teacher` | 1 | 旧版教师种子数据 |
| `user` | 2 | 旧版用户种子数据 |

## 重要关系速查

| 关系 | 说明 |
|---|---|
| `tap_user` -> `teaching_class` | 一个教师/用户可创建多个教学班 |
| `teaching_class` -> `student_profile` | 通过 `class_member` 或 `class_student` 建立班级学生关系 |
| `assignment_template` -> `assignment_offering` -> `assignment_problem` | 作业模板发布到班级后形成作业实例和题目 |
| `assignment_offering` + `student_profile` -> `student_assignment` | 学生在某次作业上的汇总状态 |
| `student_assignment` -> `student_problem_attempt` / `student_problem_state` | 学生每道题的尝试记录和最新状态 |
| `import_job` -> `import_source_file` -> `pta_raw_*` | PTA 导入任务、源文件和原始数据 |
| `grading_rubric` -> `rubric_dimension` | 批改规则和评分维度 |
| `grading_task` -> `grading_submission` -> `score_item` | 批改任务、学生提交和评分项 |
| `course_space` -> `document` -> `doc_chunk` | RAG 知识库、文档和切片 |
| `upload_folder` -> `document` -> `agent_job_file` | 上传文件到 AI 整理任务 |

