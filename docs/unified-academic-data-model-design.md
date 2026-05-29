# Unified Academic Data Model Design

## 1. Document Status

- Status: Review draft
- Scope: `AI_Ds` teacher-side teaching, PTA import, experiment analytics, AI grading association
- Database target: MySQL 8+
- This document is not a Flyway migration. It is the source of truth for later implementation and schema review.

## 2. Problem Statement

The current system has two parallel data models:

- Legacy teaching model:
  - `teacher`
  - `student`
  - `experiment`
  - `score`
  - `submission`
  - `student_code`
  - `submit_situation`
- New TAP model:
  - `tap_user`
  - `teaching_class`
  - `class_student`
  - `grading_task`
  - `grading_submission`
  - `score_item`
  - `report_file`

This causes structural inconsistency:

- PTA sync writes class roster into `teaching_class` and `class_student`
- Teacher-side experiment pages still query `teacher.classroom` and `student.class_name`
- PTA import script under `services/pta_spider/` writes directly into legacy tables
- AI grading uses a different submission model from PTA online judge submissions

As a result:

- Teacher-side experiment lists and student submission views are incomplete or empty
- The same real-world entity is represented multiple times with different IDs
- New features are harder to add because each new capability must decide which model to depend on

## 3. Design Goals

The unified model must satisfy these goals:

1. One stable business model for teacher-facing read/write operations.
2. PTA crawler data is treated as an external source, not as the business schema itself.
3. Historical raw files and parsed rows remain replayable for future parser changes.
4. PTA online judge submissions and AI grading submissions are associated through shared academic dimensions, but are not forced into the same physical table.
5. The schema supports continuous iteration:
   - new data sources
   - new analytics
   - new AI features
   - cross-class and cross-term reuse of assignments

## 4. Core Principles

### 4.1 Single business model, multiple source adapters

Business code should read from unified academic tables. PTA crawler, manual import, and future sources should all normalize into the same target tables.

### 4.2 Separate source facts from business snapshots

Raw crawler output must not directly serve pages. Raw import tables are append-only or replayable; business tables are normalized read/write models.

### 4.3 Separate template from offering

An assignment template is not the same thing as a class-specific publication of that assignment.

- Template: reusable teaching content
- Offering: one class, one term, one publish cycle

Without this split, cross-term reuse and analytics become fragile.

### 4.4 Separate PTA submission domain from AI grading domain

PTA online judge attempts and AI grading of uploaded reports are different domains:

- PTA domain tracks code attempts, problem states, runtime, memory, judge result
- AI grading domain tracks rubric-based evaluation of reports or uploaded work

They should share:

- teacher
- student
- class
- assignment offering

But they should not be forced into one generic submission table.

### 4.5 Separate canonical primary keys from compatibility keys

Internal joins should use canonical surrogate keys such as:

- `student_profile.id`
- `assignment_offering.id`
- `assignment_problem.id`

But compatibility-facing teacher APIs may still need stable business keys during transition.

Practical rule:

- `student_profile.id` is the canonical internal student key
- `student_profile.student_no` remains the stable business identifier exposed to legacy teacher pages
- legacy-style submission routes must treat `submissionId` as a synthetic compatibility key, not as a physical table primary key
- the compatibility key must be parsed as a string contract `studentNo-experimentId`, not as a new integer surrogate

This avoids leaking a new internal surrogate key into old frontend assumptions too early.

## 5. Scope Boundaries

### 5.1 In scope

- Teacher
- Student
- Class
- Course and term
- Assignment template
- Assignment offering
- Assignment problem
- PTA import job and source files
- PTA raw parsed rows
- PTA normalized attempts and states
- Teacher-side student assignment summary
- AI grading association to assignment offering

### 5.2 Out of scope for this schema draft

- Full crawler implementation rewrite
- Full frontend rewrite
- Full RAG document schema changes
- Zip organize feature changes

## 6. Final Domain Model

### 6.1 Keep and extend existing core tables

These existing tables remain primary:

- `tap_user`
- `teaching_class`
- `grading_task`
- `grading_submission`
- `score_item`
- `score_override`
- `grading_trace`
- `report_file`

### 6.2 New academic core tables

- `academic_term`
- `course`
- `student_profile`
- `class_member`
- `assignment_template`
- `assignment_offering`
- `assignment_problem`
- `student_assignment`
- `student_problem_attempt`
- `student_problem_state`
- `artifact`
- `external_identity_binding`

### 6.3 New import and replay tables

- `import_job`
- `import_source_file`
- `pta_raw_submission_row`
- `pta_raw_transcript_row`
- `pta_raw_answer_sheet`

## 7. Entity Responsibilities

### 7.1 `tap_user`

Role:

- authenticated actor in the system
- teacher/admin account
- optionally student account in the future

Notes:

- keep existing table name
- extend role constraint later if student login needs to be formalized in the TAP model

### 7.2 `student_profile`

Role:

- canonical student master record
- independent from whether the student has a registered `tap_user`

Why needed:

- PTA roster and transcripts identify students by student number and name
- many students may not have a TAP user account yet

Key rule:

- `student_profile.id` is the canonical internal primary key
- `student_no` is the stable external/business identifier used for import matching and legacy DTO compatibility during transition

### 7.3 `teaching_class`

Role:

- business class container
- owned by teacher
- should carry course and term metadata

Required extension:

- add `course_id`
- add `term_id`
- keep existing PTA sync fields

### 7.4 `class_member`

Role:

- canonical relation between class and student

This replaces:

- `student.class_name`
- `class_student` as the primary roster relation

Migration note:

- `class_student` can be migrated into `class_member`
- `class_student` may be kept temporarily for compatibility, but must stop being the primary roster table

### 7.5 `assignment_template`

Role:

- reusable definition of an experiment or assignment
- independent of a specific class run

### 7.6 `assignment_offering`

Role:

- one class-specific publication of an assignment template
- the primary anchor for teacher-side pages and analytics

### 7.7 `assignment_problem`

Role:

- problem definitions under one offering
- normalized from PTA problem set content

This replaces using only:

- `problems_sets.problem`

### 7.8 `student_problem_attempt`

Role:

- immutable or append-style PTA online judge attempts
- one row per observed attempt event

Source:

- `提交记录.csv`

### 7.9 `student_problem_state`

Role:

- latest or best state of a student on one problem
- query-friendly read model for teacher pages

Source:

- recalculated from attempts plus answer sheet/code artifacts

### 7.10 `student_assignment`

Role:

- one row per student per assignment offering
- main summary table for teacher dashboards and class analytics

This is the most important read model for experiment pages.

Materialization rule:

- rows must exist for every active `class_member` of the offering's class
- not only for students who have submitted attempts
- students with no attempts must still have a `student_assignment` row with a not-started status

### 7.11 `artifact`

Role:

- unified asset registry
- raw file, extracted code, answer sheet html, generated report, annotated pdf, export zip, parsed text

This avoids scattering file metadata across domain tables.

### 7.12 `external_identity_binding`

Role:

- explicit mapping from internal entity to external system IDs

Examples:

- PTA user ID -> student profile
- PTA problem set ID -> assignment offering
- PTA problem ID -> assignment problem

This prevents mapping logic from being buried in import scripts.

### 7.13 `import_job` and raw PTA tables

Role:

- preserve import lineage
- support replay and parser upgrades

These tables should be sufficient to rebuild normalized business tables without re-crawling if raw files already exist.

Replay rule:

- rerunning the same logical PTA source file should update matching normalized rows and prune stale normalized attempts or states that are no longer present in the current authoritative source file
- raw lineage rows remain preserved by `import_job`; pruning applies to normalized read/write models, not to historical raw storage

## 8. Proposed Schema

### 8.1 `academic_term`

- `id`
- `term_code`
- `name`
- `start_date`
- `end_date`
- `status`

Constraints:

- unique `term_code`

### 8.2 `course`

- `id`
- `course_code`
- `name`
- `subject`
- `description`
- `status`

Constraints:

- unique `course_code`

### 8.3 `student_profile`

- `id`
- `student_no`
- `real_name`
- `user_id nullable`
- `status`
- `created_at`
- `updated_at`

Constraints:

- unique `student_no`
- foreign key `user_id -> tap_user(id)` nullable

Indexes:

- `idx_student_profile_user`
- `idx_student_profile_name`

### 8.4 `teaching_class`

Current table should be extended with:

- `course_id nullable`
- `term_id nullable`
- `status`
- `archived_at nullable`

Indexes:

- `(teacher_id)`
- `(course_id, term_id)`

### 8.5 `class_member`

- `id`
- `class_id`
- `student_id`
- `member_status`
- `joined_at`
- `left_at nullable`
- `created_at`
- `updated_at`

Constraints:

- unique `(class_id, student_id)`

Indexes:

- `idx_class_member_student`
- `idx_class_member_status`

### 8.6 `assignment_template`

- `id`
- `title`
- `category`
- `language`
- `description_md`
- `source_system nullable`
- `source_template_key nullable`
- `status`
- `created_by nullable`
- `created_at`
- `updated_at`

Constraints:

- unique `(source_system, source_template_key)` when both present

### 8.7 `assignment_offering`

- `id`
- `template_id`
- `class_id`
- `teacher_id`
- `seq_no nullable`
- `title_override nullable`
- `published_at nullable`
- `deadline_at nullable`
- `status`
- `source_system nullable`
- `source_offering_key nullable`
- `pta_problem_set_id nullable`
- `created_at`
- `updated_at`

Constraints:

- unique `(source_system, source_offering_key)` when both present

Important note:

- `pta_problem_set_id` should not be assumed globally unique across all future offerings
- keep it queryable and indexed
- use `(source_system, source_offering_key)` or `external_identity_binding` as the formal uniqueness boundary
- when an offering comes from an external system, `source_system` and `source_offering_key` must be provided together
- PTA import should always materialize a canonical offering key such as `PTA:{class_id}:{pta_problem_set_id}` into `source_offering_key`

Indexes:

- `idx_assignment_offering_class`
- `idx_assignment_offering_teacher`
- `idx_assignment_offering_template`
- `idx_assignment_offering_deadline`

### 8.8 `assignment_problem`

- `id`
- `offering_id`
- `problem_no`
- `source_problem_id nullable`
- `title`
- `statement_md`
- `max_score nullable`
- `sort_order`
- `status`
- `created_at`
- `updated_at`

Constraints:

- unique `(offering_id, problem_no)`
- unique `(offering_id, source_problem_id)` when source problem id exists

### 8.9 `student_assignment`

- `id`
- `offering_id`
- `student_id`
- `submission_status`
- `first_submit_at nullable`
- `last_submit_at nullable`
- `accepted_problem_count`
- `submitted_problem_count`
- `problem_count`
- `best_total_score nullable`
- `latest_total_score nullable`
- `ranking nullable`
- `latest_sync_at nullable`
- `created_at`
- `updated_at`

Constraints:

- unique `(offering_id, student_id)`

Indexes:

- `idx_student_assignment_offering`
- `idx_student_assignment_student`
- `idx_student_assignment_status`

Read-model rule:

- this table must be materialized from `assignment_offering x active class_member`
- it is not an attempts-only aggregate
- once attempts or states exist, refresh this table with upsert or merge semantics rather than delete-and-recreate

### 8.10 `student_problem_attempt`

- `id`
- `offering_id`
- `problem_id`
- `student_id`
- `pta_user_id nullable`
- `source_system`
- `source_attempt_key`
- `submitted_at`
- `judge_status`
- `score nullable`
- `compiler nullable`
- `runtime_ms nullable`
- `memory_kb nullable`
- `raw_row_id nullable`
- `created_at`

Constraints:

- unique `(source_system, source_attempt_key)`

Integrity rule:

- `problem_id` must belong to the same `offering_id`
- enforce this with a composite foreign-key strategy or an equivalent database constraint
- `student_id` must also belong to the same offering roster boundary
- enforce this through `(offering_id, student_id) -> student_assignment(offering_id, student_id)` or an equivalent canonical roster constraint
- `source_attempt_key` must be derived from stable submission identity fields only, such as offering, external user identity, problem identity, submitted time, and optionally compiler
- do not include mutable judge outputs like score, runtime, memory, or post-rejudge status in `source_attempt_key`

Indexes:

- `idx_attempt_offering_student`
- `idx_attempt_problem_student`
- `idx_attempt_submitted_at`
- `idx_attempt_judge_status`

### 8.11 `student_problem_state`

- `id`
- `offering_id`
- `problem_id`
- `student_id`
- `latest_attempt_id nullable`
- `best_attempt_id nullable`
- `latest_status nullable`
- `best_score nullable`
- `attempt_count`
- `accepted_at nullable`
- `latest_code_artifact_id nullable`
- `latest_answer_sheet_artifact_id nullable`
- `updated_at`

Constraints:

- unique `(offering_id, problem_id, student_id)`

Integrity rule:

- `problem_id` must belong to the same `offering_id`
- this table must not allow cross-offering problem references
- `student_id` must belong to the same offering roster boundary
- `latest_attempt_id` and `best_attempt_id` must reference an attempt from the same `(offering_id, problem_id, student_id)` scope, not merely any attempt row

Indexes:

- `idx_problem_state_student`
- `idx_problem_state_status`

### 8.12 `artifact`

- `id`
- `owner_type`
- `owner_id`
- `artifact_type`
- `storage_type`
- `object_key nullable`
- `text_content longtext nullable`
- `content_hash nullable`
- `mime_type nullable`
- `file_name nullable`
- `size_bytes nullable`
- `source_system nullable`
- `source_key nullable`
- `metadata_json nullable`
- `created_at`

Constraints:

- unique `(source_system, source_key)` when present

Practical rule:

- for raw import owned artifacts, generate `source_key` inside the `import_job` scope so a later import does not overwrite an earlier job's lineage

Indexes:

- `idx_artifact_owner`
- `idx_artifact_type`
- `idx_artifact_hash`

### 8.13 `external_identity_binding`

- `id`
- `entity_type`
- `entity_id`
- `source_system`
- `external_id`
- `binding_type`
- `confidence`
- `valid_from`
- `valid_to nullable`
- `metadata_json nullable`
- `created_at`
- `updated_at`

Practical rule:

- one active binding per external ID is required at the business level
- in the first schema iteration, enforce this in import/service logic rather than with a naive SQL unique constraint
- keep history with validity fields if future needs become stricter
- do not rely on `pta_problem_set_id` alone as the replay or upsert key for offerings; persist a canonical `source_offering_key`

### 8.14 `import_job`

- `id`
- `source_system`
- `job_type`
- `class_id nullable`
- `trigger_type`
- `triggered_by nullable`
- `status`
- `started_at`
- `finished_at nullable`
- `summary_json nullable`
- `error_message nullable`
- `created_at`

### 8.15 `import_source_file`

- `id`
- `import_job_id`
- `file_role`
- `relative_path`
- `sha256`
- `size_bytes`
- `parse_status`
- `parsed_at nullable`
- `error_message nullable`
- `metadata_json nullable`
- `created_at`

Constraints:

- unique `(import_job_id, relative_path)`

### 8.16 `pta_raw_submission_row`

- `id`
- `import_job_id`
- `source_file_id`
- `row_no`
- `pta_user_id`
- `pta_problem_id`
- `judge_status`
- `score_text`
- `compiler`
- `runtime_text`
- `memory_text`
- `submitted_at_text`
- `raw_json`
- `created_at`

### 8.17 `pta_raw_transcript_row`

- `id`
- `import_job_id`
- `source_file_id`
- `row_no`
- `student_no`
- `student_name`
- `total_score_text`
- `ranking_text`
- `raw_json`
- `created_at`

### 8.18 `pta_raw_answer_sheet`

- `id`
- `import_job_id`
- `source_file_id`
- `student_no`
- `student_name`
- `problem_key`
- `html_artifact_id`
- `code_artifact_id nullable`
- `test_report_artifact_id nullable`
- `raw_json`
- `created_at`

Constraints:

- keep per-import lineage; do not deduplicate across different `import_job`
- enforce per-source-file entry idempotence with `(source_file_id, html_artifact_id)` unique

## 9. Relationship Summary

The main query path should be:

- teacher -> `tap_user`
- class -> `teaching_class`
- class roster -> `class_member`
- students -> `student_profile`
- assignment -> `assignment_offering`
- problems -> `assignment_problem`
- student summary -> `student_assignment`
- per-problem detail -> `student_problem_state`
- full attempt history -> `student_problem_attempt`
- files -> `artifact`

AI grading should attach by:

- `grading_task.assignment_offering_id`
- `grading_submission.student_id`

Compatibility query rule during transition:

- legacy teacher DTOs may continue exposing `studentId = student_profile.student_no`
- legacy teacher DTOs may continue exposing `experimentId = assignment_offering.id`
- `/api/submissions/{submissionId}` should continue to interpret `submissionId` as the synthetic key `studentId-experimentId`
- this route key is a compatibility contract, not a database primary key
- backend parsing for this route should keep the student segment as a string business key, not force integer parsing

## 10. PTA Import Normalization Flow

### 10.1 Source files

Crawler output produced by `services/pta_spider` provides:

- `提交记录.csv`
- `题目内容.txt`
- `PAPER_TRANSCRIPT.xlsx`
- `ANSWER_SHEET.zip`
- `SCORED_CODE.zip`

### 10.2 Import pipeline

1. Create `import_job`.
2. Register all source files in `import_source_file` with hash.
3. Parse raw file rows into:
   - `pta_raw_submission_row`
   - `pta_raw_transcript_row`
   - `pta_raw_answer_sheet`
4. Resolve or create:
   - `student_profile`
   - `external_identity_binding` for PTA user ID
5. Resolve or create:
   - `assignment_template`
   - `assignment_offering`
   - `assignment_problem`
6. Materialize `student_assignment` for all active `class_member` rows of the offering's class.
7. Upsert `student_problem_attempt`.
8. Recalculate `student_problem_state`.
9. Recalculate `student_assignment`.
10. Link or upsert `artifact`.
11. Mark `import_job` complete and store summary.

### 10.3 Idempotency rules

- Same source file hash within same import should not be parsed twice.
- Same PTA attempt should not create duplicate `student_problem_attempt` rows.
- Re-import should update business snapshots, not duplicate them.
- Re-import must also recreate missing `student_assignment` rows for active roster members who still have no attempts.

## 11. Existing Table Mapping

### 11.1 Legacy to unified mapping

- `teacher` -> `tap_user`
- `student` -> `student_profile`
- `class_student` -> `class_member`
- `experiment` -> `assignment_template` + `assignment_offering`
- `problems_sets` -> `assignment_problem`
- `submit_situation` -> `pta_raw_submission_row` + `student_problem_attempt`
- `score` -> `student_assignment`
- `student_code` -> `artifact` + `student_problem_state`
- `submission` -> compatibility view/key only during transition, plus optional artifact/report migration
- `problem_score_detail` -> can remain as derived analytics or be rebuilt from normalized tables

### 11.2 Existing tables to keep

- `tap_user`
- `teaching_class`
- `grading_task`
- `grading_submission`
- `score_item`
- `score_override`
- `grading_trace`
- `report_file`

### 11.3 Existing tables to downgrade from primary usage

- `teacher`
- `student`
- `user`
- `experiment`
- `score`
- `submission`
- `student_code`
- `submit_situation`
- `problems_sets`

## 12. Read Model Strategy

Teacher-side pages must not query raw import tables directly.

Recommended read models:

- experiment list page:
  - `assignment_offering`
  - `student_assignment`
- student submission list:
  - `student_assignment`
  - `student_problem_state`
- student experiment detail:
  - `student_assignment`
  - `student_problem_state`
  - `student_problem_attempt`
  - `artifact`

## 13. Migration Strategy

### Phase 1. Freeze the target model

- Review this document
- Review the DDL draft
- Confirm naming and boundaries

### Phase 2. Add schema only

- Add new tables
- Extend `teaching_class`
- Extend grading tables with offering linkage
- Do not yet add the `grading_submission.student_id -> student_profile(id)` foreign key until backfill and validation are complete
- Do not delete old tables

### Phase 3. Build import path into new tables

- Refactor `services/pta_spider/sync_to_db.py`
- Backfill canonical student and class data from existing `student`, `class_student`, and related tables before switching reads
- PTA import writes:
  - import tables
  - raw tables
  - normalized tables
- legacy writes become optional compatibility mode only

### Phase 4. Switch teacher-side reads

- Teacher experiment list
- Student list
- Submission list
- Experiment detail
- Analytics

All should read unified tables.

### Phase 5. Migrate AI grading linkage

- add `assignment_offering_id` to `grading_task`
- bind `grading_submission.student_id` to `student_profile.id`

### Phase 6. Retire old tables from business logic

- stop reading old legacy tables in application code
- keep them temporarily for audit and rollback

## 14. Risks and Mitigations

### Risk 1. Identity mismatches

PTA user ID, student number, and local account may not align.

Mitigation:

- `external_identity_binding`
- confidence score
- manual repair workflow if needed later

### Risk 2. Parser evolution

PTA export format can change.

Mitigation:

- keep raw source file records
- keep raw row tables
- make normalization replayable

### Risk 3. Frontend field drift

Teacher pages currently expect legacy DTO shapes.

Mitigation:

- service layer adapts new schema into stable response DTOs first
- frontend migration can be incremental

### Risk 4. Analytics regression

Some charts currently depend on `score` and `problem_score_detail`.

Mitigation:

- keep derived tables during transition
- rebuild metrics from new normalized tables in parallel before switching

### Risk 5. Compatibility key drift

Legacy teacher pages and routes currently assume composite submission identifiers rather than new surrogate keys.

Mitigation:

- keep compatibility DTO fields stable during the first backend switch
- expose `student_profile.student_no` rather than `student_profile.id` to legacy teacher pages
- treat `/api/submissions/{submissionId}` as a synthetic compatibility contract until frontend migration is complete

## 15. Implementation Recommendation

This design should be implemented in this order:

1. Review and approve schema.
2. Add DDL as review draft.
3. Implement import tables and normalized tables.
4. Refactor PTA import path.
5. Switch teacher-side query services.
6. Switch analytics.
7. Migrate AI grading linkage.
8. Remove legacy table dependencies.

## 16. Decision Summary

Final decisions in this design:

- Keep `tap_user` and `teaching_class`
- Introduce canonical `student_profile` and `class_member`
- Split assignment into template and offering
- Keep PTA and AI grading submission domains separate
- Introduce replayable import lineage and raw parsed tables
- Make teacher pages read only normalized business tables

This is the target architecture for future schema work.

## 17. Review Notes For Execution

This document defines the target model, but it does not yet mean the SQL draft can be applied as-is.

Before implementation starts, the following execution notes must be respected:

- The review DDL must be converted into Flyway-compatible migrations.
- Some `ALTER TABLE ... ADD CONSTRAINT` statements in the review draft require idempotent wrappers before entering Flyway.
- Legacy tables must not be dropped in the first migration round.
- The first production switch should be read-path migration, not destructive schema cleanup.
- Teacher-facing DTOs should remain backward-compatible during the first backend switch.
- `grading_submission.student_id` foreign-key enforcement must be delayed until historical rows are backfilled and validated against `student_profile`.

## 18. Companion Documents

The following companion document should be used together with this design:

- [Unified Academic Implementation Plan](./unified-academic-implementation-plan.md)
- [Unified Academic Backfill And Validation SQL](./unified-academic-backfill-and-validation.sql)

That plan defines:

- phase-by-phase rollout
- exact Java, SQL, Python, and Vue file touchpoints
- API compatibility strategy
- test and rollback expectations
