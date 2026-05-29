# Unified Academic Implementation Plan

## 1. Document Purpose

This document translates the unified academic data model into an execution plan.

It answers four practical questions:

1. What should be changed first?
2. Which files should be modified in each phase?
3. Which APIs should remain backward-compatible during transition?
4. How should rollout and rollback be controlled?

This plan is written to support later Codex-assisted execution. It is intentionally explicit at the file and subsystem level.

## 2. Implementation Strategy

The migration must follow this rule:

- first add the new schema
- then write new data into the new schema
- then switch read paths
- then attach AI grading linkage
- only then retire legacy dependencies

The migration must not start by deleting legacy tables or by rewriting the frontend first.

## 3. Phase Overview

### Phase 0. Freeze review artifacts

Goal:

- stop model drift before coding

Deliverables:

- approved design document
- approved DDL review file
- approved implementation plan

Questions that must be explicitly confirmed before coding:

- `student_profile.id` becomes the canonical student primary key
- `assignment_template` and `assignment_offering` names are accepted
- `grading_task.assignment_offering_id` is allowed
- old tables remain during compatibility period
- compatibility teacher DTOs continue exposing `studentId = student_profile.student_no`
- `/api/submissions/{submissionId}` remains a synthetic `studentId-experimentId` route key during the first backend switch
- the `submissionId` compatibility route must treat the student segment as a string business key, not an `int`

Exit criteria:

- no unresolved naming or boundary disputes remain

### Phase 1. Formalize schema migrations

Goal:

- convert review DDL into executable Flyway migrations

Recommended Flyway split:

- `V19__unified_academic_core.sql`
- `V20__pta_import_lineage_and_raw.sql`
- `V21__grading_assignment_offering_link.sql`
- `V22__grading_submission_student_fk.sql` only after student backfill and orphan validation

Primary tables introduced in this phase:

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
- `import_job`
- `import_source_file`
- `pta_raw_submission_row`
- `pta_raw_transcript_row`
- `pta_raw_answer_sheet`

Existing tables extended:

- `teaching_class`
- `grading_task`

Rules:

- do not drop old tables
- do not switch application queries yet
- wrap constraint creation with Flyway-safe idempotent checks where needed
- do not add the `grading_submission.student_id -> student_profile(id)` FK in the first migration batch
- keep raw PTA lineage per `import_job`, but make each raw answer-sheet entry idempotent within one `import_source_file`

Files to create or modify:

- `AI_Ds/src/main/resources/db/migration/V19__unified_academic_core.sql`
- `AI_Ds/src/main/resources/db/migration/V20__pta_import_lineage_and_raw.sql`
- `AI_Ds/src/main/resources/db/migration/V21__grading_assignment_offering_link.sql`
- `AI_Ds/src/main/resources/db/migration/V22__grading_submission_student_fk.sql`
- `AI_Ds/docs/unified-academic-backfill-and-validation.sql`

Exit criteria:

- migrations run on local database
- no existing application path is broken

### Phase 2. Build new persistence layer

Goal:

- add Java entities and repositories for the new schema

Recommended package root:

- `com.tap.backend.domain.academic`
- `com.tap.backend.repo.academic`
- `com.tap.backend.service.academic`

New Java entities expected:

- `AcademicTermEntity`
- `CourseEntity`
- `StudentProfileEntity`
- `ClassMemberEntity`
- `AssignmentTemplateEntity`
- `AssignmentOfferingEntity`
- `AssignmentProblemEntity`
- `StudentAssignmentEntity`
- `StudentProblemAttemptEntity`
- `StudentProblemStateEntity`
- `ArtifactEntity`
- `ExternalIdentityBindingEntity`
- `ImportJobEntity`
- `ImportSourceFileEntity`
- `PtaRawSubmissionRowEntity`
- `PtaRawTranscriptRowEntity`
- `PtaRawAnswerSheetEntity`

New repositories expected:

- one JPA repository per entity for the new model

Files to create:

- `AI_Ds/src/main/java/com/tap/backend/domain/academic/*.java`
- `AI_Ds/src/main/java/com/tap/backend/repo/academic/*.java`

Files likely to modify:

- existing domain or repo configuration only if package scanning needs extension

Exit criteria:

- application starts successfully with new entities
- no read path has been switched yet

### Phase 3. Refactor PTA import into the new model

Goal:

- stop treating crawler output as legacy business writes
- backfill the academic core so new read paths do not depend on future imports only

Current source scripts:

- [services/pta_spider/sync_to_db.py](/g:/myapps/services/pta_spider/sync_to_db.py)
- [services/pta_spider/spider.py](/g:/myapps/services/pta_spider/spider.py)
- [services/pta_spider/spider_api.py](/g:/myapps/services/pta_spider/spider_api.py)

Preferred implementation approach:

- keep current script intact as reference
- add a new normalized import module instead of mixing old and new logic in one pass

Recommended file plan:

- add `services/pta_spider/sync_to_unified_db.py`
- optionally add `services/pta_spider/import_normalizers.py`
- optionally add `services/pta_spider/import_shared.py`

Current draft implementation note:

- `services/pta_spider/sync_to_unified_db.py` is the new unified importer draft
- it is dispatched through `ACADEMIC_UNIFIED_IMPORT_ENABLED`
- legacy writes are still kept by default through `ACADEMIC_LEGACY_WRITE_ENABLED=true` unless explicitly disabled

Normalization steps:

1. create `import_job`
2. register all source files into `import_source_file`
3. parse and persist raw PTA rows
4. resolve students into `student_profile`
5. persist `external_identity_binding`
6. resolve or create `assignment_template`
7. resolve or create `assignment_offering`
8. resolve or create `assignment_problem`
9. materialize `student_assignment` for every active roster member of the offering's class
10. upsert `student_problem_attempt`
11. recalculate `student_problem_state`
12. recalculate `student_assignment`
13. persist answer-sheet/code/output artifacts

Additional persistence rules in this phase:

- PTA imports must always write a canonical `assignment_offering.source_offering_key`, for example `PTA:{class_id}:{pta_problem_set_id}`
- normalized attempt/state upserts must respect the canonical offering roster boundary, not just independent student and offering foreign keys
- `student_assignment` refresh must use upsert or merge semantics; do not delete-and-recreate it after attempts or states exist
- `student_problem_attempt.source_attempt_key` must exclude mutable judge-result fields like score, runtime, memory, and rejudge status
- raw PTA import artifacts must use job-scoped `artifact.source_key` values so reruns do not overwrite older `import_job` lineage
- rerunning the same logical `提交记录.csv` should prune stale normalized PTA attempts from older imports of that same source file path after current rows have been rebound
- after attempt pruning, `student_problem_state` rows with no remaining backing attempts in the same offering must also be pruned before recalculation

Historical backfill that must also happen in this phase:

1. backfill `student_profile` from existing `student` and roster-like sources
2. backfill `class_member` from `class_student` before read-path switch
3. backfill `assignment_template` and `assignment_offering` from existing experiment metadata where needed
4. pre-materialize `student_assignment` for active class rosters even if no PTA attempt exists yet

Compatibility rule:

- old legacy writes may remain behind a switch at first
- recommended flag:
  - `LEGACY_WRITE_ENABLED=true|false`

Files to modify:

- `services/pta_spider/sync_to_db.py`
- `services/pta_spider/spider_api.py` if import entrypoint changes

Files to add:

- `services/pta_spider/sync_to_unified_db.py`
- optional helper modules

Exit criteria:

- one PTA import can populate the new tables end-to-end
- import can be rerun without duplicate normalized rows
- historical active classes can be represented in the new tables without waiting for a fresh crawler run

### Phase 4. Switch teacher-side backend read paths

Goal:

- fix teacher-side experiment and submission visibility using the unified model

This phase is the first user-visible fix phase.

#### 4.1 Teacher experiment list

Current files:

- [ExperimentController.java](/g:/myapps/AI_Ds/src/main/java/com/tap/backend/academic/controller/Teacher/ExperimentController.java)
- [TeacherExperimentQueryService.java](/g:/myapps/AI_Ds/src/main/java/com/tap/backend/academic/service/teacherexperiment/TeacherExperimentQueryService.java)
- [TeacherExperimentQueryServiceImpl.java](/g:/myapps/AI_Ds/src/main/java/com/tap/backend/academic/service/teacherexperiment/TeacherExperimentQueryServiceImpl.java)
- [TeacherExperimentQueryDao.java](/g:/myapps/AI_Ds/src/main/java/com/tap/backend/academic/dao/teacherexperiment/TeacherExperimentQueryDao.java)

Action:

- stop deriving data from `teacher`, `student`, `score`
- derive data from:
  - `assignment_offering`
  - `student_assignment`
  - `class_member`
  - `student_profile`

Preferred implementation:

- create new academic service under `com.tap.backend.service.academic`
- adapt controller response shape to match current frontend expectations
- keep compatibility DTOs stable:
  - `studentId` should still expose the stable business key from `student_profile.student_no`
  - `experimentId` should still expose `assignment_offering.id`
  - the `/api/submissions/{submissionId}` parser must keep `studentId` as a string segment and only parse the experiment segment numerically if needed

#### 4.2 Teacher class roster and student count

Current files:

- [TeacherController.java](/g:/myapps/AI_Ds/src/main/java/com/tap/backend/academic/controller/Teacher/TeacherController.java)
- [StudentDao.java](/g:/myapps/AI_Ds/src/main/java/com/tap/backend/academic/dao/StudentDao.java)
- [StudentMapper.xml](/g:/myapps/AI_Ds/src/main/resources/mappers/StudentMapper.xml)

Action:

- stop using `student.class_name = teacher.classroom`
- resolve roster via:
  - `teaching_class`
  - `class_member`
  - `student_profile`

#### 4.3 Submission detail

Current files:

- [ApiController.java](/g:/myapps/AI_Ds/src/main/java/com/tap/backend/academic/controller/ApiController.java)
- [SubmissionMapper.xml](/g:/myapps/AI_Ds/src/main/resources/mappers/SubmissionMapper.xml)

Action:

- detail should read from:
  - `student_assignment`
  - `student_problem_state`
  - `student_problem_attempt`
  - `artifact`
- legacy `submission` and `student_code` become fallback-only
- keep `/api/submissions/{submissionId}` as a synthetic compatibility route using `studentId-experimentId`, not a new surrogate primary key
- do not keep the current `Integer.parseInt(studentId)` assumption in the compatibility route implementation

Exit criteria:

- `/api/teacher/experiments` works from the new model
- `/api/teacher/allStudentExperiments` works from the new model
- `/api/submissions/{submissionId}` works from the new model or unified fallback

### Phase 5. Keep frontend stable while switching backend

Goal:

- minimize frontend churn during the backend transition

Current frontend touchpoints:

- [AI_Ds-vue/src/api/index.js](/g:/myapps/AI_Ds-vue/src/api/index.js)
- [AI_Ds-vue/src/views/teacher/SubmissionList.vue](/g:/myapps/AI_Ds-vue/src/views/teacher/SubmissionList.vue)
- [AI_Ds-vue/src/views/teacher/ExperimentDetail.vue](/g:/myapps/AI_Ds-vue/src/views/teacher/ExperimentDetail.vue)
- [AI_Ds-vue/src/views/teacher/ClassDetailedAnalysis.vue](/g:/myapps/AI_Ds-vue/src/views/teacher/ClassDetailedAnalysis.vue)

Rule:

- keep existing API paths initially
- adapt backend DTOs first
- only then update frontend field names if necessary

Compatibility priority:

- `GET /api/teacher/experiments`
- `GET /api/teacher/allStudentExperiments`
- `GET /api/teacher/studentList`
- `GET /api/submissions/{submissionId}`

Frontend should not be the first migration target.

Exit criteria:

- existing teacher pages work with minimal or zero route changes

### Phase 6. Attach AI grading to the academic model

Goal:

- align AI grading with the new academic dimensions

Current files:

- [GradingTaskEntity.java](/g:/myapps/AI_Ds/src/main/java/com/tap/backend/domain/grading/GradingTaskEntity.java)
- [GradingSubmissionEntity.java](/g:/myapps/AI_Ds/src/main/java/com/tap/backend/domain/grading/GradingSubmissionEntity.java)
- [GradingSubmissionService.java](/g:/myapps/AI_Ds/src/main/java/com/tap/backend/service/GradingSubmissionService.java)

Action:

- use `assignment_offering_id` on `grading_task`
- ensure `grading_submission.student_id` points to `student_profile.id`
- allow teacher report grading to share the same student/class/assignment coordinates as PTA data

Exit criteria:

- AI grading and PTA records can be queried under the same student-assignment view

### Phase 7. Migrate analytics

Goal:

- move analytics off legacy `score` and `problem_score_detail` dependence

Likely affected areas:

- teacher analytics endpoints
- class analysis pages
- experiment detail summaries

Preferred new sources:

- `student_assignment`
- `student_problem_state`
- `student_problem_attempt`

Transition strategy:

- run old and new analytics in parallel for validation
- switch after metric parity is acceptable

### Phase 8. Retire legacy dependencies

Goal:

- remove old business-table reads from application code

Tables to retire from primary business usage:

- `teacher`
- `student`
- `user`
- `experiment`
- `score`
- `submission`
- `student_code`
- `submit_situation`
- `problems_sets`

Rules:

- first stop reads
- then stop writes
- only much later consider table archival or drop

## 4. File-Level Change Matrix

### 4.1 SQL

Create:

- `AI_Ds/src/main/resources/db/migration/V19__unified_academic_core.sql`
- `AI_Ds/src/main/resources/db/migration/V20__pta_import_lineage_and_raw.sql`
- `AI_Ds/src/main/resources/db/migration/V21__grading_assignment_offering_link.sql`
- `AI_Ds/src/main/resources/db/migration/V22__grading_submission_student_fk.sql`
- `AI_Ds/docs/unified-academic-backfill-and-validation.sql`

Reference input:

- [unified-academic-data-model-ddl-review.sql](/g:/myapps/AI_Ds/docs/unified-academic-data-model-ddl-review.sql)

### 4.2 Python import

Modify:

- `services/pta_spider/sync_to_db.py`
- `services/pta_spider/spider_api.py` if import invocation changes

Add:

- `services/pta_spider/sync_to_unified_db.py`
- optional `services/pta_spider/import_normalizers.py`
- optional `services/pta_spider/import_shared.py`

### 4.3 Java backend

Legacy controllers/services that must be migrated:

- `AI_Ds/src/main/java/com/tap/backend/academic/controller/Teacher/ExperimentController.java`
- `AI_Ds/src/main/java/com/tap/backend/academic/controller/Teacher/TeacherController.java`
- `AI_Ds/src/main/java/com/tap/backend/academic/controller/ApiController.java`
- `AI_Ds/src/main/java/com/tap/backend/academic/service/teacherexperiment/TeacherExperimentQueryService.java`
- `AI_Ds/src/main/java/com/tap/backend/academic/service/teacherexperiment/TeacherExperimentQueryServiceImpl.java`
- `AI_Ds/src/main/java/com/tap/backend/academic/dao/teacherexperiment/TeacherExperimentQueryDao.java`
- `AI_Ds/src/main/resources/mappers/StudentMapper.xml`
- `AI_Ds/src/main/resources/mappers/SubmissionMapper.xml`

New backend package areas recommended:

- `AI_Ds/src/main/java/com/tap/backend/domain/academic/`
- `AI_Ds/src/main/java/com/tap/backend/repo/academic/`
- `AI_Ds/src/main/java/com/tap/backend/service/academic/`
- `AI_Ds/src/main/java/com/tap/backend/api/academic/`

### 4.4 Vue frontend

Likely touchpoints:

- `AI_Ds-vue/src/api/index.js`
- `AI_Ds-vue/src/views/teacher/SubmissionList.vue`
- `AI_Ds-vue/src/views/teacher/ExperimentDetail.vue`
- `AI_Ds-vue/src/views/teacher/ClassDetailedAnalysis.vue`

Migration policy:

- backend DTO compatibility first
- frontend refactor second

## 5. API Compatibility Strategy

The first backend switch should preserve current API paths.

Recommended compatibility policy:

- keep path stable
- keep top-level response shape stable
- translate new schema into old DTO fields where needed
- do not expose `student_profile.id` directly to legacy teacher pages during the first switch

Examples:

- `assignment_offering.id` may still be exposed as `experimentId` in legacy teacher pages during transition
- `student_profile.student_no` may still be exposed as `studentId` in legacy teacher pages during transition
- `student_assignment.best_total_score` may be exposed as `score`
- `student_assignment.submission_status` may be mapped to existing page statuses
- `/api/submissions/{submissionId}` should continue accepting `studentId-experimentId` as a compatibility contract
- the `studentId` segment of that compatibility key is a business string, even if current sample data happens to be numeric

Only after frontend stabilization should APIs be renamed or split.

## 6. Suggested First Execution Batch

The first execution batch should be intentionally small but high-value.

Recommended scope:

1. create `V19`, `V20`, `V21`
2. backfill `student_profile`, `class_member`, and roster-backed `student_assignment`
3. implement normalized PTA import writes
4. switch:
   - `GET /api/teacher/experiments`
   - `GET /api/teacher/allStudentExperiments`
   - `GET /api/submissions/{submissionId}`

Why this first:

- it fixes the most visible teacher-side failure
- it validates the new schema with real crawler data
- it avoids premature full-project refactors

## 7. Test Strategy

### 7.1 Database validation

- migrations run cleanly on a local copy of `ptadatabase`
- old tables remain intact
- new tables are populated after import
- `V22__grading_submission_student_fk.sql` must fail fast if `grading_submission.student_id` still contains orphan values
- run the queries in [unified-academic-backfill-and-validation.sql](/g:/myapps/AI_Ds/docs/unified-academic-backfill-and-validation.sql) before enabling V22 or the new read path

### 7.2 Import validation

Use real crawler output from `services/pta_spider` and `爬取结果/`.

Validate:

- import job creation
- raw row persistence
- correct student identity mapping
- assignment/problem resolution
- idempotent re-import
- roster materialization for students with zero attempts

### 7.3 Backend validation

Validate teacher endpoints against real local data:

- teacher experiment list returns offerings
- student experiment list returns roster-linked rows
- submission detail returns code, status, score, and attempt history
- students with no attempts still appear with a not-started state
- compatibility `submissionId` values still resolve after read-path migration

### 7.4 Frontend validation

Validate:

- teacher experiment page renders without field-shape regressions
- submission list and detail pages still work

### 7.5 AI grading migration validation

Validate before adding the later foreign key:

- every non-null `grading_submission.student_id` has been mapped to `student_profile.id`
- no orphan `grading_submission.student_id` values remain
- application writes now use canonical `student_profile.id`

## 8. Rollback Strategy

Rollback policy must be phase-specific.

### Schema phase rollback

- Flyway migration rollback should be handled via forward-fix if already applied in shared environments
- local environment may be reset from snapshot if needed

### Import phase rollback

- keep legacy writes available behind a switch during transition
- import into new tables should be repeatable after truncating only new normalized/import tables if needed

### Read-path rollback

- preserve old endpoints or old service implementations behind a feature flag during first switch

Recommended flags:

- `ACADEMIC_UNIFIED_READ_ENABLED`
- `ACADEMIC_UNIFIED_IMPORT_ENABLED`
- `ACADEMIC_LEGACY_WRITE_ENABLED`
- `ACADEMIC_COMPAT_SUBMISSION_KEY_ENABLED`

## 9. Open Items Before Coding

These should be explicitly resolved when execution begins:

- whether `tap_user` should later allow formal `STUDENT` role
- whether `class_student` should be deprecated immediately or mirrored temporarily
- whether `problem_score_detail` should be rebuilt or temporarily preserved
- whether new academic APIs should be introduced now, or only after legacy path stabilization
- whether any PTA identifier truly deserves global uniqueness, or should remain an indexed source attribute only

## 10. Final Execution Rule

Do not start by deleting old tables.

Do not start by rewriting the frontend.

Do not start by merging PTA attempts and AI grading submissions into one table.

Start with:

- schema
- import
- teacher read paths

That order minimizes project risk and gives the fastest visible recovery.
