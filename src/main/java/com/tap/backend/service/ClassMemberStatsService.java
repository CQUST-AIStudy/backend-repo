package com.tap.backend.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ClassMemberStatsService {

    private static final String ACTIVE_CLASS_MEMBER_COUNT_SQL = """
            SELECT COUNT(DISTINCT cm.student_id)
            FROM class_member cm
            JOIN student_profile sp
              ON sp.id = cm.student_id
            WHERE cm.class_id = ?
              AND cm.member_status = 'ACTIVE'
              AND sp.status <> 'DELETED'
            """;

    private static final String ACTIVE_BOUND_CLASS_MEMBER_COUNT_SQL = """
            SELECT COUNT(DISTINCT cm.student_id)
            FROM class_member cm
            JOIN student_profile sp
              ON sp.id = cm.student_id
            WHERE cm.class_id = ?
              AND cm.member_status = 'ACTIVE'
              AND sp.status <> 'DELETED'
              AND sp.user_id IS NOT NULL
            """;

    private final JdbcTemplate jdbcTemplate;

    public ClassMemberStatsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long countActiveStudents(Long classId) {
        return count(classId, ACTIVE_CLASS_MEMBER_COUNT_SQL);
    }

    public long countActiveStudentsBoundToUsers(Long classId) {
        return count(classId, ACTIVE_BOUND_CLASS_MEMBER_COUNT_SQL);
    }

    private long count(Long classId, String sql) {
        if (classId == null) {
            return 0L;
        }
        Long count = jdbcTemplate.queryForObject(sql, Long.class, classId);
        return count == null ? 0L : count;
    }
}
