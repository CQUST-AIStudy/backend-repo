package com.tap.backend.service;

import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.domain.grading.GradingTaskEntity;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class GradingUnifiedLinkService {

    private static final Pattern STUDENT_NO_PATTERN = Pattern.compile("(?<!\\d)(\\d{6,20})(?!\\d)");

    private final JdbcTemplate jdbcTemplate;

    public GradingUnifiedLinkService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long resolveAssignmentOfferingId(Long experimentId, Long classId, Long teacherId) {
        if (experimentId == null) {
            return null;
        }
        List<Long> ids = jdbcTemplate.query(
                """
                SELECT ao.id
                FROM assignment_offering ao
                WHERE ao.source_system = 'LEGACY_TAP'
                  AND ao.source_offering_key IN (
                    CONCAT('LEGACY_EXPERIMENT_OFFERING:', ?, ':CLASS:', ?),
                    CONCAT('LEGACY_EXPERIMENT_OFFERING:', ?)
                  )
                ORDER BY
                  CASE WHEN ? IS NOT NULL AND ao.class_id = ? THEN 0 ELSE 1 END,
                  CASE WHEN ? IS NOT NULL AND ao.teacher_id = ? THEN 0 ELSE 1 END,
                  ao.id
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getLong("id"),
                experimentId,
                classId,
                experimentId,
                classId,
                classId,
                teacherId,
                teacherId
        );
        return ids.isEmpty() ? null : ids.get(0);
    }

    public String resolveClassName(Long assignmentOfferingId, Long classId) {
        if (assignmentOfferingId != null) {
            List<String> names = jdbcTemplate.query(
                    """
                    SELECT tc.name
                    FROM assignment_offering ao
                    JOIN teaching_class tc
                      ON tc.id = ao.class_id
                    WHERE ao.id = ?
                    LIMIT 1
                    """,
                    (rs, rowNum) -> rs.getString("name"),
                    assignmentOfferingId
            );
            if (!names.isEmpty()) {
                return normalizeText(names.get(0));
            }
        }
        if (classId == null) {
            return null;
        }
        List<String> names = jdbcTemplate.query(
                "SELECT name FROM teaching_class WHERE id = ? LIMIT 1",
                (rs, rowNum) -> rs.getString("name"),
                classId
        );
        return names.isEmpty() ? null : normalizeText(names.get(0));
    }

    public SubmissionIdentity resolveSubmissionIdentity(GradingTaskEntity task, GradingSubmissionEntity submission) {
        if (submission == null) {
            return null;
        }
        Long assignmentOfferingId = task != null ? task.getAssignmentOfferingId() : null;
        Long classId = task != null ? task.getClassId() : null;

        SubmissionIdentity identity = findByCanonicalId(submission.getStudentId(), assignmentOfferingId, classId);
        if (identity == null) {
            identity = findByStudentNo(extractCandidateStudentNo(submission), assignmentOfferingId, classId);
        }
        if (identity == null && submission.getStudentId() != null) {
            identity = findByStudentNo(String.valueOf(submission.getStudentId()), assignmentOfferingId, classId);
        }
        if (identity == null) {
            identity = findUniqueByName(normalizeText(submission.getStudentName()), assignmentOfferingId, classId);
        }
        if (identity == null && assignmentOfferingId == null && classId == null) {
            identity = findByStudentNo(normalizeStudentNo(submission.getStudentNo()), null, null);
        }
        if (identity == null) {
            return null;
        }
        String className = firstNonBlank(
                identity.className(),
                resolveClassName(assignmentOfferingId, classId),
                submission.getClassName()
        );
        String studentName = firstNonBlank(identity.studentName(), submission.getStudentName());
        return new SubmissionIdentity(
                identity.studentProfileId(),
                identity.studentNo(),
                studentName,
                className,
                identity.username(),
                identity.legacyStudentId()
        );
    }

    private SubmissionIdentity findByCanonicalId(Long studentProfileId, Long assignmentOfferingId, Long classId) {
        if (studentProfileId == null) {
            return null;
        }
        List<SubmissionIdentity> rows = jdbcTemplate.query(
                """
                SELECT sp.id,
                       sp.student_no,
                       sp.real_name,
                       tu.username
                FROM student_profile sp
                LEFT JOIN tap_user tu
                  ON tu.id = sp.user_id
                WHERE sp.id = ?
                LIMIT 1
                """,
                (rs, rowNum) -> toIdentity(
                        rs.getLong("id"),
                        rs.getString("student_no"),
                        rs.getString("real_name"),
                        null,
                        rs.getString("username"),
                        assignmentOfferingId,
                        classId
                ),
                studentProfileId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private SubmissionIdentity findByStudentNo(String studentNo, Long assignmentOfferingId, Long classId) {
        String normalizedStudentNo = normalizeStudentNo(studentNo);
        if (normalizedStudentNo == null) {
            return null;
        }
        List<SubmissionIdentity> rows = jdbcTemplate.query(
                """
                SELECT sp.id,
                       sp.student_no,
                       sp.real_name,
                       tu.username
                FROM student_profile sp
                LEFT JOIN tap_user tu
                  ON tu.id = sp.user_id
                WHERE sp.student_no = ?
                LIMIT 1
                """,
                (rs, rowNum) -> toIdentity(
                        rs.getLong("id"),
                        rs.getString("student_no"),
                        rs.getString("real_name"),
                        null,
                        rs.getString("username"),
                        assignmentOfferingId,
                        classId
                ),
                normalizedStudentNo
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private SubmissionIdentity findUniqueByName(String studentName, Long assignmentOfferingId, Long classId) {
        if (studentName == null) {
            return null;
        }
        String sql;
        Object[] args;
        if (assignmentOfferingId != null) {
            sql = """
                    SELECT DISTINCT sp.id,
                                    sp.student_no,
                                    sp.real_name,
                                    tu.username
                    FROM assignment_offering ao
                    JOIN class_member cm
                      ON cm.class_id = ao.class_id
                     AND cm.member_status = 'ACTIVE'
                    JOIN student_profile sp
                      ON sp.id = cm.student_id
                    LEFT JOIN tap_user tu
                      ON tu.id = sp.user_id
                    WHERE ao.id = ?
                      AND sp.real_name = ?
                    ORDER BY sp.id
                    LIMIT 2
                    """;
            args = new Object[]{assignmentOfferingId, studentName};
        } else if (classId != null) {
            sql = """
                    SELECT DISTINCT sp.id,
                                    sp.student_no,
                                    sp.real_name,
                                    tu.username
                    FROM class_member cm
                    JOIN student_profile sp
                      ON sp.id = cm.student_id
                    LEFT JOIN tap_user tu
                      ON tu.id = sp.user_id
                    WHERE cm.class_id = ?
                      AND cm.member_status = 'ACTIVE'
                      AND sp.real_name = ?
                    ORDER BY sp.id
                    LIMIT 2
                    """;
            args = new Object[]{classId, studentName};
        } else {
            return null;
        }
        List<SubmissionIdentity> rows = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> toIdentity(
                        rs.getLong("id"),
                        rs.getString("student_no"),
                        rs.getString("real_name"),
                        null,
                        rs.getString("username"),
                        assignmentOfferingId,
                        classId
                ),
                args
        );
        return rows.size() == 1 ? rows.get(0) : null;
    }

    private SubmissionIdentity toIdentity(Long studentProfileId,
                                          String studentNo,
                                          String studentName,
                                          String className,
                                          String username,
                                          Long assignmentOfferingId,
                                          Long classId) {
        String normalizedStudentNo = normalizeStudentNo(studentNo);
        Integer legacyStudentId = parseLegacyStudentId(normalizedStudentNo);
        return new SubmissionIdentity(
                studentProfileId,
                normalizedStudentNo,
                normalizeText(studentName),
                firstNonBlank(className, resolveClassName(assignmentOfferingId, classId)),
                normalizeText(username),
                legacyStudentId
        );
    }

    private Integer parseLegacyStudentId(String studentNo) {
        if (studentNo == null || !studentNo.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Integer.valueOf(studentNo);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String extractCandidateStudentNo(GradingSubmissionEntity submission) {
        String direct = normalizeStudentNo(submission.getStudentNo());
        if (direct != null) {
            return direct;
        }
        String fromFilename = extractStudentNoFromText(submission.getOriginalFilename());
        if (fromFilename != null) {
            return fromFilename;
        }
        return extractStudentNoFromText(submission.getStudentName());
    }

    private String extractStudentNoFromText(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = STUDENT_NO_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return normalizeStudentNo(matcher.group(1));
    }

    private String normalizeStudentNo(String value) {
        String normalized = normalizeText(value);
        if (normalized == null) {
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

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.isBlank() ? null : normalized;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = normalizeText(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    public record SubmissionIdentity(Long studentProfileId,
                                     String studentNo,
                                     String studentName,
                                     String className,
                                     String username,
                                     Integer legacyStudentId) {
        public boolean matches(GradingSubmissionEntity submission) {
            if (submission == null) {
                return false;
            }
            return Objects.equals(studentProfileId, submission.getStudentId())
                    && Objects.equals(studentNo, normalize(submission.getStudentNo()))
                    && Objects.equals(studentName, normalize(submission.getStudentName()))
                    && Objects.equals(className, normalize(submission.getClassName()));
        }

        private static String normalize(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim().replaceAll("\\s+", " ");
            return normalized.isBlank() ? null : normalized;
        }
    }
}
