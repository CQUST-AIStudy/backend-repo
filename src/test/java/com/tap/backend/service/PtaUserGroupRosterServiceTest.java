package com.tap.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PtaUserGroupRosterServiceTest {

    private JdbcTemplate jdbcTemplate;
    private PtaUserGroupRosterService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:pta-roster;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP ALL OBJECTS");
        jdbcTemplate.execute("""
                CREATE TABLE pta_user_group (
                  id BIGINT PRIMARY KEY,
                  class_id BIGINT,
                  pta_group_id VARCHAR(64) NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE pta_user_group_member (
                  id BIGINT PRIMARY KEY,
                  pta_user_group_id BIGINT NOT NULL,
                  class_id BIGINT,
                  student_id BIGINT,
                  student_no VARCHAR(32) NOT NULL,
                  student_name VARCHAR(128) NOT NULL,
                  member_status VARCHAR(16) NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE student_profile (
                  id BIGINT PRIMARY KEY,
                  user_id BIGINT
                )
                """);
        service = new PtaUserGroupRosterService(jdbcTemplate);
    }

    @Test
    void findActiveRosterUsesExactClassAndGroupAndExcludesLeftMembers() {
        jdbcTemplate.update("INSERT INTO pta_user_group VALUES (?, ?, ?)", 1L, 10L, "group-a");
        jdbcTemplate.update("INSERT INTO pta_user_group VALUES (?, ?, ?)", 2L, 20L, "group-a");
        jdbcTemplate.update("INSERT INTO student_profile VALUES (?, ?)", 100L, 1000L);
        jdbcTemplate.update(
                "INSERT INTO pta_user_group_member VALUES (?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, 10L, 100L, "20240001", "张三", "ACTIVE"
        );
        jdbcTemplate.update(
                "INSERT INTO pta_user_group_member VALUES (?, ?, ?, ?, ?, ?, ?)",
                2L, 1L, 10L, null, "20240002", "李四", "LEFT"
        );
        jdbcTemplate.update(
                "INSERT INTO pta_user_group_member VALUES (?, ?, ?, ?, ?, ?, ?)",
                3L, 2L, 20L, null, "20240003", "王五", "ACTIVE"
        );

        List<PtaUserGroupRosterService.RosterStudent> roster =
                service.findActiveRoster(10L, "group-a");

        assertThat(roster).containsExactly(
                new PtaUserGroupRosterService.RosterStudent("20240001", "张三", 1000L)
        );
    }
}
