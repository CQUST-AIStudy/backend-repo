package com.tap.backend.repo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;

class ClassAssignmentCleanupRepositoryTest {

    @Test
    void deleteAssignmentDataByClassIdCreatesTemporaryTablesWithoutMemoryEngine() {
        JdbcClient jdbcClient = mockJdbcClient(1);

        ClassAssignmentCleanupRepository repository = new ClassAssignmentCleanupRepository(jdbcClient);

        repository.deleteAssignmentDataByClassId(2L);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcClient, atLeastOnce()).sql(sqlCaptor.capture());

        List<String> createTableStatements = new ArrayList<>();
        for (String sql : sqlCaptor.getAllValues()) {
            if (sql.contains("CREATE TEMPORARY TABLE")) {
                createTableStatements.add(sql);
            }
        }

        assertFalse(createTableStatements.isEmpty(), "expected temporary tables to be created");
        for (String sql : createTableStatements) {
            assertFalse(sql.contains("ENGINE=MEMORY"), () -> "temporary table SQL should not require MEMORY engine: " + sql);
        }
    }

    @Test
    void deleteAssignmentDataByClassIdDoesNotReuseTemporaryTableTwiceInSingleStatement() {
        JdbcClient jdbcClient = mockJdbcClient(1);

        ClassAssignmentCleanupRepository repository = new ClassAssignmentCleanupRepository(jdbcClient);

        repository.deleteAssignmentDataByClassId(2L);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcClient, atLeastOnce()).sql(sqlCaptor.capture());

        List<String> deleteStatements = new ArrayList<>();
        for (String sql : sqlCaptor.getAllValues()) {
            if (sql.contains("DELETE FROM pta_api_submission_row")) {
                deleteStatements.add(sql);
            }
        }

        assertTrue(deleteStatements.size() >= 2, "expected pta_api_submission_row cleanup to be split into multiple statements");
        for (String sql : deleteStatements) {
            assertFalse(
                    appearsMoreThanOnce(sql, "tmp_class_delete_offerings"),
                    () -> "delete SQL should not reopen tmp_class_delete_offerings in one statement: " + sql
            );
        }
    }

    @Test
    void deleteAssignmentDataByClassIdSkipsPtaApiSubmissionCleanupWhenTableMissing() {
        JdbcClient jdbcClient = mockJdbcClient(0);

        ClassAssignmentCleanupRepository repository = new ClassAssignmentCleanupRepository(jdbcClient);

        repository.deleteAssignmentDataByClassId(2L);

        verify(jdbcClient, never()).sql(eq("""
                DELETE FROM pta_api_submission_row
                WHERE offering_id IN (SELECT id FROM tmp_class_delete_offerings)
                """));
        verify(jdbcClient, never()).sql(eq("""
                DELETE FROM pta_api_submission_row
                WHERE problem_id IN (
                    SELECT id
                    FROM assignment_problem
                    WHERE offering_id IN (SELECT id FROM tmp_class_delete_offerings)
                )
                """));
    }

    private boolean appearsMoreThanOnce(String sql, String token) {
        int first = sql.indexOf(token);
        if (first < 0) {
            return false;
        }
        return sql.indexOf(token, first + token.length()) >= 0;
    }

    @SuppressWarnings("unchecked")
    private JdbcClient mockJdbcClient(int tableExistsCount) {
        JdbcClient jdbcClient = mock(JdbcClient.class);
        JdbcClient.StatementSpec statementSpec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<Integer> countQuerySpec = mock(JdbcClient.MappedQuerySpec.class);

        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(statementSpec);
        when(statementSpec.update()).thenReturn(1);
        when(statementSpec.query(Integer.class)).thenReturn(countQuerySpec);
        when(countQuerySpec.single()).thenReturn(tableExistsCount);

        return jdbcClient;
    }
}
