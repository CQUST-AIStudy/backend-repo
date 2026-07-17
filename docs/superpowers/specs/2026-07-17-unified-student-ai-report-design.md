# Unified Student AI Report Design

## Goal

Make the student AI report endpoints use the same unified academic data model as `GET /api/experiments`. A student whose experiment list shows an offering as completed with submitted code and a score must be able to generate and retrieve an AI report for that offering.

## Scope

This change covers:

- `POST /api/experiments/{offeringId}/report/generate`
- `GET /api/experiments/{offeringId}/report`
- the submit-time value returned by `GET /api/experiments`
- persistence of generated student experiment reports

It does not preserve a legacy read or write path through the `experiment`, `submission`, `score`, or `submit_situation` tables.

## Data Model

The route parameter is always `assignment_offering.id`.

The current student is resolved to `student_profile.id` through the authenticated student number. Report generation reads:

- experiment title and description from `assignment_offering` and `assignment_template`;
- assignment status and aggregate score from `student_assignment`;
- submitted source code from `student_problem_state` and its latest code `artifact`;
- the latest submit time from the latest problem attempt, falling back to `student_assignment.last_submit_at` and `first_submit_at`.

Generated reports are stored in a new `ai_experiment_report` table with a unique key on `(offering_id, student_id)`. The row stores the report body and creation/update timestamps. Regeneration updates the existing row.

## Service Behavior

`AiReportService` validates that the offering belongs to one of the authenticated student's active classes. It then loads the unified offering context and submitted code. Missing offerings, inaccessible offerings, and missing code return explicit failures.

The AI generator receives a unified report context rather than a legacy `Experiment`/`Submission` lookup. The report repository upserts and retrieves reports using `(offering_id, student_id)` only.

The GET endpoint does not consult the legacy `submission.report` column. The POST endpoint does not create legacy submission rows.

## API Compatibility

Endpoint paths and response shape remain unchanged so the current frontend store continues to work. Only the identifier semantics and backing data source are made consistent with the experiment list.

## Database Migration

Add a Flyway migration that creates `ai_experiment_report`, foreign keys to `assignment_offering` and `student_profile`, and a unique constraint on `(offering_id, student_id)`. Deleting an offering or student cascades to its generated reports.

No legacy report data is migrated because the requested behavior explicitly removes legacy compatibility.

## Testing

Service tests must first reproduce the current failure: an offering exists in the unified model with a score and artifact code, while no legacy experiment or submission exists. Generation must succeed, persist by offering/student, and GET must return the persisted report.

Additional tests cover missing code, inaccessible offerings, report regeneration/upsert, and missing saved reports. Controller contract tests verify that the authenticated student number and offering ID reach the unified service unchanged.

## Success Criteria

- An experiment displayed as completed with code in `/student/experiments` can generate a report in `/student/ai-report`.
- AI report endpoints do not query or update legacy experiment/submission persistence.
- The AI report page no longer shows “未提交” when unified problem attempts contain a submission time.
- Focused and relevant regression tests pass.
