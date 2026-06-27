package com.tap.backend.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ClassAssignmentCleanupRepository {

    private final JdbcClient jdbcClient;

    public ClassAssignmentCleanupRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void deleteAssignmentDataByClassId(Long classId) {
        dropTemporaryTables();
        createTemporaryTables();
        try {
            populateTargets(classId);
            collectArtifactIds();
            deleteRows();
        } finally {
            dropTemporaryTables();
        }
    }

    private void createTemporaryTables() {
        jdbcClient.sql("""
                CREATE TEMPORARY TABLE tmp_class_delete_offerings (
                    id BIGINT PRIMARY KEY
                )
                """).update();
        jdbcClient.sql("""
                CREATE TEMPORARY TABLE tmp_class_delete_import_jobs (
                    id BIGINT PRIMARY KEY
                )
                """).update();
        jdbcClient.sql("""
                CREATE TEMPORARY TABLE tmp_class_delete_artifacts (
                    id BIGINT PRIMARY KEY
                )
                """).update();
    }

    private void populateTargets(Long classId) {
        jdbcClient.sql("""
                INSERT IGNORE INTO tmp_class_delete_offerings (id)
                SELECT id
                FROM assignment_offering
                WHERE class_id = :classId
                """)
                .param("classId", classId)
                .update();
        jdbcClient.sql("""
                INSERT IGNORE INTO tmp_class_delete_import_jobs (id)
                SELECT id
                FROM import_job
                WHERE class_id = :classId
                """)
                .param("classId", classId)
                .update();
    }

    private void collectArtifactIds() {
        jdbcClient.sql("""
                INSERT IGNORE INTO tmp_class_delete_artifacts (id)
                SELECT a.id
                FROM artifact a
                WHERE a.owner_type = 'PTA_IMPORT_JOB'
                  AND a.owner_id IN (SELECT id FROM tmp_class_delete_import_jobs)
                """).update();
        jdbcClient.sql("""
                INSERT IGNORE INTO tmp_class_delete_artifacts (id)
                SELECT ras.html_artifact_id
                FROM pta_raw_answer_sheet ras
                WHERE ras.import_job_id IN (SELECT id FROM tmp_class_delete_import_jobs)
                  AND ras.html_artifact_id IS NOT NULL
                """).update();
        jdbcClient.sql("""
                INSERT IGNORE INTO tmp_class_delete_artifacts (id)
                SELECT ras.code_artifact_id
                FROM pta_raw_answer_sheet ras
                WHERE ras.import_job_id IN (SELECT id FROM tmp_class_delete_import_jobs)
                  AND ras.code_artifact_id IS NOT NULL
                """).update();
        jdbcClient.sql("""
                INSERT IGNORE INTO tmp_class_delete_artifacts (id)
                SELECT ras.test_report_artifact_id
                FROM pta_raw_answer_sheet ras
                WHERE ras.import_job_id IN (SELECT id FROM tmp_class_delete_import_jobs)
                  AND ras.test_report_artifact_id IS NOT NULL
                """).update();
        jdbcClient.sql("""
                INSERT IGNORE INTO tmp_class_delete_artifacts (id)
                SELECT sps.latest_code_artifact_id
                FROM student_problem_state sps
                WHERE sps.offering_id IN (SELECT id FROM tmp_class_delete_offerings)
                  AND sps.latest_code_artifact_id IS NOT NULL
                """).update();
        jdbcClient.sql("""
                INSERT IGNORE INTO tmp_class_delete_artifacts (id)
                SELECT sps.latest_answer_sheet_artifact_id
                FROM student_problem_state sps
                WHERE sps.offering_id IN (SELECT id FROM tmp_class_delete_offerings)
                  AND sps.latest_answer_sheet_artifact_id IS NOT NULL
                """).update();
    }

    private void deleteRows() {
        if (tableExists("pta_api_submission_row")) {
            jdbcClient.sql("""
                    DELETE FROM pta_api_submission_row
                    WHERE offering_id IN (SELECT id FROM tmp_class_delete_offerings)
                    """).update();
            jdbcClient.sql("""
                    DELETE FROM pta_api_submission_row
                    WHERE problem_id IN (
                        SELECT id
                        FROM assignment_problem
                        WHERE offering_id IN (SELECT id FROM tmp_class_delete_offerings)
                    )
                    """).update();
        }
        jdbcClient.sql("""
                DELETE FROM assignment_offering
                WHERE id IN (SELECT id FROM tmp_class_delete_offerings)
                """).update();
        jdbcClient.sql("""
                DELETE FROM import_job
                WHERE id IN (SELECT id FROM tmp_class_delete_import_jobs)
                """).update();
        jdbcClient.sql("""
                DELETE FROM artifact
                WHERE id IN (SELECT id FROM tmp_class_delete_artifacts)
                """).update();
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name = :tableName
                """)
                .param("tableName", tableName)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    private void dropTemporaryTables() {
        jdbcClient.sql("DROP TEMPORARY TABLE IF EXISTS tmp_class_delete_artifacts").update();
        jdbcClient.sql("DROP TEMPORARY TABLE IF EXISTS tmp_class_delete_import_jobs").update();
        jdbcClient.sql("DROP TEMPORARY TABLE IF EXISTS tmp_class_delete_offerings").update();
    }
}
