# Unified Student AI Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make student AI report generation and retrieval use the same assignment-offering data model as the student experiment list.

**Architecture:** Introduce a focused unified report context/query boundary and a dedicated report repository keyed by offering and student profile. Refactor the generator and service to consume that context, remove legacy experiment/submission access, and derive list submit time from unified attempts.

**Tech Stack:** Java 17, Spring Boot, MyBatis annotations, Flyway SQL, JUnit 5, Mockito, Maven

## Global Constraints

- Route IDs are always `assignment_offering.id`.
- Student identity is resolved from the authenticated student number to `student_profile.id`.
- AI report reads and writes must not use legacy `experiment`, `submission`, `score`, or `submit_situation` tables.
- Existing endpoint paths and response JSON shape remain unchanged.
- No legacy report data migration or fallback is added.

---

### Task 1: Unified report persistence

**Files:**
- Create: `src/main/resources/db/migration/V60__student_ai_experiment_report.sql`
- Create: `src/main/java/com/tap/backend/academic/entity/AiExperimentReport.java`
- Create: `src/main/java/com/tap/backend/academic/dao/AiExperimentReportDao.java`
- Test: `src/test/java/com/tap/backend/academic/dao/AiExperimentReportDaoContractTest.java`

**Interfaces:**
- Produces: `AiExperimentReportDao.findByOfferingAndStudent(long offeringId, long studentId)` and `upsert(long offeringId, long studentId, String report)`.

- [ ] **Step 1: Write the failing mapper contract test**

Create a reflection-based test that asserts the DAO has both methods, `@Select` references `ai_experiment_report`, and `@Insert` contains `ON DUPLICATE KEY UPDATE`.

```java
@Test
void reportDaoUsesOfferingStudentKeyAndUpsert() throws Exception {
    Method find = AiExperimentReportDao.class.getMethod("findByOfferingAndStudent", long.class, long.class);
    Method upsert = AiExperimentReportDao.class.getMethod("upsert", long.class, long.class, String.class);
    assertTrue(find.getAnnotation(Select.class).value()[0].contains("ai_experiment_report"));
    assertTrue(String.join(" ", upsert.getAnnotation(Insert.class).value())
            .contains("ON DUPLICATE KEY UPDATE"));
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `mvn -Dtest=AiExperimentReportDaoContractTest test`

Expected: compilation failure because `AiExperimentReportDao` does not exist.

- [ ] **Step 3: Add migration, entity, and mapper**

The migration creates columns `id`, `offering_id`, `student_id`, `report_md`, `created_at`, and `updated_at`, a unique key on `(offering_id, student_id)`, and cascading foreign keys to `assignment_offering(id)` and `student_profile(id)`.

The mapper methods use these signatures:

```java
AiExperimentReport findByOfferingAndStudent(
        @Param("offeringId") long offeringId,
        @Param("studentId") long studentId);

int upsert(
        @Param("offeringId") long offeringId,
        @Param("studentId") long studentId,
        @Param("report") String report);
```

- [ ] **Step 4: Run the test and verify GREEN**

Run: `mvn -Dtest=AiExperimentReportDaoContractTest test`

Expected: `BUILD SUCCESS` with one passing test.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V60__student_ai_experiment_report.sql src/main/java/com/tap/backend/academic/entity/AiExperimentReport.java src/main/java/com/tap/backend/academic/dao/AiExperimentReportDao.java src/test/java/com/tap/backend/academic/dao/AiExperimentReportDaoContractTest.java
git commit -m "feat: persist unified student AI reports"
```

### Task 2: Unified report input query

**Files:**
- Create: `src/main/java/com/tap/backend/academic/service/AiReportContext.java`
- Create: `src/main/java/com/tap/backend/academic/dao/StudentAiReportQueryDao.java`
- Test: `src/test/java/com/tap/backend/academic/dao/StudentAiReportQueryDaoContractTest.java`

**Interfaces:**
- Produces: `AiReportContext findContext(String studentNo, long offeringId)` and `List<TeacherSubmissionProblemRow> findProblemRows(String studentNo, long offeringId)`.
- `AiReportContext` contains `studentProfileId`, `studentNo`, `studentName`, `offeringId`, `name`, `description`, `status`, `score`, and `submitTime`.

- [ ] **Step 1: Write failing query contract tests**

Assert the context SQL joins `student_profile`, `class_student`, `assignment_offering`, `assignment_template`, and `student_assignment`, filters by both student number and offering ID, and does not contain legacy table names. Assert problem SQL reads `student_problem_state` and `artifact.text_content` by the same keys.

```java
assertFalse(contextSql.matches("(?s).*\\b(experiment|submission|score|submit_situation)\\b.*"));
assertTrue(problemSql.contains("sps.offering_id = #{offeringId}"));
assertTrue(problemSql.contains("latest_code_artifact_id"));
```

- [ ] **Step 2: Run the test and verify RED**

Run: `mvn -Dtest=StudentAiReportQueryDaoContractTest test`

Expected: compilation failure because the DAO and context record do not exist.

- [ ] **Step 3: Implement context and query mapper**

Use `assignment_offering.id` as the only experiment identifier. Restrict the offering through the student's active class membership. Return the aggregate assignment values without consulting legacy fallbacks. Read ordered latest code artifacts for each problem.

- [ ] **Step 4: Run the test and verify GREEN**

Run: `mvn -Dtest=StudentAiReportQueryDaoContractTest test`

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tap/backend/academic/service/AiReportContext.java src/main/java/com/tap/backend/academic/dao/StudentAiReportQueryDao.java src/test/java/com/tap/backend/academic/dao/StudentAiReportQueryDaoContractTest.java
git commit -m "feat: query unified AI report context"
```

### Task 3: Generator and service migration

**Files:**
- Modify: `src/main/java/com/tap/backend/academic/service/AiReportGenerator.java`
- Modify: `src/main/java/com/tap/backend/academic/service/impl/DeepSeekAiReportGenerator.java`
- Modify: `src/main/java/com/tap/backend/academic/service/AiReportService.java`
- Modify: `src/test/java/com/tap/backend/academic/service/AiReportServiceTest.java`

**Interfaces:**
- Consumes: the two DAOs and `AiReportContext` from Tasks 1 and 2.
- Produces: `AiReportGenerator.generate(AiReportContext context, String code, Map<String,Object> userData)` and unchanged service entry points `generate(String studentNo, int offeringId, Map<String,Object>)` / `get(String studentNo, int offeringId)`.

- [ ] **Step 1: Replace service tests with failing unified-model cases**

Test generation when the context and artifact code exist but no legacy dependencies are present. Verify persistence uses the context's `studentProfileId` and route offering ID.

```java
when(queryDao.findContext("2025001", 4L)).thenReturn(context(23L, 4L));
when(queryDao.findProblemRows("2025001", 4L)).thenReturn(List.of(problem("int main(){}")));
when(generator.generate(any(), contains("int main"), anyMap())).thenReturn("# AI report");

AiReportResult result = service.generate("2025001", 4, Map.of());

assertTrue(result.success());
verify(reportDao).upsert(4L, 23L, "# AI report");
```

Add focused tests for inaccessible offering, missing code, GET without a saved report, GET with a saved report, and regeneration upsert.

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn -Dtest=AiReportServiceTest test`

Expected: compilation failures against the old constructor and generator signature.

- [ ] **Step 3: Implement the minimal unified service**

Remove `SubmissionDao`, `ExperimentService`, and `TeacherExperimentQueryDao` dependencies from `AiReportService`. Resolve the context once, concatenate nonblank problem code in display order, call the new generator signature, and upsert/read through `AiExperimentReportDao`.

Update `DeepSeekAiReportGenerator` to build the same prompt from `context.name()`, `context.description()`, and the explicit code string. Keep network configuration and response parsing unchanged.

- [ ] **Step 4: Run tests and verify GREEN**

Run: `mvn -Dtest=AiReportServiceTest test`

Expected: all `AiReportServiceTest` tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tap/backend/academic/service/AiReportGenerator.java src/main/java/com/tap/backend/academic/service/impl/DeepSeekAiReportGenerator.java src/main/java/com/tap/backend/academic/service/AiReportService.java src/test/java/com/tap/backend/academic/service/AiReportServiceTest.java
git commit -m "fix: generate AI reports from unified assignments"
```

### Task 4: Unified submit time in experiment list

**Files:**
- Modify: `src/main/java/com/tap/backend/academic/controller/ApiController.java`
- Test: `src/test/java/com/tap/backend/academic/controller/StudentExperimentListContractTest.java`

**Interfaces:**
- Produces: list field `submitTime` as the latest unified problem attempt time, falling back to assignment aggregate timestamps.

- [ ] **Step 1: Write a failing SQL contract test**

Extract the `getUnifiedStudentExperimentList` query text through a focused source contract test and assert it selects a correlated or aggregated `MAX(student_problem_attempt.submitted_at)` before assignment timestamps.

```java
assertTrue(source.contains("MAX(spa.submitted_at)"));
assertTrue(source.contains("COALESCE(latest_attempt"));
```

- [ ] **Step 2: Run the test and verify RED**

Run: `mvn -Dtest=StudentExperimentListContractTest test`

Expected: assertion failure because the current query only selects `first_submit_at` and `last_submit_at`.

- [ ] **Step 3: Update the unified list query**

Join an offering/student grouped subquery over `student_problem_attempt` and select `COALESCE(latest_attempt.submitted_at, sa.last_submit_at, sa.first_submit_at)`. Update the row indexes and use that value for `submitTime`; do not change response keys.

- [ ] **Step 4: Run the test and verify GREEN**

Run: `mvn -Dtest=StudentExperimentListContractTest test`

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tap/backend/academic/controller/ApiController.java src/test/java/com/tap/backend/academic/controller/StudentExperimentListContractTest.java
git commit -m "fix: expose unified experiment submit time"
```

### Task 5: Controller and regression verification

**Files:**
- Modify: `src/test/java/com/tap/backend/academic/controller/AiReportControllerTest.java`
- Modify only if required by a failing contract: `src/main/java/com/tap/backend/academic/controller/ApiController.java`

**Interfaces:**
- Consumes: unchanged service entry points from Task 3.
- Produces: unchanged HTTP response contract for report GET and POST.

- [ ] **Step 1: Add controller contract assertions**

Verify the authenticated student number and route offering ID are passed unchanged to `AiReportService`, and success/failure results keep `success`, `message`, `report`, and `data` fields.

- [ ] **Step 2: Run focused controller tests**

Run: `mvn -Dtest=AiReportControllerTest test`

Expected: PASS; if a contract assertion fails, make only the minimal controller adjustment and rerun.

- [ ] **Step 3: Run the complete focused regression set**

Run: `mvn -Dtest=AiExperimentReportDaoContractTest,StudentAiReportQueryDaoContractTest,AiReportServiceTest,StudentExperimentListContractTest,AiReportControllerTest test`

Expected: `BUILD SUCCESS`, no test failures or errors.

- [ ] **Step 4: Run project compilation**

Run: `mvn -DskipTests package`

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit final contract changes**

```bash
git add src/test/java/com/tap/backend/academic/controller/AiReportControllerTest.java src/main/java/com/tap/backend/academic/controller/ApiController.java
git commit -m "test: verify unified student AI report endpoints"
```
