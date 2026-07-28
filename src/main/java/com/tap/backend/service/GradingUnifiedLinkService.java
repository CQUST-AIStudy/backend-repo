package com.tap.backend.service;

import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.domain.grading.GradingTaskEntity;
import com.tap.backend.domain.grading.SubmissionMatchStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class GradingUnifiedLinkService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GradingUnifiedLinkService.class);

    private static final Pattern STUDENT_NO_PATTERN = Pattern.compile("(?<!\\d)(\\d{6,20})(?!\\d)");

    private final JdbcTemplate jdbcTemplate;
    private final GradingFilenameIdentityParser filenameIdentityParser;

    public GradingUnifiedLinkService(JdbcTemplate jdbcTemplate,
                                     GradingFilenameIdentityParser filenameIdentityParser) {
        this.jdbcTemplate = jdbcTemplate;
        this.filenameIdentityParser = filenameIdentityParser;
    }

    public MatchDecision resolveSubmissionMatch(GradingTaskEntity task, GradingSubmissionEntity submission) {
        if (submission == null) {
            return MatchDecision.unmatched("提交不存在");
        }
        List<SubmissionIdentity> roster = listRoster(task);
        if (!roster.isEmpty()) {
            if (submission.getStudentId() != null) {
                List<SubmissionIdentity> byId = roster.stream()
                        .filter(item -> Objects.equals(item.studentProfileId(), submission.getStudentId()))
                        .toList();
                if (byId.size() == 1) {
                    return MatchDecision.confirmed(byId.get(0), "已按学生主键匹配");
                }
            }

            GradingFilenameIdentityParser.FilenameIdentity parsed =
                    filenameIdentityParser.parse(submission.getOriginalFilename());
            String studentNo = firstNonBlank(parsed.studentNo(), submission.getStudentNo());
            if (studentNo != null) {
                List<SubmissionIdentity> byNumber = roster.stream()
                        .filter(item -> Objects.equals(normalizeStudentNo(studentNo), normalizeStudentNo(item.studentNo())))
                        .toList();
                if (byNumber.size() == 1) {
                    return MatchDecision.confirmed(byNumber.get(0), "已按文件名学号匹配");
                }
            }

            String studentName = firstNonBlank(parsed.studentName(), normalizeFilenameBackedName(submission));
            if (studentName != null) {
                List<SubmissionIdentity> byName = roster.stream()
                        .filter(item -> Objects.equals(normalizeText(studentName), normalizeText(item.studentName())))
                        .toList();
                if (byName.size() == 1) {
                    return MatchDecision.confirmed(byName.get(0), "已按班级内唯一姓名匹配");
                }
                if (byName.size() > 1) {
                    return new MatchDecision(SubmissionMatchStatus.AMBIGUOUS, null, byName, "班级内存在重名学生");
                }
            }
            return MatchDecision.unmatched("文件名未能匹配当前教学班学生");
        }

        SubmissionIdentity legacyIdentity = resolveSubmissionIdentity(task, submission);
        return legacyIdentity == null
                ? MatchDecision.unmatched("任务未关联可用班级花名册")
                : MatchDecision.confirmed(legacyIdentity, "已通过兼容身份链路匹配");
    }

    public List<SubmissionIdentity> listRoster(GradingTaskEntity task) {
        if (task == null) {
            return List.of();
        }
        Long offeringId = task.getAssignmentOfferingId();
        Long classId = task.getClassId();
        if (offeringId == null && classId == null) {
            Long teacherId = task.getTeacherId();
            if (teacherId == null && task.getTeacher() != null) {
                teacherId = task.getTeacher().getId();
            }
            classId = findSoleOwnedClassId(teacherId);
        }
        String sql;
        Object id;
        if (offeringId != null) {
            sql = """
                    SELECT DISTINCT sp.id, sp.student_no, sp.real_name, tc.name AS class_name, tu.username
                    FROM assignment_offering ao
                    JOIN teaching_class tc ON tc.id = ao.class_id
                    JOIN class_member cm ON cm.class_id = ao.class_id AND cm.member_status = 'ACTIVE'
                    JOIN student_profile sp ON sp.id = cm.student_id
                    LEFT JOIN tap_user tu ON tu.id = sp.user_id
                    WHERE ao.id = ?
                    ORDER BY sp.student_no, sp.id
                    """;
            id = offeringId;
        } else if (classId != null) {
            return loadRosterByClassId(classId);
        } else {
            return List.of();
        }
        return jdbcTemplate.query(sql, (rs, rowNum) -> new SubmissionIdentity(
                rs.getLong("id"),
                normalizeStudentNo(rs.getString("student_no")),
                normalizeText(rs.getString("real_name")),
                normalizeText(rs.getString("class_name")),
                normalizeText(rs.getString("username")),
                parseLegacyStudentId(normalizeStudentNo(rs.getString("student_no")))
        ), id);
    }

    public Long resolveEffectiveClassId(Long requestedClassId, Long teacherId) {
        return requestedClassId != null ? requestedClassId : findSoleOwnedClassId(teacherId);
    }

    /**
     * 将 class_student（教师在学生管理里维护的名册）补齐到统一模型
     * （student_profile + class_member），保证手动添加的学生也能出现在匹配候选里。
     * 幂等操作：已存在的档案/成员记录不会被重复插入，也不会修改已有成员状态。
     */
    public void ensureRosterCoverage(GradingTaskEntity task) {
        Long classId = resolveRosterClassId(task);
        if (classId == null) {
            return;
        }
        try {
            jdbcTemplate.update("""
                    INSERT IGNORE INTO student_profile (student_no, real_name, user_id, status)
                    SELECT cs.student_num, cs.student_name, cs.user_id, 'ACTIVE'
                    FROM class_student cs
                    WHERE cs.class_id = ? AND cs.student_num IS NOT NULL AND cs.student_num <> ''
                    """, classId);
            jdbcTemplate.update("""
                    INSERT IGNORE INTO class_member (class_id, student_id, member_status)
                    SELECT cs.class_id, sp.id, 'ACTIVE'
                    FROM class_student cs
                    JOIN student_profile sp ON cs.student_num = sp.student_no COLLATE utf8mb4_unicode_ci
                    WHERE cs.class_id = ?
                    """, classId);
        } catch (Exception e) {
            log.warn("ensureRosterCoverage: failed to sync class_student into unified roster for class {}: {}",
                    classId, e.getMessage());
        }
    }

    /** 解析任务实际对应的教学班 ID，与 listRoster 的取数链路保持一致。 */
    private Long resolveRosterClassId(GradingTaskEntity task) {
        if (task == null) {
            return null;
        }
        if (task.getAssignmentOfferingId() != null) {
            List<Long> classIds = jdbcTemplate.query(
                    "SELECT class_id FROM assignment_offering WHERE id = ?",
                    (rs, rowNum) -> rs.getLong("class_id"),
                    task.getAssignmentOfferingId()
            );
            if (!classIds.isEmpty() && classIds.get(0) != null && classIds.get(0) > 0) {
                return classIds.get(0);
            }
        }
        if (task.getClassId() != null) {
            return task.getClassId();
        }
        Long teacherId = task.getTeacherId();
        if (teacherId == null && task.getTeacher() != null) {
            teacherId = task.getTeacher().getId();
        }
        return findSoleOwnedClassId(teacherId);
    }

    protected Long findSoleOwnedClassId(Long teacherId) {
        if (teacherId == null) {
            return null;
        }
        List<Long> classIds = jdbcTemplate.query(
                """
                SELECT id
                FROM teaching_class
                WHERE teacher_id = ? AND status = 'ACTIVE'
                ORDER BY id
                LIMIT 2
                """,
                (rs, rowNum) -> rs.getLong("id"),
                teacherId
        );
        return classIds.size() == 1 ? classIds.get(0) : null;
    }

    protected List<SubmissionIdentity> loadRosterByClassId(Long classId) {
        // First try student_profile + class_member (analytics system)
        List<SubmissionIdentity> fromMembers = jdbcTemplate.query(
                """
                SELECT DISTINCT sp.id, sp.student_no, sp.real_name, tc.name AS class_name, tu.username
                FROM teaching_class tc
                JOIN class_member cm ON cm.class_id = tc.id AND cm.member_status = 'ACTIVE'
                JOIN student_profile sp ON sp.id = cm.student_id
                LEFT JOIN tap_user tu ON tu.id = sp.user_id
                WHERE tc.id = ?
                ORDER BY sp.student_no, sp.id
                """,
                (rs, rowNum) -> new SubmissionIdentity(
                        rs.getLong("id"),
                        normalizeStudentNo(rs.getString("student_no")),
                        normalizeText(rs.getString("real_name")),
                        normalizeText(rs.getString("class_name")),
                        normalizeText(rs.getString("username")),
                        parseLegacyStudentId(normalizeStudentNo(rs.getString("student_no")))
                ),
                classId
        );

        if (!fromMembers.isEmpty()) {
            return fromMembers;
        }

        // Fallback: query class_student table (grading system's own student roster)
        return jdbcTemplate.query(
                """
                SELECT cs.id, cs.student_num AS student_no, cs.student_name AS real_name,
                       tc.name AS class_name, tu.username
                FROM class_student cs
                JOIN teaching_class tc ON tc.id = cs.class_id
                LEFT JOIN tap_user tu ON tu.id = cs.user_id
                WHERE tc.id = ?
                ORDER BY cs.student_num, cs.id
                """,
                (rs, rowNum) -> new SubmissionIdentity(
                        rs.getLong("id"),
                        normalizeStudentNo(rs.getString("student_no")),
                        normalizeText(rs.getString("real_name")),
                        normalizeText(rs.getString("class_name")),
                        normalizeText(rs.getString("username")),
                        parseLegacyStudentId(normalizeStudentNo(rs.getString("student_no")))
                ),
                classId
        );
    }

    public SubmissionIdentity requireRosterStudent(GradingTaskEntity task, Long studentProfileId) {
        return listRoster(task).stream()
                .filter(item -> Objects.equals(item.studentProfileId(), studentProfileId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("所选学生不在当前教学班中"));
    }

    private String normalizeFilenameBackedName(GradingSubmissionEntity submission) {
        String value = normalizeText(submission.getStudentName());
        if (value == null || Objects.equals(value, normalizeText(submission.getOriginalFilename()))) {
            return null;
        }
        return value;
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

    public record MatchDecision(SubmissionMatchStatus status,
                                SubmissionIdentity identity,
                                List<SubmissionIdentity> candidates,
                                String reason) {
        public MatchDecision {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public static MatchDecision confirmed(SubmissionIdentity identity, String reason) {
            return new MatchDecision(SubmissionMatchStatus.AUTO_CONFIRMED, identity, List.of(identity), reason);
        }

        public static MatchDecision unmatched(String reason) {
            return new MatchDecision(SubmissionMatchStatus.UNMATCHED, null, List.of(), reason);
        }
    }
}
