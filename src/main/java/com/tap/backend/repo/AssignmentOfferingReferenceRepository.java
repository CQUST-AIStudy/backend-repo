package com.tap.backend.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AssignmentOfferingReferenceRepository {

    private final JdbcClient jdbcClient;

    public AssignmentOfferingReferenceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public boolean existsByClassId(Long classId) {
        Integer count = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM assignment_offering
                WHERE class_id = :classId
                """)
                .param("classId", classId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }
}
