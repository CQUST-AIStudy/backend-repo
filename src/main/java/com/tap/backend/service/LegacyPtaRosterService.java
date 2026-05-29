package com.tap.backend.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LegacyPtaRosterService {

    private final JdbcTemplate jdbcTemplate;

    public LegacyPtaRosterService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<RosterStudent> findRoster(String className, String ptaKeyword) {
        Set<String> candidates = new LinkedHashSet<>();
        addCandidate(candidates, className);
        addCandidate(candidates, ptaKeyword);

        Map<String, RosterStudent> roster = new LinkedHashMap<>();
        for (String candidate : candidates) {
            loadFromStudentTable(candidate, roster);
        }
        if (!roster.isEmpty()) {
            return new ArrayList<>(roster.values());
        }

        for (String candidate : candidates) {
            loadFromSubmissions(candidate, roster);
            loadFromScores(candidate, roster);
            loadFromStudentCode(candidate, roster);
        }
        return new ArrayList<>(roster.values());
    }

    private void loadFromStudentTable(String className, Map<String, RosterStudent> roster) {
        jdbcTemplate.query(
                """
                SELECT CAST(student_id AS CHAR) AS student_num,
                       COALESCE(NULLIF(TRIM(name), ''), CAST(student_id AS CHAR)) AS student_name
                FROM student
                WHERE class_name = ?
                """,
                (rs, rowNum) -> {
                    merge(roster, rs.getString("student_num"), rs.getString("student_name"));
                    return null;
                },
                className
        );
    }

    private void loadFromSubmissions(String keyword, Map<String, RosterStudent> roster) {
        jdbcTemplate.query(
                """
                SELECT DISTINCT student_id AS student_num,
                       COALESCE(NULLIF(TRIM(student_name), ''), student_id) AS student_name
                FROM submit_situation
                WHERE experiment_name LIKE ?
                """,
                (rs, rowNum) -> {
                    merge(roster, rs.getString("student_num"), rs.getString("student_name"));
                    return null;
                },
                likeKeyword(keyword)
        );
    }

    private void loadFromScores(String keyword, Map<String, RosterStudent> roster) {
        jdbcTemplate.query(
                """
                SELECT DISTINCT s.username AS student_num,
                       COALESCE(NULLIF(TRIM(s.real_name), ''), s.username) AS student_name
                FROM score s
                JOIN experiment e ON e.experiment_id = s.experiment_id
                WHERE e.name LIKE ?
                """,
                (rs, rowNum) -> {
                    merge(roster, rs.getString("student_num"), rs.getString("student_name"));
                    return null;
                },
                likeKeyword(keyword)
        );
    }

    private void loadFromStudentCode(String keyword, Map<String, RosterStudent> roster) {
        jdbcTemplate.query(
                """
                SELECT DISTINCT student_id AS student_num,
                       COALESCE(NULLIF(TRIM(student_name), ''), student_id) AS student_name
                FROM student_code
                WHERE experiment_name LIKE ?
                """,
                (rs, rowNum) -> {
                    merge(roster, rs.getString("student_num"), rs.getString("student_name"));
                    return null;
                },
                likeKeyword(keyword)
        );
    }

    private void merge(Map<String, RosterStudent> roster, String studentNumRaw, String studentNameRaw) {
        String studentNum = normalizeStudentNum(studentNumRaw);
        if (studentNum == null) {
            return;
        }

        String studentName = normalizeStudentName(studentNameRaw, studentNum);
        RosterStudent existing = roster.get(studentNum);
        if (existing == null) {
            roster.put(studentNum, new RosterStudent(studentNum, studentName));
            return;
        }

        if (isFallbackName(existing.studentName(), existing.studentNum()) && !isFallbackName(studentName, studentNum)) {
            roster.put(studentNum, new RosterStudent(studentNum, studentName));
        }
    }

    private void addCandidate(Set<String> candidates, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (!trimmed.isBlank()) {
            candidates.add(trimmed);
        }
    }

    private String likeKeyword(String keyword) {
        return "%" + keyword + "%";
    }

    private String normalizeStudentNum(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.endsWith(".0")) {
            String maybeInteger = normalized.substring(0, normalized.length() - 2);
            if (maybeInteger.chars().allMatch(Character::isDigit)) {
                normalized = maybeInteger;
            }
        }
        return normalized;
    }

    private String normalizeStudentName(String value, String studentNum) {
        if (value == null) {
            return studentNum;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? studentNum : normalized;
    }

    private boolean isFallbackName(String studentName, String studentNum) {
        return studentName == null || studentName.isBlank() || studentName.equals(studentNum);
    }

    public record RosterStudent(String studentNum, String studentName) {}
}
