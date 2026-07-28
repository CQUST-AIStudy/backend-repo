package com.tap.backend.service;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PtaUserGroupRosterService {

    private final JdbcTemplate jdbcTemplate;

    public PtaUserGroupRosterService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<RosterStudent> findActiveRoster(Long classId, String ptaGroupId) {
        if (classId == null || ptaGroupId == null || ptaGroupId.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT ugm.student_no, ugm.student_name, sp.user_id
                FROM pta_user_group ug
                JOIN pta_user_group_member ugm ON ugm.pta_user_group_id = ug.id
                LEFT JOIN student_profile sp ON sp.id = ugm.student_id
                WHERE ug.class_id = ?
                  AND ug.pta_group_id = ?
                  AND ugm.class_id = ?
                  AND ugm.member_status = 'ACTIVE'
                ORDER BY ugm.student_no
                """,
                (rs, rowNum) -> new RosterStudent(
                        rs.getString("student_no"),
                        rs.getString("student_name"),
                        rs.getObject("user_id", Long.class)
                ),
                classId,
                ptaGroupId.trim(),
                classId
        );
    }

    public record RosterStudent(String studentNum, String studentName, Long userId) {}
}
