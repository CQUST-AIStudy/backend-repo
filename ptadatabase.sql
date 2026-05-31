/*
 Navicat Premium Data Transfer

 Source Server         : mypractice
 Source Server Type    : MySQL
 Source Server Version : 90400 (9.4.0)
 Source Host           : localhost:3306
 Source Schema         : ptadatabase

 Target Server Type    : MySQL
 Target Server Version : 90400 (9.4.0)
 File Encoding         : 65001

 Date: 31/05/2026 12:41:34
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for academic_term
-- ----------------------------
DROP TABLE IF EXISTS `academic_term`;
CREATE TABLE `academic_term`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `term_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `start_date` date NULL DEFAULT NULL,
  `end_date` date NULL DEFAULT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ACTIVE',
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_academic_term_code`(`term_code` ASC) USING BTREE,
  CONSTRAINT `chk_academic_term_status` CHECK (`status` in (_utf8mb4'ACTIVE',_utf8mb4'ARCHIVED'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for agent_file_extract
-- ----------------------------
DROP TABLE IF EXISTS `agent_file_extract`;
CREATE TABLE `agent_file_extract`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `job_file_id` bigint NOT NULL,
  `title_candidate` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `headings_json` json NULL,
  `abstract_snippet` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `body_preview` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `metadata_json` json NULL,
  `evidence_json` json NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_afe_jf`(`job_file_id` ASC) USING BTREE,
  CONSTRAINT `fk_afe_jf` FOREIGN KEY (`job_file_id`) REFERENCES `agent_job_file` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for agent_job
-- ----------------------------
DROP TABLE IF EXISTS `agent_job`;
CREATE TABLE `agent_job`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `upload_folder_id` bigint NOT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `progress` int NOT NULL DEFAULT 0,
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `retry_count` int NOT NULL DEFAULT 0,
  `started_at` timestamp(3) NULL DEFAULT NULL,
  `finished_at` timestamp(3) NULL DEFAULT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `version` bigint NOT NULL DEFAULT 0,
  `current_step` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `step_detail` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `organized_prefix` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `zip_object_key` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_agent_job_user`(`user_id` ASC) USING BTREE,
  INDEX `fk_agent_job_folder`(`upload_folder_id` ASC) USING BTREE,
  CONSTRAINT `fk_agent_job_folder` FOREIGN KEY (`upload_folder_id`) REFERENCES `upload_folder` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_agent_job_user` FOREIGN KEY (`user_id`) REFERENCES `tap_user` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `chk_agent_job_status` CHECK (`status` in (_utf8mb4'PENDING',_utf8mb4'RUNNING',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED',_utf8mb4'CANCELLED'))
) ENGINE = InnoDB AUTO_INCREMENT = 7 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for agent_job_file
-- ----------------------------
DROP TABLE IF EXISTS `agent_job_file`;
CREATE TABLE `agent_job_file`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `job_id` bigint NOT NULL,
  `document_id` bigint NOT NULL,
  `object_key` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `filename` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `content_type` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `size_bytes` bigint NOT NULL DEFAULT 0,
  `sha256` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `ext` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_ajf_doc`(`document_id` ASC) USING BTREE,
  INDEX `idx_ajf_job`(`job_id` ASC) USING BTREE,
  CONSTRAINT `fk_ajf_doc` FOREIGN KEY (`document_id`) REFERENCES `document` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_ajf_job` FOREIGN KEY (`job_id`) REFERENCES `agent_job` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 20 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for agent_organize_plan
-- ----------------------------
DROP TABLE IF EXISTS `agent_organize_plan`;
CREATE TABLE `agent_organize_plan`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `job_id` bigint NOT NULL,
  `job_file_id` bigint NOT NULL,
  `source_object_key` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_object_key` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `new_filename` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_folder` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `doc_kind` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `topic` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `confidence` double NULL DEFAULT 0,
  `decision_source` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT 'ai',
  `review_flag` tinyint(1) NOT NULL DEFAULT 0,
  `review_reason` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `duplicate_group_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `conflict_resolved` tinyint(1) NOT NULL DEFAULT 0,
  `applied` tinyint(1) NOT NULL DEFAULT 0,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_aop_jf`(`job_file_id` ASC) USING BTREE,
  INDEX `idx_aop_job`(`job_id` ASC) USING BTREE,
  CONSTRAINT `fk_aop_jf` FOREIGN KEY (`job_file_id`) REFERENCES `agent_job_file` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_aop_job` FOREIGN KEY (`job_id`) REFERENCES `agent_job` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for agent_result
-- ----------------------------
DROP TABLE IF EXISTS `agent_result`;
CREATE TABLE `agent_result`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `job_id` bigint NOT NULL,
  `topic` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `tags_json` json NULL,
  `summary` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `translation_link` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `result_json` json NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_agent_result_job_id`(`job_id` ASC) USING BTREE,
  CONSTRAINT `fk_agent_result_job` FOREIGN KEY (`job_id`) REFERENCES `agent_job` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for ai_pta_suggested_url
-- ----------------------------
DROP TABLE IF EXISTS `ai_pta_suggested_url`;
CREATE TABLE `ai_pta_suggested_url`  (
  `student_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `PTAURLS` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  PRIMARY KEY (`student_name`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for ai_remark_organization
-- ----------------------------
DROP TABLE IF EXISTS `ai_remark_organization`;
CREATE TABLE `ai_remark_organization`  (
  `student_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `student_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `experiment_id` int NOT NULL,
  `remark_organization` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  PRIMARY KEY (`student_id`, `experiment_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for ai_remarks
-- ----------------------------
DROP TABLE IF EXISTS `ai_remarks`;
CREATE TABLE `ai_remarks`  (
  `student_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `student_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `experiment_id` int NOT NULL,
  `experiment_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `airemark` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  PRIMARY KEY (`student_id`, `experiment_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for ai_submission_analysis
-- ----------------------------
DROP TABLE IF EXISTS `ai_submission_analysis`;
CREATE TABLE `ai_submission_analysis`  (
  `experiment_id` int NOT NULL,
  `experiment_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `AI_analysis` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  PRIMARY KEY (`experiment_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for ai_suggested_problems
-- ----------------------------
DROP TABLE IF EXISTS `ai_suggested_problems`;
CREATE TABLE `ai_suggested_problems`  (
  `student_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `student_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `experiment_id` int NOT NULL,
  `suggested_problems` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  PRIMARY KEY (`student_id`, `experiment_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for artifact
-- ----------------------------
DROP TABLE IF EXISTS `artifact`;
CREATE TABLE `artifact`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `owner_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `owner_id` bigint NOT NULL,
  `artifact_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `storage_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'OBJECT',
  `object_key` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `text_content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `content_hash` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `mime_type` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `file_name` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `size_bytes` bigint NULL DEFAULT NULL,
  `source_system` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `source_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `metadata_json` json NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_artifact_source`(`source_system` ASC, `source_key` ASC) USING BTREE,
  INDEX `idx_artifact_owner`(`owner_type` ASC, `owner_id` ASC) USING BTREE,
  INDEX `idx_artifact_type`(`artifact_type` ASC) USING BTREE,
  INDEX `idx_artifact_hash`(`content_hash` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1047672 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for assignment_offering
-- ----------------------------
DROP TABLE IF EXISTS `assignment_offering`;
CREATE TABLE `assignment_offering`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `template_id` bigint NOT NULL,
  `class_id` bigint NOT NULL,
  `teacher_id` bigint NOT NULL,
  `seq_no` int NULL DEFAULT NULL,
  `title_override` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `published_at` timestamp(3) NULL DEFAULT NULL,
  `deadline_at` timestamp(3) NULL DEFAULT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'DRAFT',
  `source_system` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `source_offering_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `pta_problem_set_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_assignment_offering_source`(`source_system` ASC, `source_offering_key` ASC) USING BTREE,
  INDEX `idx_assignment_offering_pta_problem_set_id`(`pta_problem_set_id` ASC) USING BTREE,
  INDEX `idx_assignment_offering_class`(`class_id` ASC) USING BTREE,
  INDEX `idx_assignment_offering_teacher`(`teacher_id` ASC) USING BTREE,
  INDEX `idx_assignment_offering_template`(`template_id` ASC) USING BTREE,
  INDEX `idx_assignment_offering_deadline`(`deadline_at` ASC) USING BTREE,
  CONSTRAINT `fk_assignment_offering_class` FOREIGN KEY (`class_id`) REFERENCES `teaching_class` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_assignment_offering_teacher` FOREIGN KEY (`teacher_id`) REFERENCES `tap_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_assignment_offering_template` FOREIGN KEY (`template_id`) REFERENCES `assignment_template` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `chk_assignment_offering_source_pair` CHECK (((`source_system` is null) and (`source_offering_key` is null)) or ((`source_system` is not null) and (`source_offering_key` is not null))),
  CONSTRAINT `chk_assignment_offering_status` CHECK (`status` in (_utf8mb4'DRAFT',_utf8mb4'PUBLISHED',_utf8mb4'CLOSED',_utf8mb4'ARCHIVED'))
) ENGINE = InnoDB AUTO_INCREMENT = 131 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for assignment_problem
-- ----------------------------
DROP TABLE IF EXISTS `assignment_problem`;
CREATE TABLE `assignment_problem`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `offering_id` bigint NOT NULL,
  `problem_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `source_problem_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `title` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `statement_md` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `max_score` decimal(8, 2) NULL DEFAULT NULL,
  `sort_order` int NOT NULL DEFAULT 0,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ACTIVE',
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_assignment_problem_no`(`offering_id` ASC, `problem_no` ASC) USING BTREE,
  UNIQUE INDEX `uq_assignment_problem_id_offering`(`id` ASC, `offering_id` ASC) USING BTREE,
  UNIQUE INDEX `uq_assignment_problem_source`(`offering_id` ASC, `source_problem_id` ASC) USING BTREE,
  CONSTRAINT `fk_assignment_problem_offering` FOREIGN KEY (`offering_id`) REFERENCES `assignment_offering` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `chk_assignment_problem_status` CHECK (`status` in (_utf8mb4'ACTIVE',_utf8mb4'REMOVED'))
) ENGINE = InnoDB AUTO_INCREMENT = 14601 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for assignment_template
-- ----------------------------
DROP TABLE IF EXISTS `assignment_template`;
CREATE TABLE `assignment_template`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `category` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `language` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `description_md` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `source_system` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `source_template_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ACTIVE',
  `created_by` bigint NULL DEFAULT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_assignment_template_source`(`source_system` ASC, `source_template_key` ASC) USING BTREE,
  INDEX `fk_assignment_template_creator`(`created_by` ASC) USING BTREE,
  CONSTRAINT `fk_assignment_template_creator` FOREIGN KEY (`created_by`) REFERENCES `tap_user` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `chk_assignment_template_source_pair` CHECK (((`source_system` is null) and (`source_template_key` is null)) or ((`source_system` is not null) and (`source_template_key` is not null))),
  CONSTRAINT `chk_assignment_template_status` CHECK (`status` in (_utf8mb4'ACTIVE',_utf8mb4'ARCHIVED'))
) ENGINE = InnoDB AUTO_INCREMENT = 166 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for audit_event
-- ----------------------------
DROP TABLE IF EXISTS `audit_event`;
CREATE TABLE `audit_event`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NULL DEFAULT NULL,
  `role` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `action` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `target_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `metadata_json` json NULL,
  `ip` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `user_agent` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `trace_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_audit_event_user`(`user_id` ASC) USING BTREE,
  CONSTRAINT `fk_audit_event_user` FOREIGN KEY (`user_id`) REFERENCES `tap_user` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 59 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for chapter_summary
-- ----------------------------
DROP TABLE IF EXISTS `chapter_summary`;
CREATE TABLE `chapter_summary`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_id` bigint NOT NULL,
  `course_space_id` bigint NOT NULL,
  `chapter_path` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `summary_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `level` int NOT NULL DEFAULT 1,
  `parent_chapter_id` bigint NULL DEFAULT NULL,
  `milvus_id` bigint NULL DEFAULT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_chsum_parent`(`parent_chapter_id` ASC) USING BTREE,
  INDEX `idx_chsum_doc`(`doc_id` ASC) USING BTREE,
  INDEX `idx_chsum_course`(`course_space_id` ASC) USING BTREE,
  CONSTRAINT `fk_chsum_cs` FOREIGN KEY (`course_space_id`) REFERENCES `course_space` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_chsum_doc` FOREIGN KEY (`doc_id`) REFERENCES `document` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_chsum_parent` FOREIGN KEY (`parent_chapter_id`) REFERENCES `chapter_summary` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 21 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for class_member
-- ----------------------------
DROP TABLE IF EXISTS `class_member`;
CREATE TABLE `class_member`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `class_id` bigint NOT NULL,
  `student_id` bigint NOT NULL,
  `member_status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ACTIVE',
  `joined_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `left_at` timestamp(3) NULL DEFAULT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_class_member`(`class_id` ASC, `student_id` ASC) USING BTREE,
  INDEX `idx_class_member_student`(`student_id` ASC) USING BTREE,
  INDEX `idx_class_member_status`(`member_status` ASC) USING BTREE,
  CONSTRAINT `fk_class_member_class` FOREIGN KEY (`class_id`) REFERENCES `teaching_class` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_class_member_student` FOREIGN KEY (`student_id`) REFERENCES `student_profile` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `chk_class_member_status` CHECK (`member_status` in (_utf8mb4'ACTIVE',_utf8mb4'LEFT',_utf8mb4'SUSPENDED'))
) ENGINE = InnoDB AUTO_INCREMENT = 137891 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for class_student
-- ----------------------------
DROP TABLE IF EXISTS `class_student`;
CREATE TABLE `class_student`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `class_id` bigint NOT NULL,
  `student_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '学生姓名',
  `student_num` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '学号',
  `user_id` bigint NULL DEFAULT NULL COMMENT '关联 tap_user（可选）',
  `joined_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_class_student`(`class_id` ASC, `student_num` ASC) USING BTREE,
  INDEX `idx_class_student_class`(`class_id` ASC) USING BTREE,
  INDEX `idx_class_student_user`(`user_id` ASC) USING BTREE,
  CONSTRAINT `fk_class_student_class` FOREIGN KEY (`class_id`) REFERENCES `teaching_class` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 432 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for course
-- ----------------------------
DROP TABLE IF EXISTS `course`;
CREATE TABLE `course`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `course_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `subject` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ACTIVE',
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_course_code`(`course_code` ASC) USING BTREE,
  CONSTRAINT `chk_course_status` CHECK (`status` in (_utf8mb4'ACTIVE',_utf8mb4'ARCHIVED'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for course_space
-- ----------------------------
DROP TABLE IF EXISTS `course_space`;
CREATE TABLE `course_space`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `teacher_id` bigint NOT NULL,
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `term` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `course_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `default_mode` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'strict',
  `allow_web_search` tinyint(1) NOT NULL DEFAULT 0,
  `require_citation` tinyint(1) NOT NULL DEFAULT 1,
  `doc_visibility` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'private',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_cs_teacher`(`teacher_id` ASC) USING BTREE,
  CONSTRAINT `fk_cs_teacher` FOREIGN KEY (`teacher_id`) REFERENCES `tap_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for course_space_class
-- ----------------------------
DROP TABLE IF EXISTS `course_space_class`;
CREATE TABLE `course_space_class`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `course_space_id` bigint NOT NULL,
  `class_id` bigint NOT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_course_space_class`(`course_space_id` ASC, `class_id` ASC) USING BTREE,
  INDEX `idx_course_space_class_space`(`course_space_id` ASC) USING BTREE,
  INDEX `idx_course_space_class_class`(`class_id` ASC) USING BTREE,
  CONSTRAINT `fk_course_space_class_class` FOREIGN KEY (`class_id`) REFERENCES `teaching_class` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_course_space_class_space` FOREIGN KEY (`course_space_id`) REFERENCES `course_space` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for course_space_document
-- ----------------------------
DROP TABLE IF EXISTS `course_space_document`;
CREATE TABLE `course_space_document`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `course_space_id` bigint NOT NULL,
  `document_id` bigint NOT NULL,
  `doc_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT 'textbook',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `chunk_count` int NOT NULL DEFAULT 0,
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_csd`(`course_space_id` ASC, `document_id` ASC) USING BTREE,
  INDEX `fk_csd_doc`(`document_id` ASC) USING BTREE,
  CONSTRAINT `fk_csd_cs` FOREIGN KEY (`course_space_id`) REFERENCES `course_space` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_csd_doc` FOREIGN KEY (`document_id`) REFERENCES `document` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `chk_csd_status` CHECK (`status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING',_utf8mb4'READY',_utf8mb4'FAILED'))
) ENGINE = InnoDB AUTO_INCREMENT = 15 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for doc_chunk
-- ----------------------------
DROP TABLE IF EXISTS `doc_chunk`;
CREATE TABLE `doc_chunk`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `document_id` bigint NOT NULL,
  `course_space_id` bigint NOT NULL,
  `chunk_type` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `parent_id` bigint NULL DEFAULT NULL,
  `chunk_index` int NOT NULL DEFAULT 0,
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `chapter_path` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `page_range` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `token_count` int NOT NULL DEFAULT 0,
  `milvus_id` bigint NULL DEFAULT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_dc_doc`(`document_id` ASC) USING BTREE,
  INDEX `idx_dc_parent`(`parent_id` ASC) USING BTREE,
  INDEX `idx_dc_cs`(`course_space_id` ASC, `chunk_type` ASC) USING BTREE,
  CONSTRAINT `fk_dc_cs` FOREIGN KEY (`course_space_id`) REFERENCES `course_space` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_dc_doc` FOREIGN KEY (`document_id`) REFERENCES `document` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_dc_parent` FOREIGN KEY (`parent_id`) REFERENCES `doc_chunk` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `chk_dc_type` CHECK (`chunk_type` in (_utf8mb4'parent',_utf8mb4'child'))
) ENGINE = InnoDB AUTO_INCREMENT = 156 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for doc_chunk_annotation
-- ----------------------------
DROP TABLE IF EXISTS `doc_chunk_annotation`;
CREATE TABLE `doc_chunk_annotation`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `chunk_id` bigint NOT NULL,
  `annotation_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `note` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `teacher_id` bigint NOT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_dca_teacher`(`teacher_id` ASC) USING BTREE,
  INDEX `idx_dca_chunk`(`chunk_id` ASC) USING BTREE,
  CONSTRAINT `fk_dca_chunk` FOREIGN KEY (`chunk_id`) REFERENCES `doc_chunk` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_dca_teacher` FOREIGN KEY (`teacher_id`) REFERENCES `tap_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `chk_dca_type` CHECK (`annotation_type` in (_utf8mb4'important',_utf8mb4'error_prone'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for document
-- ----------------------------
DROP TABLE IF EXISTS `document`;
CREATE TABLE `document`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `upload_folder_id` bigint NOT NULL,
  `original_path` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `filename` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `content_type` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `size_bytes` bigint NOT NULL,
  `sha256` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `language` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `extracted_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `extracted_text_key` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `extracted_text_truncated` tinyint(1) NOT NULL DEFAULT 0,
  `object_key` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_document_sha256`(`sha256` ASC) USING BTREE,
  INDEX `idx_document_upload_folder_id`(`upload_folder_id` ASC) USING BTREE,
  INDEX `idx_document_user_id`(`user_id` ASC) USING BTREE,
  CONSTRAINT `fk_document_folder` FOREIGN KEY (`upload_folder_id`) REFERENCES `upload_folder` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_document_user` FOREIGN KEY (`user_id`) REFERENCES `tap_user` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 311 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for evidence_block
-- ----------------------------
DROP TABLE IF EXISTS `evidence_block`;
CREATE TABLE `evidence_block`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `evidence_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `kind` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `page` int NULL DEFAULT NULL,
  `bbox_json` json NULL,
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `confidence` decimal(4, 3) NULL DEFAULT NULL,
  `image_key` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `metadata_json` json NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_evidence_block_evidence_id`(`evidence_id` ASC) USING BTREE,
  INDEX `idx_evidence_block_submission`(`submission_id` ASC) USING BTREE,
  CONSTRAINT `fk_evidence_block_submission` FOREIGN KEY (`submission_id`) REFERENCES `grading_submission` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `chk_evidence_block_kind` CHECK (`kind` in (_utf8mb4'text',_utf8mb4'ocr',_utf8mb4'vlm',_utf8mb4'vlm_failed'))
) ENGINE = InnoDB AUTO_INCREMENT = 2085 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for experiment
-- ----------------------------
DROP TABLE IF EXISTS `experiment`;
CREATE TABLE `experiment`  (
  `experiment_id` int NOT NULL AUTO_INCREMENT,
  `num` int NULL DEFAULT NULL COMMENT '实验编号',
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '实验名称',
  `deadline` datetime NULL DEFAULT NULL COMMENT '截止时间',
  `describe` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '实验描述',
  `requirements` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '实验要求',
  `topic_sum` int NULL DEFAULT 0 COMMENT '题目总数',
  `teacher_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '教师ID',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`experiment_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 94 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for external_identity_binding
-- ----------------------------
DROP TABLE IF EXISTS `external_identity_binding`;
CREATE TABLE `external_identity_binding`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `entity_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `entity_id` bigint NOT NULL,
  `source_system` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `external_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `binding_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `confidence` decimal(5, 4) NOT NULL DEFAULT 1.0000,
  `is_active` tinyint(1) NOT NULL DEFAULT 1,
  `valid_from` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `valid_to` timestamp(3) NULL DEFAULT NULL,
  `metadata_json` json NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `active_external_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci GENERATED ALWAYS AS ((case when `is_active` then `external_id` else NULL end)) STORED NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_external_identity_active_external`(`source_system` ASC, `entity_type` ASC, `active_external_id` ASC) USING BTREE,
  INDEX `idx_external_identity_entity`(`entity_type` ASC, `entity_id` ASC) USING BTREE,
  INDEX `idx_external_identity_source_external`(`source_system` ASC, `entity_type` ASC, `external_id` ASC, `is_active` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2551 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for flyway_schema_history
-- ----------------------------
DROP TABLE IF EXISTS `flyway_schema_history`;
CREATE TABLE `flyway_schema_history`  (
  `installed_rank` int NOT NULL,
  `version` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `description` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `script` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `checksum` int NULL DEFAULT NULL,
  `installed_by` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `installed_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `execution_time` int NOT NULL,
  `success` tinyint(1) NOT NULL,
  PRIMARY KEY (`installed_rank`) USING BTREE,
  INDEX `flyway_schema_history_s_idx`(`success` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for grading_rubric
-- ----------------------------
DROP TABLE IF EXISTS `grading_rubric`;
CREATE TABLE `grading_rubric`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `teacher_id` bigint NOT NULL,
  `name` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `subject` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `custom_prompt` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_grading_rubric_teacher`(`teacher_id` ASC) USING BTREE,
  CONSTRAINT `fk_grading_rubric_teacher` FOREIGN KEY (`teacher_id`) REFERENCES `tap_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 13 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for grading_submission
-- ----------------------------
DROP TABLE IF EXISTS `grading_submission`;
CREATE TABLE `grading_submission`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `student_id` bigint NULL DEFAULT NULL,
  `student_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `class_name` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `student_no` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `pdf_object_key` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `original_filename` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `status` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `total_score` decimal(6, 2) NULL DEFAULT NULL,
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `final_review_comment` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_grading_submission_task`(`task_id` ASC) USING BTREE,
  INDEX `idx_grading_submission_student`(`student_id` ASC) USING BTREE,
  CONSTRAINT `fk_grading_submission_student_profile` FOREIGN KEY (`student_id`) REFERENCES `student_profile` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_grading_submission_task` FOREIGN KEY (`task_id`) REFERENCES `grading_task` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `chk_grading_submission_status` CHECK (`status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING',_utf8mb4'SCORED',_utf8mb4'FAILED',_utf8mb4'NEED_MORE_EVIDENCE'))
) ENGINE = InnoDB AUTO_INCREMENT = 47 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for grading_task
-- ----------------------------
DROP TABLE IF EXISTS `grading_task`;
CREATE TABLE `grading_task`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `teacher_id` bigint NOT NULL,
  `experiment_id` bigint NULL DEFAULT NULL,
  `assignment_offering_id` bigint NULL DEFAULT NULL COMMENT 'Link to assignment_offering',
  `class_id` bigint NULL DEFAULT NULL,
  `teacher_signature` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `rubric_id` bigint NOT NULL,
  `score_range_min` decimal(5, 1) NULL DEFAULT NULL,
  `score_range_max` decimal(5, 1) NULL DEFAULT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `total_count` int NOT NULL DEFAULT 0,
  `completed_count` int NOT NULL DEFAULT 0,
  `failed_count` int NOT NULL DEFAULT 0,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_grading_task_rubric`(`rubric_id` ASC) USING BTREE,
  INDEX `idx_grading_task_teacher`(`teacher_id` ASC) USING BTREE,
  INDEX `idx_grading_task_status`(`status` ASC) USING BTREE,
  INDEX `idx_grading_task_assignment_offering`(`assignment_offering_id` ASC) USING BTREE,
  CONSTRAINT `fk_grading_task_assignment_offering` FOREIGN KEY (`assignment_offering_id`) REFERENCES `assignment_offering` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_grading_task_rubric` FOREIGN KEY (`rubric_id`) REFERENCES `grading_rubric` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_grading_task_teacher` FOREIGN KEY (`teacher_id`) REFERENCES `tap_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `chk_grading_task_status` CHECK (`status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING',_utf8mb4'COMPLETED',_utf8mb4'FAILED'))
) ENGINE = InnoDB AUTO_INCREMENT = 51 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for grading_trace
-- ----------------------------
DROP TABLE IF EXISTS `grading_trace`;
CREATE TABLE `grading_trace`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `step` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `duration_ms` bigint NULL DEFAULT NULL,
  `model_used` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `input_tokens` int NULL DEFAULT NULL,
  `output_tokens` int NULL DEFAULT NULL,
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `metadata_json` json NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_grading_trace_submission`(`submission_id` ASC) USING BTREE,
  CONSTRAINT `fk_grading_trace_submission` FOREIGN KEY (`submission_id`) REFERENCES `grading_submission` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 3659 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for import_job
-- ----------------------------
DROP TABLE IF EXISTS `import_job`;
CREATE TABLE `import_job`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `source_system` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `job_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `class_id` bigint NULL DEFAULT NULL,
  `trigger_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'MANUAL',
  `triggered_by` bigint NULL DEFAULT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'PENDING',
  `started_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `finished_at` timestamp(3) NULL DEFAULT NULL,
  `summary_json` json NULL,
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_import_job_user`(`triggered_by` ASC) USING BTREE,
  INDEX `idx_import_job_source_status`(`source_system` ASC, `status` ASC) USING BTREE,
  INDEX `idx_import_job_class`(`class_id` ASC) USING BTREE,
  CONSTRAINT `fk_import_job_class` FOREIGN KEY (`class_id`) REFERENCES `teaching_class` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_import_job_user` FOREIGN KEY (`triggered_by`) REFERENCES `tap_user` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `chk_import_job_status` CHECK (`status` in (_utf8mb4'PENDING',_utf8mb4'RUNNING',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED',_utf8mb4'CANCELLED'))
) ENGINE = InnoDB AUTO_INCREMENT = 2602 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for import_source_file
-- ----------------------------
DROP TABLE IF EXISTS `import_source_file`;
CREATE TABLE `import_source_file`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `import_job_id` bigint NOT NULL,
  `file_role` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `relative_path` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `sha256` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `size_bytes` bigint NULL DEFAULT NULL,
  `parse_status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'PENDING',
  `parsed_at` timestamp(3) NULL DEFAULT NULL,
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `metadata_json` json NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_import_source_file`(`import_job_id` ASC, `relative_path` ASC) USING BTREE,
  INDEX `idx_import_source_file_sha256`(`sha256` ASC) USING BTREE,
  CONSTRAINT `fk_import_source_file_job` FOREIGN KEY (`import_job_id`) REFERENCES `import_job` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `chk_import_source_file_status` CHECK (`parse_status` in (_utf8mb4'PENDING',_utf8mb4'PARSED',_utf8mb4'FAILED',_utf8mb4'SKIPPED'))
) ENGINE = InnoDB AUTO_INCREMENT = 11908 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for leetcode_problem_bank
-- ----------------------------
DROP TABLE IF EXISTS `leetcode_problem_bank`;
CREATE TABLE `leetcode_problem_bank`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `source_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '如 id:1234 / title:xxx',
  `problem_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '原始题号，如 LCR 002',
  `numeric_id` int NULL DEFAULT NULL COMMENT '纯数字题号',
  `title_main` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `title_alt` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `problem_text` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `solution_text` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `source_url` varchar(600) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `difficulty` enum('Easy','Medium','Hard','Unknown') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'Unknown',
  `estimated_minutes` int NOT NULL DEFAULT 30,
  `quality_score` decimal(5, 4) NOT NULL DEFAULT 0.8000,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_source_key`(`source_key` ASC) USING BTREE,
  INDEX `idx_numeric_id`(`numeric_id` ASC) USING BTREE,
  INDEX `idx_difficulty`(`difficulty` ASC) USING BTREE,
  INDEX `idx_quality`(`quality_score` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1557 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'LeetCode题库主表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for leetcode_problem_tag
-- ----------------------------
DROP TABLE IF EXISTS `leetcode_problem_tag`;
CREATE TABLE `leetcode_problem_tag`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `problem_id` bigint NOT NULL,
  `tag_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '标签类型：algorithm, difficulty, series, topic',
  `tag_value` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '标签值',
  `confidence` decimal(3, 2) NULL DEFAULT 0.80 COMMENT '置信度',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_problem_tag`(`problem_id` ASC, `tag_type` ASC) USING BTREE,
  INDEX `idx_tag_value`(`tag_value` ASC) USING BTREE,
  INDEX `idx_tag_type_value`(`tag_type` ASC, `tag_value` ASC) USING BTREE,
  CONSTRAINT `leetcode_problem_tag_ibfk_1` FOREIGN KEY (`problem_id`) REFERENCES `leetcode_problem_bank` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 2121 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'LeetCode题目标签表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for leetcode_recommend_feedback
-- ----------------------------
DROP TABLE IF EXISTS `leetcode_recommend_feedback`;
CREATE TABLE `leetcode_recommend_feedback`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `request_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `student_id` int NOT NULL,
  `problem_id` bigint NOT NULL,
  `session_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `action` enum('exposure','click','start','complete','skip','dislike') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `action_at` datetime NOT NULL,
  `extra_json` json NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_student_time`(`student_id` ASC, `action_at` ASC) USING BTREE,
  INDEX `idx_request`(`request_id` ASC) USING BTREE,
  INDEX `idx_problem_time`(`problem_id` ASC, `action_at` ASC) USING BTREE,
  CONSTRAINT `fk_recommend_feedback_problem` FOREIGN KEY (`problem_id`) REFERENCES `leetcode_problem_bank` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 392 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '反馈行为表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for leetcode_recommend_item
-- ----------------------------
DROP TABLE IF EXISTS `leetcode_recommend_item`;
CREATE TABLE `leetcode_recommend_item`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `request_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `student_id` int NOT NULL,
  `rank_no` int NOT NULL,
  `problem_id` bigint NOT NULL,
  `score_total` decimal(8, 4) NOT NULL,
  `score_need_match` decimal(8, 4) NOT NULL,
  `score_difficulty_fit` decimal(8, 4) NOT NULL,
  `score_success_prob` decimal(8, 4) NOT NULL,
  `score_novelty` decimal(8, 4) NOT NULL,
  `score_quality` decimal(8, 4) NOT NULL,
  `reason_text` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `reason_json` json NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_request_rank`(`request_id` ASC, `rank_no` ASC) USING BTREE,
  INDEX `idx_request`(`request_id` ASC) USING BTREE,
  INDEX `idx_student_created`(`student_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `fk_recommend_item_problem`(`problem_id` ASC) USING BTREE,
  CONSTRAINT `fk_recommend_item_problem` FOREIGN KEY (`problem_id`) REFERENCES `leetcode_problem_bank` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '推荐结果表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for leetcode_recommend_request
-- ----------------------------
DROP TABLE IF EXISTS `leetcode_recommend_request`;
CREATE TABLE `leetcode_recommend_request`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `request_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `student_id` int NOT NULL,
  `scene` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'default',
  `request_limit` int NOT NULL DEFAULT 20,
  `status` enum('pending','completed','failed') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'pending',
  `error_message` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `finished_at` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_request_id`(`request_id` ASC) USING BTREE,
  INDEX `idx_student_created`(`student_id` ASC, `created_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '推荐请求表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for leetcode_recommendation_feedback
-- ----------------------------
DROP TABLE IF EXISTS `leetcode_recommendation_feedback`;
CREATE TABLE `leetcode_recommendation_feedback`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `student_id` int NOT NULL,
  `problem_id` bigint NOT NULL,
  `action` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'click, start, complete, skip, dislike',
  `session_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `action_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_student_action_time`(`student_id` ASC, `action` ASC, `action_time` DESC) USING BTREE,
  INDEX `idx_request_id`(`request_id` ASC) USING BTREE,
  INDEX `idx_problem_action`(`problem_id` ASC, `action` ASC) USING BTREE,
  CONSTRAINT `leetcode_recommendation_feedback_ibfk_1` FOREIGN KEY (`problem_id`) REFERENCES `leetcode_problem_bank` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'LeetCode推荐反馈表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for leetcode_recommendation_result
-- ----------------------------
DROP TABLE IF EXISTS `leetcode_recommendation_result`;
CREATE TABLE `leetcode_recommendation_result`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `student_id` int NOT NULL,
  `experiment_id` int NULL DEFAULT NULL,
  `problem_id` bigint NOT NULL,
  `rank_index` int NOT NULL COMMENT '推荐排序位置',
  `score_total` decimal(6, 4) NULL DEFAULT 0.0000 COMMENT '总分',
  `score_weakness_match` decimal(6, 4) NULL DEFAULT 0.0000 COMMENT '薄弱点匹配分',
  `score_difficulty_match` decimal(6, 4) NULL DEFAULT 0.0000 COMMENT '难度匹配分',
  `score_novelty` decimal(6, 4) NULL DEFAULT 0.0000 COMMENT '新颖性分',
  `score_diversity` decimal(6, 4) NULL DEFAULT 0.0000 COMMENT '多样性分',
  `score_quality` decimal(6, 4) NULL DEFAULT 0.0000 COMMENT '质量分',
  `reason_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '推荐理由',
  `reason_json` json NULL COMMENT '推荐理由详细信息',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_request_rank`(`request_id` ASC, `rank_index` ASC) USING BTREE,
  INDEX `idx_student_time`(`student_id` ASC, `created_at` DESC) USING BTREE,
  INDEX `idx_experiment_time`(`experiment_id` ASC, `created_at` DESC) USING BTREE,
  INDEX `idx_problem_id`(`problem_id` ASC) USING BTREE,
  CONSTRAINT `leetcode_recommendation_result_ibfk_1` FOREIGN KEY (`problem_id`) REFERENCES `leetcode_problem_bank` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'LeetCode推荐结果表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for leetcode_solutions
-- ----------------------------
DROP TABLE IF EXISTS `leetcode_solutions`;
CREATE TABLE `leetcode_solutions`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `source_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '题目标识行（题号+标题）',
  `problem_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '题号编码（如2528/LCR 002/面试题 16.19）',
  `numeric_id` int NULL DEFAULT NULL COMMENT '纯数字题号（可空）',
  `title_main` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '主标题',
  `title_alt` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '副标题',
  `problem_text` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '题面文本（input）',
  `solution_text` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '题解文本（output）',
  `problem_url` varchar(600) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'leetcode题解链接',
  `source_dataset` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '来源数据集文件',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_source_key`(`source_key` ASC) USING BTREE,
  INDEX `idx_problem_code`(`problem_code` ASC) USING BTREE,
  INDEX `idx_numeric_id`(`numeric_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 507 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for legacy_experiment_offering_map
-- ----------------------------
DROP TABLE IF EXISTS `legacy_experiment_offering_map`;
CREATE TABLE `legacy_experiment_offering_map`  (
  `experiment_id` bigint NOT NULL,
  `class_id` bigint NOT NULL,
  `teacher_id` bigint NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `notes` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  PRIMARY KEY (`experiment_id`) USING BTREE,
  INDEX `fk_legacy_experiment_offering_map_class`(`class_id` ASC) USING BTREE,
  INDEX `fk_legacy_experiment_offering_map_teacher`(`teacher_id` ASC) USING BTREE,
  CONSTRAINT `fk_legacy_experiment_offering_map_class` FOREIGN KEY (`class_id`) REFERENCES `teaching_class` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_legacy_experiment_offering_map_teacher` FOREIGN KEY (`teacher_id`) REFERENCES `tap_user` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for library_item
-- ----------------------------
DROP TABLE IF EXISTS `library_item`;
CREATE TABLE `library_item`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `paper_id` bigint NOT NULL,
  `saved_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `downloaded_at` timestamp(3) NULL DEFAULT NULL,
  `note` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_library_item_user_paper`(`user_id` ASC, `paper_id` ASC) USING BTREE,
  INDEX `fk_library_item_paper`(`paper_id` ASC) USING BTREE,
  CONSTRAINT `fk_library_item_paper` FOREIGN KEY (`paper_id`) REFERENCES `paper` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_library_item_user` FOREIGN KEY (`user_id`) REFERENCES `tap_user` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for paper
-- ----------------------------
DROP TABLE IF EXISTS `paper`;
CREATE TABLE `paper`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `arxiv_id` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `abstract_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `pdf_url` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `published_at` timestamp(3) NULL DEFAULT NULL,
  `updated_at` timestamp(3) NULL DEFAULT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_paper_arxiv_id`(`arxiv_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 7 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for paper_author
-- ----------------------------
DROP TABLE IF EXISTS `paper_author`;
CREATE TABLE `paper_author`  (
  `paper_id` bigint NOT NULL,
  `author_name` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`paper_id`, `author_name`) USING BTREE,
  CONSTRAINT `fk_paper_author_paper` FOREIGN KEY (`paper_id`) REFERENCES `paper` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for paper_category
-- ----------------------------
DROP TABLE IF EXISTS `paper_category`;
CREATE TABLE `paper_category`  (
  `paper_id` bigint NOT NULL,
  `category` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`paper_id`, `category`) USING BTREE,
  CONSTRAINT `fk_paper_category_paper` FOREIGN KEY (`paper_id`) REFERENCES `paper` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for plagiarism_check_table
-- ----------------------------
DROP TABLE IF EXISTS `plagiarism_check_table`;
CREATE TABLE `plagiarism_check_table`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `student_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `experiment_id` int NOT NULL,
  `Plagiarism_Rate` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_plagiarism`(`student_id` ASC, `experiment_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for problem_score_detail
-- ----------------------------
DROP TABLE IF EXISTS `problem_score_detail`;
CREATE TABLE `problem_score_detail`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `experiment_id` int NOT NULL COMMENT '实验ID',
  `student_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '学号',
  `student_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '姓名',
  `problem_label` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '题目标号，如 2-1, 7-1',
  `problem_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '题目类型，如 单选题、编程题',
  `max_score` decimal(8, 2) NULL DEFAULT 0.00 COMMENT '该题满分',
  `actual_score` decimal(8, 2) NULL DEFAULT 0.00 COMMENT '实际得分',
  `total_score` decimal(8, 2) NULL DEFAULT 0.00 COMMENT '总分',
  `ranking` int NULL DEFAULT 0 COMMENT '排名',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_psd`(`experiment_id` ASC, `student_id` ASC, `problem_label` ASC) USING BTREE,
  INDEX `idx_psd_exp`(`experiment_id` ASC) USING BTREE,
  INDEX `idx_psd_student`(`student_id` ASC) USING BTREE,
  INDEX `idx_psd_label`(`problem_label` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1540795 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '题目得分明细' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for problems_sets
-- ----------------------------
DROP TABLE IF EXISTS `problems_sets`;
CREATE TABLE `problems_sets`  (
  `experiment_id` int NOT NULL,
  `experiment_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `problem` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  PRIMARY KEY (`experiment_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for profile_ai_feedback
-- ----------------------------
DROP TABLE IF EXISTS `profile_ai_feedback`;
CREATE TABLE `profile_ai_feedback`  (
  `student_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `feedback` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `profile_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`student_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for pta_api_submission_row
-- ----------------------------
DROP TABLE IF EXISTS `pta_api_submission_row`;
CREATE TABLE `pta_api_submission_row`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `import_job_id` bigint NULL DEFAULT NULL,
  `offering_id` bigint NULL DEFAULT NULL,
  `problem_id` bigint NULL DEFAULT NULL,
  `student_id` bigint NULL DEFAULT NULL,
  `pta_problem_set_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `pta_user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `pta_submission_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `pta_problem_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `problem_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `judge_status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `score` decimal(10, 2) NULL DEFAULT NULL,
  `compiler` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `runtime_ms` int NULL DEFAULT NULL,
  `memory_kb` int NULL DEFAULT NULL,
  `submitted_at` timestamp(3) NULL DEFAULT NULL,
  `raw_json` json NOT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_pta_api_submission_id`(`pta_submission_id` ASC) USING BTREE,
  INDEX `fk_pta_api_submission_job`(`import_job_id` ASC) USING BTREE,
  INDEX `fk_pta_api_submission_offering`(`offering_id` ASC) USING BTREE,
  INDEX `fk_pta_api_submission_problem`(`problem_id` ASC) USING BTREE,
  INDEX `fk_pta_api_submission_student`(`student_id` ASC) USING BTREE,
  INDEX `idx_pta_api_submission_target`(`pta_problem_set_id` ASC, `pta_user_id` ASC, `submitted_at` ASC) USING BTREE,
  INDEX `idx_pta_api_submission_problem`(`pta_problem_id` ASC) USING BTREE,
  INDEX `idx_pta_api_submission_status`(`judge_status` ASC) USING BTREE,
  CONSTRAINT `fk_pta_api_submission_job` FOREIGN KEY (`import_job_id`) REFERENCES `import_job` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_pta_api_submission_offering` FOREIGN KEY (`offering_id`) REFERENCES `assignment_offering` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_pta_api_submission_problem` FOREIGN KEY (`problem_id`) REFERENCES `assignment_problem` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_pta_api_submission_student` FOREIGN KEY (`student_id`) REFERENCES `student_profile` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 43 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for pta_raw_answer_sheet
-- ----------------------------
DROP TABLE IF EXISTS `pta_raw_answer_sheet`;
CREATE TABLE `pta_raw_answer_sheet`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `import_job_id` bigint NOT NULL,
  `source_file_id` bigint NOT NULL,
  `student_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `student_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `problem_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `html_artifact_id` bigint NOT NULL,
  `code_artifact_id` bigint NULL DEFAULT NULL,
  `test_report_artifact_id` bigint NULL DEFAULT NULL,
  `raw_json` json NOT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_pta_raw_answer_sheet_entry`(`source_file_id` ASC, `html_artifact_id` ASC) USING BTREE,
  INDEX `fk_pta_raw_answer_sheet_job`(`import_job_id` ASC) USING BTREE,
  INDEX `fk_pta_raw_answer_sheet_html_artifact`(`html_artifact_id` ASC) USING BTREE,
  INDEX `fk_pta_raw_answer_sheet_code_artifact`(`code_artifact_id` ASC) USING BTREE,
  INDEX `fk_pta_raw_answer_sheet_report_artifact`(`test_report_artifact_id` ASC) USING BTREE,
  INDEX `idx_pta_raw_answer_sheet_student_no`(`student_no` ASC) USING BTREE,
  INDEX `idx_pta_raw_answer_sheet_problem_key`(`problem_key` ASC) USING BTREE,
  CONSTRAINT `fk_pta_raw_answer_sheet_code_artifact` FOREIGN KEY (`code_artifact_id`) REFERENCES `artifact` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_pta_raw_answer_sheet_file` FOREIGN KEY (`source_file_id`) REFERENCES `import_source_file` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_pta_raw_answer_sheet_html_artifact` FOREIGN KEY (`html_artifact_id`) REFERENCES `artifact` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_pta_raw_answer_sheet_job` FOREIGN KEY (`import_job_id`) REFERENCES `import_job` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_pta_raw_answer_sheet_report_artifact` FOREIGN KEY (`test_report_artifact_id`) REFERENCES `artifact` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 123616 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for pta_raw_submission_row
-- ----------------------------
DROP TABLE IF EXISTS `pta_raw_submission_row`;
CREATE TABLE `pta_raw_submission_row`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `import_job_id` bigint NOT NULL,
  `source_file_id` bigint NOT NULL,
  `row_no` int NOT NULL,
  `pta_user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `pta_problem_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `judge_status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `score_text` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `compiler` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `runtime_text` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `memory_text` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `submitted_at_text` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `raw_json` json NOT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_pta_raw_submission_row`(`source_file_id` ASC, `row_no` ASC) USING BTREE,
  INDEX `fk_pta_raw_submission_job`(`import_job_id` ASC) USING BTREE,
  INDEX `idx_pta_raw_submission_pta_user`(`pta_user_id` ASC) USING BTREE,
  INDEX `idx_pta_raw_submission_problem`(`pta_problem_id` ASC) USING BTREE,
  CONSTRAINT `fk_pta_raw_submission_file` FOREIGN KEY (`source_file_id`) REFERENCES `import_source_file` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_pta_raw_submission_job` FOREIGN KEY (`import_job_id`) REFERENCES `import_job` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 436341 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for pta_raw_transcript_row
-- ----------------------------
DROP TABLE IF EXISTS `pta_raw_transcript_row`;
CREATE TABLE `pta_raw_transcript_row`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `import_job_id` bigint NOT NULL,
  `source_file_id` bigint NOT NULL,
  `row_no` int NOT NULL,
  `student_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `student_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `total_score_text` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `ranking_text` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `raw_json` json NOT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_pta_raw_transcript_row`(`source_file_id` ASC, `row_no` ASC) USING BTREE,
  INDEX `fk_pta_raw_transcript_job`(`import_job_id` ASC) USING BTREE,
  INDEX `idx_pta_raw_transcript_student_no`(`student_no` ASC) USING BTREE,
  CONSTRAINT `fk_pta_raw_transcript_file` FOREIGN KEY (`source_file_id`) REFERENCES `import_source_file` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_pta_raw_transcript_job` FOREIGN KEY (`import_job_id`) REFERENCES `import_job` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 135336 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for qa_log
-- ----------------------------
DROP TABLE IF EXISTS `qa_log`;
CREATE TABLE `qa_log`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `student_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `course_space_id` bigint NOT NULL,
  `query` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `retrieved_chunk_ids` json NULL,
  `top1_score` double NULL DEFAULT NULL,
  `answer_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `citations_json` json NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `mode` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT 'strict',
  `coverage_score` double NULL DEFAULT NULL,
  `used_web` tinyint(1) NULL DEFAULT 0,
  `feedback` int NULL DEFAULT NULL,
  `intent_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_qa_cs`(`course_space_id` ASC) USING BTREE,
  CONSTRAINT `fk_qa_cs` FOREIGN KEY (`course_space_id`) REFERENCES `course_space` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 39 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for report_file
-- ----------------------------
DROP TABLE IF EXISTS `report_file`;
CREATE TABLE `report_file`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `submission_id` bigint NULL DEFAULT NULL,
  `file_type` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `object_key` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_report_file_task`(`task_id` ASC) USING BTREE,
  INDEX `fk_report_file_submission`(`submission_id` ASC) USING BTREE,
  CONSTRAINT `fk_report_file_submission` FOREIGN KEY (`submission_id`) REFERENCES `grading_submission` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_report_file_task` FOREIGN KEY (`task_id`) REFERENCES `grading_task` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `chk_report_file_type` CHECK (`file_type` in (_utf8mb4'pdf',_utf8mb4'zip',_utf8mb4'annopdf',_utf8mb4'annodoc'))
) ENGINE = InnoDB AUTO_INCREMENT = 114 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for rubric_dimension
-- ----------------------------
DROP TABLE IF EXISTS `rubric_dimension`;
CREATE TABLE `rubric_dimension`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `rubric_id` bigint NOT NULL,
  `name` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `max_score` decimal(5, 1) NOT NULL,
  `weight` int NOT NULL,
  `sort_order` int NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_rubric_dimension_rubric`(`rubric_id` ASC) USING BTREE,
  CONSTRAINT `fk_rubric_dimension_rubric` FOREIGN KEY (`rubric_id`) REFERENCES `grading_rubric` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 56 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for score
-- ----------------------------
DROP TABLE IF EXISTS `score`;
CREATE TABLE `score`  (
  `score_id` int NOT NULL AUTO_INCREMENT,
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `real_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `experiment_id` int NOT NULL,
  `score` decimal(5, 2) NULL DEFAULT NULL,
  `submit_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `plagiarism_rate` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'pending',
  `serial_number` int NULL DEFAULT 0,
  `num` int NULL DEFAULT 0,
  PRIMARY KEY (`score_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 192665 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for score_item
-- ----------------------------
DROP TABLE IF EXISTS `score_item`;
CREATE TABLE `score_item`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `dimension_id` bigint NOT NULL,
  `score` decimal(5, 1) NULL DEFAULT NULL,
  `max_score` decimal(5, 1) NOT NULL,
  `weight` int NOT NULL,
  `comment` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `evidence_ids_json` json NULL,
  `status` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_score_item_dimension`(`dimension_id` ASC) USING BTREE,
  INDEX `idx_score_item_submission`(`submission_id` ASC) USING BTREE,
  CONSTRAINT `fk_score_item_dimension` FOREIGN KEY (`dimension_id`) REFERENCES `rubric_dimension` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_score_item_submission` FOREIGN KEY (`submission_id`) REFERENCES `grading_submission` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `chk_score_item_status` CHECK (`status` in (_utf8mb4'PENDING',_utf8mb4'SCORED',_utf8mb4'NEED_MORE_EVIDENCE'))
) ENGINE = InnoDB AUTO_INCREMENT = 223 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for score_override
-- ----------------------------
DROP TABLE IF EXISTS `score_override`;
CREATE TABLE `score_override`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `score_item_id` bigint NOT NULL,
  `teacher_id` bigint NOT NULL,
  `old_score` decimal(5, 1) NULL DEFAULT NULL,
  `new_score` decimal(5, 1) NOT NULL,
  `old_comment` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `new_comment` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `reason` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_score_override_score_item`(`score_item_id` ASC) USING BTREE,
  INDEX `fk_score_override_teacher`(`teacher_id` ASC) USING BTREE,
  CONSTRAINT `fk_score_override_score_item` FOREIGN KEY (`score_item_id`) REFERENCES `score_item` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_score_override_teacher` FOREIGN KEY (`teacher_id`) REFERENCES `tap_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for structured_summary
-- ----------------------------
DROP TABLE IF EXISTS `structured_summary`;
CREATE TABLE `structured_summary`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `scope_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `scope_key` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `content_hash` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `provider` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `model` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `summary_json` json NOT NULL,
  `markdown` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `scope_type`(`scope_type` ASC, `scope_key` ASC, `provider` ASC, `model` ASC) USING BTREE,
  UNIQUE INDEX `uq_structured_summary_scope_provider_model`(`scope_type` ASC, `scope_key` ASC, `provider` ASC, `model` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 7 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for student
-- ----------------------------
DROP TABLE IF EXISTS `student`;
CREATE TABLE `student`  (
  `student_id` int NOT NULL COMMENT '学号',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '姓名',
  `class_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '班级',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`student_id`) USING BTREE,
  UNIQUE INDEX `username`(`username` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for student_assignment
-- ----------------------------
DROP TABLE IF EXISTS `student_assignment`;
CREATE TABLE `student_assignment`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `offering_id` bigint NOT NULL,
  `student_id` bigint NOT NULL,
  `submission_status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'NOT_STARTED',
  `first_submit_at` timestamp(3) NULL DEFAULT NULL,
  `last_submit_at` timestamp(3) NULL DEFAULT NULL,
  `accepted_problem_count` int NOT NULL DEFAULT 0,
  `submitted_problem_count` int NOT NULL DEFAULT 0,
  `problem_count` int NOT NULL DEFAULT 0,
  `best_total_score` decimal(10, 2) NULL DEFAULT NULL,
  `latest_total_score` decimal(10, 2) NULL DEFAULT NULL,
  `ranking` int NULL DEFAULT NULL,
  `transcript_row_present` tinyint(1) NOT NULL DEFAULT 0,
  `answer_sheet_count` int NOT NULL DEFAULT 0,
  `scored_code_count` int NOT NULL DEFAULT 0,
  `submission_attempt_count` int NOT NULL DEFAULT 0,
  `completion_evidence` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'NONE',
  `latest_sync_at` timestamp(3) NULL DEFAULT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_student_assignment`(`offering_id` ASC, `student_id` ASC) USING BTREE,
  INDEX `idx_student_assignment_offering`(`offering_id` ASC) USING BTREE,
  INDEX `idx_student_assignment_student`(`student_id` ASC) USING BTREE,
  INDEX `idx_student_assignment_status`(`submission_status` ASC) USING BTREE,
  INDEX `idx_student_assignment_evidence`(`offering_id` ASC, `completion_evidence` ASC) USING BTREE,
  CONSTRAINT `fk_student_assignment_offering` FOREIGN KEY (`offering_id`) REFERENCES `assignment_offering` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_student_assignment_student` FOREIGN KEY (`student_id`) REFERENCES `student_profile` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `chk_student_assignment_counts_valid` CHECK ((`accepted_problem_count` >= 0) and (`submitted_problem_count` >= 0) and (`problem_count` >= 0) and (`accepted_problem_count` <= `submitted_problem_count`) and ((`problem_count` = 0) or (`submitted_problem_count` <= `problem_count`))),
  CONSTRAINT `chk_student_assignment_evidence_valid` CHECK ((`transcript_row_present` in (0,1)) and (`answer_sheet_count` >= 0) and (`scored_code_count` >= 0) and (`submission_attempt_count` >= 0) and (`completion_evidence` in (_utf8mb4'NONE',_utf8mb4'TRANSCRIPT_SCORE',_utf8mb4'ANSWER_SHEET',_utf8mb4'SCORED_CODE',_utf8mb4'SUBMISSION_ATTEMPT'))),
  CONSTRAINT `chk_student_assignment_not_started_empty` CHECK ((`submission_status` <> _utf8mb4'NOT_STARTED') or ((`first_submit_at` is null) and (`last_submit_at` is null) and (`accepted_problem_count` = 0) and (`submitted_problem_count` = 0) and (`best_total_score` is null) and (`latest_total_score` is null) and (`ranking` is null))),
  CONSTRAINT `chk_student_assignment_not_started_evidence_empty` CHECK ((`submission_status` <> _utf8mb4'NOT_STARTED') or ((`answer_sheet_count` = 0) and (`scored_code_count` = 0) and (`submission_attempt_count` = 0) and (`completion_evidence` = _utf8mb4'NONE'))),
  CONSTRAINT `chk_student_assignment_scores_valid` CHECK (((`best_total_score` is null) or (`best_total_score` >= 0)) and ((`latest_total_score` is null) or (`latest_total_score` >= 0))),
  CONSTRAINT `chk_student_assignment_status` CHECK (`submission_status` in (_utf8mb4'NOT_STARTED',_utf8mb4'IN_PROGRESS',_utf8mb4'SUBMITTED',_utf8mb4'GRADED',_utf8mb4'CLOSED'))
) ENGINE = InnoDB AUTO_INCREMENT = 26134 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for student_code
-- ----------------------------
DROP TABLE IF EXISTS `student_code`;
CREATE TABLE `student_code`  (
  `experiment_id` int NOT NULL,
  `experiment_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `student_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `student_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `code` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  PRIMARY KEY (`experiment_id`, `student_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for student_problem_attempt
-- ----------------------------
DROP TABLE IF EXISTS `student_problem_attempt`;
CREATE TABLE `student_problem_attempt`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `offering_id` bigint NOT NULL,
  `problem_id` bigint NOT NULL,
  `student_id` bigint NOT NULL,
  `pta_user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `source_system` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `source_attempt_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `submitted_at` timestamp(3) NOT NULL,
  `judge_status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `score` decimal(10, 2) NULL DEFAULT NULL,
  `compiler` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `runtime_ms` int NULL DEFAULT NULL,
  `memory_kb` int NULL DEFAULT NULL,
  `raw_row_id` bigint NULL DEFAULT NULL,
  `raw_api_submission_id` bigint NULL DEFAULT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_student_problem_attempt_source`(`source_system` ASC, `source_attempt_key` ASC) USING BTREE,
  UNIQUE INDEX `uq_student_problem_attempt_id_scope`(`id` ASC, `offering_id` ASC, `problem_id` ASC, `student_id` ASC) USING BTREE,
  INDEX `fk_student_problem_attempt_problem_offering`(`problem_id` ASC, `offering_id` ASC) USING BTREE,
  INDEX `fk_student_problem_attempt_student`(`student_id` ASC) USING BTREE,
  INDEX `idx_student_problem_attempt_offering_student`(`offering_id` ASC, `student_id` ASC, `submitted_at` ASC) USING BTREE,
  INDEX `idx_student_problem_attempt_problem_student`(`problem_id` ASC, `student_id` ASC, `submitted_at` ASC) USING BTREE,
  INDEX `idx_student_problem_attempt_status`(`judge_status` ASC) USING BTREE,
  INDEX `fk_student_problem_attempt_raw_row`(`raw_row_id` ASC) USING BTREE,
  INDEX `idx_student_problem_attempt_raw_api`(`raw_api_submission_id` ASC) USING BTREE,
  CONSTRAINT `fk_student_problem_attempt_offering` FOREIGN KEY (`offering_id`) REFERENCES `assignment_offering` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_student_problem_attempt_problem_offering` FOREIGN KEY (`problem_id`, `offering_id`) REFERENCES `assignment_problem` (`id`, `offering_id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_student_problem_attempt_raw_api` FOREIGN KEY (`raw_api_submission_id`) REFERENCES `pta_api_submission_row` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_student_problem_attempt_raw_row` FOREIGN KEY (`raw_row_id`) REFERENCES `pta_raw_submission_row` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_student_problem_attempt_student` FOREIGN KEY (`student_id`) REFERENCES `student_profile` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_student_problem_attempt_student_assignment` FOREIGN KEY (`offering_id`, `student_id`) REFERENCES `student_assignment` (`offering_id`, `student_id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 434465 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for student_problem_state
-- ----------------------------
DROP TABLE IF EXISTS `student_problem_state`;
CREATE TABLE `student_problem_state`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `offering_id` bigint NOT NULL,
  `problem_id` bigint NOT NULL,
  `student_id` bigint NOT NULL,
  `latest_attempt_id` bigint NULL DEFAULT NULL,
  `best_attempt_id` bigint NULL DEFAULT NULL,
  `latest_status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `best_score` decimal(10, 2) NULL DEFAULT NULL,
  `attempt_count` int NOT NULL DEFAULT 0,
  `accepted_at` timestamp(3) NULL DEFAULT NULL,
  `latest_code_artifact_id` bigint NULL DEFAULT NULL,
  `latest_answer_sheet_artifact_id` bigint NULL DEFAULT NULL,
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_student_problem_state`(`offering_id` ASC, `problem_id` ASC, `student_id` ASC) USING BTREE,
  INDEX `fk_student_problem_state_problem_offering`(`problem_id` ASC, `offering_id` ASC) USING BTREE,
  INDEX `fk_student_problem_state_latest_attempt`(`latest_attempt_id` ASC, `offering_id` ASC, `problem_id` ASC, `student_id` ASC) USING BTREE,
  INDEX `fk_student_problem_state_best_attempt`(`best_attempt_id` ASC, `offering_id` ASC, `problem_id` ASC, `student_id` ASC) USING BTREE,
  INDEX `fk_student_problem_state_code_artifact`(`latest_code_artifact_id` ASC) USING BTREE,
  INDEX `fk_student_problem_state_answer_artifact`(`latest_answer_sheet_artifact_id` ASC) USING BTREE,
  INDEX `idx_student_problem_state_student`(`student_id` ASC) USING BTREE,
  INDEX `idx_student_problem_state_status`(`latest_status` ASC) USING BTREE,
  INDEX `fk_student_problem_state_student_assignment`(`offering_id` ASC, `student_id` ASC) USING BTREE,
  CONSTRAINT `fk_student_problem_state_answer_artifact` FOREIGN KEY (`latest_answer_sheet_artifact_id`) REFERENCES `artifact` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_student_problem_state_best_attempt` FOREIGN KEY (`best_attempt_id`) REFERENCES `student_problem_attempt` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_student_problem_state_code_artifact` FOREIGN KEY (`latest_code_artifact_id`) REFERENCES `artifact` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_student_problem_state_latest_attempt` FOREIGN KEY (`latest_attempt_id`) REFERENCES `student_problem_attempt` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_student_problem_state_offering` FOREIGN KEY (`offering_id`) REFERENCES `assignment_offering` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_student_problem_state_problem_offering` FOREIGN KEY (`problem_id`, `offering_id`) REFERENCES `assignment_problem` (`id`, `offering_id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_student_problem_state_student` FOREIGN KEY (`student_id`) REFERENCES `student_profile` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_student_problem_state_student_assignment` FOREIGN KEY (`offering_id`, `student_id`) REFERENCES `student_assignment` (`offering_id`, `student_id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 268857 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for student_profile
-- ----------------------------
DROP TABLE IF EXISTS `student_profile`;
CREATE TABLE `student_profile`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `student_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `real_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` bigint NULL DEFAULT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ACTIVE',
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_student_profile_student_no`(`student_no` ASC) USING BTREE,
  INDEX `idx_student_profile_user`(`user_id` ASC) USING BTREE,
  INDEX `idx_student_profile_name`(`real_name` ASC) USING BTREE,
  CONSTRAINT `fk_student_profile_user` FOREIGN KEY (`user_id`) REFERENCES `tap_user` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `chk_student_profile_status` CHECK (`status` in (_utf8mb4'ACTIVE',_utf8mb4'INACTIVE',_utf8mb4'GRADUATED',_utf8mb4'DELETED')),
  CONSTRAINT `chk_student_profile_student_no_valid` CHECK ((trim(`student_no`) <> _utf8mb4'') and (lower(trim(`student_no`)) not in (_utf8mb4'0',_utf8mb4'none',_utf8mb4'null',_utf8mb4'n/a',_utf8mb4'na',_utf8mb4'blank')))
) ENGINE = InnoDB AUTO_INCREMENT = 138275 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for student_skill_state
-- ----------------------------
DROP TABLE IF EXISTS `student_skill_state`;
CREATE TABLE `student_skill_state`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `student_id` int NOT NULL,
  `tag_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `mastery_score` decimal(5, 2) NOT NULL DEFAULT 50.00 COMMENT '0~100',
  `forgetting_score` decimal(5, 2) NOT NULL DEFAULT 0.00 COMMENT '0~100',
  `confidence_score` decimal(5, 2) NOT NULL DEFAULT 0.00 COMMENT '0~100',
  `attempt_count` int NOT NULL DEFAULT 0,
  `success_count` int NOT NULL DEFAULT 0,
  `avg_attempts_to_success` decimal(8, 3) NULL DEFAULT NULL,
  `last_practice_at` datetime NULL DEFAULT NULL,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_student_tag`(`student_id` ASC, `tag_name` ASC) USING BTREE,
  INDEX `idx_student`(`student_id` ASC) USING BTREE,
  INDEX `idx_student_mastery`(`student_id` ASC, `mastery_score` ASC) USING BTREE,
  INDEX `idx_student_forgetting`(`student_id` ASC, `forgetting_score` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 13 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '学生技能状态表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for submission
-- ----------------------------
DROP TABLE IF EXISTS `submission`;
CREATE TABLE `submission`  (
  `submission_id` int NOT NULL AUTO_INCREMENT,
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `experiment_id` int NOT NULL,
  `code` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `report` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `submit_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `serial_number` int NULL DEFAULT 0,
  PRIMARY KEY (`submission_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for submit_situation
-- ----------------------------
DROP TABLE IF EXISTS `submit_situation`;
CREATE TABLE `submit_situation`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `submit_time` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `situation` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `score` decimal(5, 2) NULL DEFAULT NULL,
  `serial_number` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `experiment_id` int NOT NULL,
  `experiment_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `runtime_ms` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `memory_kb` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `student_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `student_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_submit`(`submit_time` ASC, `serial_number` ASC, `experiment_id` ASC, `student_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 675197 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for tap_user
-- ----------------------------
DROP TABLE IF EXISTS `tap_user`;
CREATE TABLE `tap_user`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `display_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `role` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'TEACHER',
  `password_hash` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `pta_username` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'Bound PTA username',
  `pta_password_ciphertext` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'Encrypted PTA password',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_tap_user_username`(`username` ASC) USING BTREE,
  INDEX `idx_tap_user_role`(`role` ASC) USING BTREE,
  CONSTRAINT `chk_tap_user_role` CHECK (`role` in (_utf8mb4'TEACHER',_utf8mb4'ADMIN',_utf8mb4'STUDENT'))
) ENGINE = InnoDB AUTO_INCREMENT = 5 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for teacher
-- ----------------------------
DROP TABLE IF EXISTS `teacher`;
CREATE TABLE `teacher`  (
  `teacher_id` int NOT NULL AUTO_INCREMENT,
  `teacher_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '教师姓名',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `classroom` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '所教班级',
  PRIMARY KEY (`teacher_id`) USING BTREE,
  UNIQUE INDEX `username`(`username` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for teaching_class
-- ----------------------------
DROP TABLE IF EXISTS `teaching_class`;
CREATE TABLE `teaching_class`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `teacher_id` bigint NOT NULL,
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '班级名称',
  `class_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '唯一班级号',
  `join_password` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '加入密码',
  `grade` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '年级，如 2023',
  `course_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '课程名称',
  `course_id` bigint NULL DEFAULT NULL COMMENT 'Reference to course',
  `term_id` bigint NULL DEFAULT NULL COMMENT 'Reference to academic term',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '班级描述',
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `pta_keyword` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'PTA搜索关键词',
  `sync_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否开启PTA定时同步',
  `last_sync_at` timestamp NULL DEFAULT NULL COMMENT '上次同步完成时间',
  `sync_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT 'IDLE' COMMENT '同步状态',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE or ARCHIVED',
  `archived_at` timestamp(3) NULL DEFAULT NULL COMMENT 'Archive timestamp',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_class_code`(`class_code` ASC) USING BTREE,
  INDEX `idx_teaching_class_teacher`(`teacher_id` ASC) USING BTREE,
  INDEX `fk_teaching_class_term`(`term_id` ASC) USING BTREE,
  INDEX `idx_teaching_class_course_term`(`course_id` ASC, `term_id` ASC) USING BTREE,
  CONSTRAINT `fk_teaching_class_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_teaching_class_teacher` FOREIGN KEY (`teacher_id`) REFERENCES `tap_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_teaching_class_term` FOREIGN KEY (`term_id`) REFERENCES `academic_term` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for tk
-- ----------------------------
DROP TABLE IF EXISTS `tk`;
CREATE TABLE `tk`  (
  `problems_id` int NOT NULL,
  `problems_content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `problems_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  PRIMARY KEY (`problems_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for total_submission_analysis
-- ----------------------------
DROP TABLE IF EXISTS `total_submission_analysis`;
CREATE TABLE `total_submission_analysis`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `total_analysis` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for translation_segment
-- ----------------------------
DROP TABLE IF EXISTS `translation_segment`;
CREATE TABLE `translation_segment`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `document_id` bigint NOT NULL,
  `target_lang` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `segment_index` int NOT NULL,
  `source_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `provider` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `document_id`(`document_id` ASC, `target_lang` ASC, `segment_index` ASC) USING BTREE,
  UNIQUE INDEX `uq_translation_segment_doc_lang_idx`(`document_id` ASC, `target_lang` ASC, `segment_index` ASC) USING BTREE,
  CONSTRAINT `fk_translation_segment_doc` FOREIGN KEY (`document_id`) REFERENCES `document` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 696 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for upload_folder
-- ----------------------------
DROP TABLE IF EXISTS `upload_folder`;
CREATE TABLE `upload_folder`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `folder_name` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `original_structure_json` json NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_upload_folder_user`(`user_id` ASC) USING BTREE,
  CONSTRAINT `fk_upload_folder_user` FOREIGN KEY (`user_id`) REFERENCES `tap_user` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 16 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for user
-- ----------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '用户名',
  `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '密码',
  `email` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '邮箱',
  `role` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'student' COMMENT '角色: student/teacher/admin',
  `usernum` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '学号或工号',
  `classname` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '班级名称',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `username`(`username` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for user_daily_quota_usage
-- ----------------------------
DROP TABLE IF EXISTS `user_daily_quota_usage`;
CREATE TABLE `user_daily_quota_usage`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `usage_date` date NOT NULL,
  `translation_chars` bigint NOT NULL DEFAULT 0,
  `ai_requests` bigint NOT NULL DEFAULT 0,
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `user_id`(`user_id` ASC, `usage_date` ASC) USING BTREE,
  CONSTRAINT `fk_user_daily_quota_usage_user` FOREIGN KEY (`user_id`) REFERENCES `tap_user` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 157 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for zip_organize_item
-- ----------------------------
DROP TABLE IF EXISTS `zip_organize_item`;
CREATE TABLE `zip_organize_item`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `job_id` bigint NOT NULL,
  `original_path` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `filename` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `content_type` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `size_bytes` bigint NOT NULL DEFAULT 0,
  `sha256` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `ext` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `object_key` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `extract_status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `extracted_text_preview` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `title_candidate` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `doc_kind` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `topic` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `keywords_json` json NULL,
  `summary_zh` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `year_value` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `confidence` double NULL DEFAULT 0,
  `review_flag` tinyint(1) NOT NULL DEFAULT 0,
  `review_reason` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `target_folder` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `new_filename` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `duplicate_group_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `final_path` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_zip_organize_item_job`(`job_id` ASC) USING BTREE,
  CONSTRAINT `fk_zip_organize_item_job` FOREIGN KEY (`job_id`) REFERENCES `zip_organize_job` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `chk_zip_organize_extract_status` CHECK (`extract_status` in (_utf8mb4'PENDING',_utf8mb4'EXTRACTED',_utf8mb4'EMPTY',_utf8mb4'FAILED'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for zip_organize_job
-- ----------------------------
DROP TABLE IF EXISTS `zip_organize_job`;
CREATE TABLE `zip_organize_job`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `progress` int NOT NULL DEFAULT 0,
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `retry_count` int NOT NULL DEFAULT 0,
  `started_at` timestamp(3) NULL DEFAULT NULL,
  `finished_at` timestamp(3) NULL DEFAULT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `current_step` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `step_detail` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `original_filename` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `input_object_key` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `zip_object_key` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `report_object_key` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  `result_json` json NULL,
  `version` bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_zip_organize_job_user_created`(`user_id` ASC, `created_at` DESC) USING BTREE,
  INDEX `idx_zip_organize_job_status_created`(`status` ASC, `created_at` ASC) USING BTREE,
  CONSTRAINT `fk_zip_organize_job_user` FOREIGN KEY (`user_id`) REFERENCES `tap_user` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `chk_zip_organize_job_status` CHECK (`status` in (_utf8mb4'PENDING',_utf8mb4'RUNNING',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED',_utf8mb4'CANCELLED'))
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
