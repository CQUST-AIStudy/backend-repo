package com.tap.backend.academic.dao.teacherexperiment;

import com.tap.backend.academic.teacherexperiment.TeacherExperimentPlagiarismRow;
import com.tap.backend.academic.teacherexperiment.TeacherExperimentScoreAggregate;
import com.tap.backend.academic.teacherexperiment.TeacherExperimentScoreRow;
import com.tap.backend.academic.teacherexperiment.TeacherExperimentSummaryRow;
import com.tap.backend.academic.teacherexperiment.TeacherStudentAssignmentRow;
import com.tap.backend.academic.teacherexperiment.TeacherSubmissionProblemRow;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TeacherExperimentQueryDao {

    String DATA_STRUCTURE_KEYWORD = "\u6570\u636e\u7ed3\u6784";
    String C_LANGUAGE_KEYWORD = "C\u8bed\u8a00";
    String EXPERIMENT_NAME_EXPR = "COALESCE(NULLIF(TRIM(ao.title_override), ''), at.title)";
    String COURSE_NAME_EXPR = "COALESCE(NULLIF(TRIM(tc.course_name), ''), '')";
    String LEGACY_EXPERIMENT_ID_EXPR =
            "CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(ao.source_offering_key, ':CLASS:', 1), ':', -1) AS SIGNED)";
    String COLL = " COLLATE utf8mb4_0900_ai_ci";
    String DATA_STRUCTURE_SQL_LITERAL = "_utf8mb4'" + DATA_STRUCTURE_KEYWORD + "' COLLATE utf8mb4_0900_ai_ci";
    String C_LANGUAGE_SQL_LITERAL = "_utf8mb4'" + C_LANGUAGE_KEYWORD + "' COLLATE utf8mb4_0900_ai_ci";
    String DATA_STRUCTURE_LIKE_PATTERN = "CONCAT('%', " + DATA_STRUCTURE_SQL_LITERAL + ", '%')";
    String C_LANGUAGE_LIKE_PATTERN = "CONCAT('%', " + C_LANGUAGE_SQL_LITERAL + ", '%')";
    String EXPERIMENT_NAME_EXPR_COLL = EXPERIMENT_NAME_EXPR + COLL;
    String COURSE_NAME_EXPR_COLL = COURSE_NAME_EXPR + COLL;
    String NORMALIZED_PTA_KEYWORD_EXPR =
            "REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(COALESCE(tc.pta_keyword, ''), ' ', ''), '　', ''), CHAR(9), ''), CHAR(10), ''), CHAR(13), '')";
    String NORMALIZED_CLASS_NAME_EXPR =
            "REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(COALESCE(tc.name, ''), ' ', ''), '　', ''), CHAR(9), ''), CHAR(10), ''), CHAR(13), '')";
    String DATA_STRUCTURE_SCOPE_PREDICATE =
            "(" +
                    EXPERIMENT_NAME_EXPR_COLL + " LIKE " + DATA_STRUCTURE_LIKE_PATTERN + " OR " +
                    "COALESCE(NULLIF(TRIM(tc.pta_keyword), ''), '')" + COLL + " LIKE " + DATA_STRUCTURE_LIKE_PATTERN + " OR " +
                    COURSE_NAME_EXPR_COLL + " LIKE " + DATA_STRUCTURE_LIKE_PATTERN +
                    ") AND NOT (" +
                    EXPERIMENT_NAME_EXPR_COLL + " LIKE " + C_LANGUAGE_LIKE_PATTERN + " OR " +
                    "COALESCE(NULLIF(TRIM(tc.pta_keyword), ''), '')" + COLL + " LIKE " + C_LANGUAGE_LIKE_PATTERN + " OR " +
                    COURSE_NAME_EXPR_COLL + " LIKE " + C_LANGUAGE_LIKE_PATTERN +
                    ")";
    String SUBMISSION_ACTIVITY_PREDICATE =
            "LOWER(COALESCE(sa.submission_status, '')) IN ('graded', 'submitted') " +
                    "OR COALESCE(sa.completion_evidence, 'NONE') IN ('TRANSCRIPT_SCORE', 'ANSWER_SHEET', 'SCORED_CODE')";
    String ACTIVE_PROGRAMMING_PROBLEM_PREDICATE =
            "EXISTS (" +
                    "SELECT 1 FROM assignment_problem eligible_ap " +
                    "JOIN pta_problem_detail eligible_pd " +
                    "  ON eligible_pd.problem_set_id = ao.pta_problem_set_id " +
                    " AND eligible_pd.problem_set_problem_id = eligible_ap.source_problem_id " +
                    "WHERE eligible_ap.offering_id = ao.id " +
                    "  AND eligible_ap.status = 'ACTIVE' " +
                    "  AND UPPER(TRIM(COALESCE(eligible_pd.problem_type, ''))) = 'PROGRAMMING'" +
                    ")";

    @Select({
            "<script>",
            "SELECT",
            "  CAST(ao.id AS SIGNED) AS experimentId,",
            "  " + EXPERIMENT_NAME_EXPR + " AS name,",
            "  ao.deadline_at AS deadline,",
            "  ao.created_at AS createdTime,",
            "  COUNT(sa.id) AS rosterCount,",
            "  COALESCE(SUM(CASE",
            "    WHEN " + SUBMISSION_ACTIVITY_PREDICATE + " THEN 1",
            "    ELSE 0",
            "  END), 0) AS submissionCount,",
            "  ROUND(",
            "    COALESCE(SUM(COALESCE(sa.best_total_score, 0)), 0) / NULLIF(COUNT(sa.id), 0),",
            "    2",
            "  ) AS averageScore",
            "FROM assignment_offering ao",
            "JOIN assignment_template at ON at.id = ao.template_id",
            "JOIN teaching_class tc ON tc.id = ao.class_id",
            "JOIN tap_user tu ON tu.id = #{teacherId}",
            "LEFT JOIN student_assignment sa ON sa.offering_id = ao.id",
            "WHERE ao.teacher_id = tu.id",
            "  AND " + ACTIVE_PROGRAMMING_PROBLEM_PREDICATE,
            "  AND " + DATA_STRUCTURE_SCOPE_PREDICATE,
            "  <if test='classId != null'>",
            "    AND ao.class_id = #{classId}",
            "  </if>",
            "  <if test='classKeyword != null and classKeyword != \"\"'>",
            "    AND (",
            "      " + NORMALIZED_PTA_KEYWORD_EXPR + " COLLATE utf8mb4_unicode_ci = #{classKeyword} COLLATE utf8mb4_unicode_ci",
            "      OR " + NORMALIZED_CLASS_NAME_EXPR + " COLLATE utf8mb4_unicode_ci = #{classKeyword} COLLATE utf8mb4_unicode_ci",
            "    )",
            "  </if>",
            "GROUP BY ao.id, " + EXPERIMENT_NAME_EXPR + ", ao.deadline_at, ao.created_at, ao.seq_no",
            "ORDER BY",
            "  CASE WHEN ao.seq_no IS NULL THEN 1 ELSE 0 END,",
            "  ao.seq_no,",
            "  ao.id",
            "</script>"
    })
    List<TeacherExperimentSummaryRow> findTeacherExperimentSummaries(
            @Param("teacherId") Integer teacherId,
            @Param("classId") Long classId,
            @Param("classKeyword") String classKeyword
    );

    @Select({
            "<script>",
            "SELECT",
            "  CAST(ao.id AS SIGNED) AS experimentId,",
            "  " + EXPERIMENT_NAME_EXPR + " AS name,",
            "  ao.deadline_at AS deadline,",
            "  ao.created_at AS createdTime,",
            "  COUNT(sa.id) AS rosterCount,",
            "  COALESCE(SUM(CASE",
            "    WHEN " + SUBMISSION_ACTIVITY_PREDICATE + " THEN 1",
            "    ELSE 0",
            "  END), 0) AS submissionCount,",
            "  ROUND(",
            "    COALESCE(SUM(COALESCE(sa.best_total_score, 0)), 0) / NULLIF(COUNT(sa.id), 0),",
            "    2",
            "  ) AS averageScore",
            "FROM assignment_offering ao",
            "JOIN assignment_template at ON at.id = ao.template_id",
            "JOIN teaching_class tc ON tc.id = ao.class_id",
            "JOIN tap_user tu ON tu.id = #{teacherId}",
            "LEFT JOIN student_assignment sa ON sa.offering_id = ao.id",
            "WHERE ao.teacher_id = tu.id",
            "  AND " + ACTIVE_PROGRAMMING_PROBLEM_PREDICATE,
            "  <if test='classId != null'>",
            "    AND ao.class_id = #{classId}",
            "  </if>",
            "  <if test='classKeyword != null and classKeyword != \"\"'>",
            "    AND (",
            "      " + NORMALIZED_PTA_KEYWORD_EXPR + " COLLATE utf8mb4_unicode_ci = #{classKeyword} COLLATE utf8mb4_unicode_ci",
            "      OR " + NORMALIZED_CLASS_NAME_EXPR + " COLLATE utf8mb4_unicode_ci = #{classKeyword} COLLATE utf8mb4_unicode_ci",
            "    )",
            "  </if>",
            "GROUP BY ao.id, " + EXPERIMENT_NAME_EXPR + ", ao.deadline_at, ao.created_at, ao.seq_no",
            "ORDER BY",
            "  CASE WHEN ao.seq_no IS NULL THEN 1 ELSE 0 END,",
            "  ao.seq_no,",
            "  ao.id",
            "</script>"
    })
    List<TeacherExperimentSummaryRow> findTeacherExperimentSummariesForTeacher(
            @Param("teacherId") Integer teacherId,
            @Param("classId") Long classId,
            @Param("classKeyword") String classKeyword
    );

    @Select({
            "<script>",
            "SELECT",
            "  CAST(tc.id AS SIGNED) AS classId,",
            "  sp.student_no AS studentId,",
            "  sp.real_name AS studentName,",
            "  COALESCE(NULLIF(TRIM(student_user.username), ''), sp.student_no) AS studentUsername,",
            "  tc.name AS className,",
            "  CAST(ao.id AS SIGNED) AS experimentId,",
            "  " + EXPERIMENT_NAME_EXPR + " AS experimentName,",
            "  ao.deadline_at AS deadline,",
            "  COALESCE(sa.last_submit_at, sa.first_submit_at) AS submitTime,",
            "  COALESCE(sa.best_total_score, sa.latest_total_score) AS score,",
            "  sa.submission_status AS submissionStatus,",
            "  sa.transcript_row_present AS transcriptRowPresent,",
            "  sa.answer_sheet_count AS answerSheetCount,",
            "  sa.scored_code_count AS scoredCodeCount,",
            "  sa.submission_attempt_count AS submissionAttemptCount,",
            "  sa.completion_evidence AS completionEvidence,",
            "  pct.Plagiarism_Rate AS plagiarismRate",
            "FROM assignment_offering ao",
            "JOIN assignment_template at ON at.id = ao.template_id",
            "JOIN tap_user teacher_user ON teacher_user.id = #{teacherId}",
            "JOIN teaching_class tc ON tc.id = ao.class_id",
            "JOIN student_assignment sa ON sa.offering_id = ao.id",
            "JOIN student_profile sp ON sp.id = sa.student_id",
            "LEFT JOIN tap_user student_user ON student_user.id = sp.user_id",
            "LEFT JOIN Plagiarism_Check_Table pct",
            "  ON pct.student_id COLLATE utf8mb4_unicode_ci = sp.student_no COLLATE utf8mb4_unicode_ci",
            " AND ao.source_offering_key LIKE 'LEGACY_EXPERIMENT_OFFERING:%'",
            " AND pct.experiment_id = " + LEGACY_EXPERIMENT_ID_EXPR,
            "WHERE ao.teacher_id = teacher_user.id",
            "  AND " + DATA_STRUCTURE_SCOPE_PREDICATE,
            "  <if test='classId != null'>",
            "    AND ao.class_id = #{classId}",
            "  </if>",
            "  <if test='classKeyword != null and classKeyword != \"\"'>",
            "    AND (",
            "      " + NORMALIZED_PTA_KEYWORD_EXPR + " COLLATE utf8mb4_unicode_ci = #{classKeyword} COLLATE utf8mb4_unicode_ci",
            "      OR " + NORMALIZED_CLASS_NAME_EXPR + " COLLATE utf8mb4_unicode_ci = #{classKeyword} COLLATE utf8mb4_unicode_ci",
            "    )",
            "  </if>",
            "  <if test='experimentId != null'>",
            "    AND ao.id = #{experimentId}",
            "  </if>",
            "ORDER BY",
            "  tc.name,",
            "  sp.student_no,",
            "  CASE WHEN ao.seq_no IS NULL THEN 1 ELSE 0 END,",
            "  ao.seq_no,",
            "  ao.id",
            "</script>"
    })
    List<TeacherStudentAssignmentRow> findTeacherStudentAssignments(
            @Param("teacherId") Integer teacherId,
            @Param("classId") Long classId,
            @Param("classKeyword") String classKeyword,
            @Param("experimentId") Integer experimentId
    );

    @Select({
            "<script>",
            "SELECT",
            "  CAST(tc.id AS SIGNED) AS classId,",
            "  sp.student_no AS studentId,",
            "  sp.real_name AS studentName,",
            "  COALESCE(NULLIF(TRIM(student_user.username), ''), sp.student_no) AS studentUsername,",
            "  tc.name AS className,",
            "  CAST(ao.id AS SIGNED) AS experimentId,",
            "  " + EXPERIMENT_NAME_EXPR + " AS experimentName,",
            "  ao.deadline_at AS deadline,",
            "  COALESCE(sa.last_submit_at, sa.first_submit_at) AS submitTime,",
            "  COALESCE(sa.best_total_score, sa.latest_total_score) AS score,",
            "  sa.submission_status AS submissionStatus,",
            "  sa.transcript_row_present AS transcriptRowPresent,",
            "  sa.answer_sheet_count AS answerSheetCount,",
            "  sa.scored_code_count AS scoredCodeCount,",
            "  sa.submission_attempt_count AS submissionAttemptCount,",
            "  sa.completion_evidence AS completionEvidence,",
            "  pct.Plagiarism_Rate AS plagiarismRate",
            "FROM assignment_offering ao",
            "JOIN assignment_template at ON at.id = ao.template_id",
            "JOIN tap_user teacher_user ON teacher_user.id = #{teacherId}",
            "JOIN teaching_class tc ON tc.id = ao.class_id",
            "JOIN student_assignment sa ON sa.offering_id = ao.id",
            "JOIN student_profile sp ON sp.id = sa.student_id",
            "LEFT JOIN tap_user student_user ON student_user.id = sp.user_id",
            "LEFT JOIN Plagiarism_Check_Table pct",
            "  ON pct.student_id COLLATE utf8mb4_unicode_ci = sp.student_no COLLATE utf8mb4_unicode_ci",
            " AND ao.source_offering_key LIKE 'LEGACY_EXPERIMENT_OFFERING:%'",
            " AND pct.experiment_id = " + LEGACY_EXPERIMENT_ID_EXPR,
            "WHERE ao.teacher_id = teacher_user.id",
            "  <if test='classId != null'>",
            "    AND ao.class_id = #{classId}",
            "  </if>",
            "  <if test='classKeyword != null and classKeyword != \"\"'>",
            "    AND (",
            "      " + NORMALIZED_PTA_KEYWORD_EXPR + " COLLATE utf8mb4_unicode_ci = #{classKeyword} COLLATE utf8mb4_unicode_ci",
            "      OR " + NORMALIZED_CLASS_NAME_EXPR + " COLLATE utf8mb4_unicode_ci = #{classKeyword} COLLATE utf8mb4_unicode_ci",
            "    )",
            "  </if>",
            "  <if test='experimentId != null'>",
            "    AND ao.id = #{experimentId}",
            "  </if>",
            "ORDER BY",
            "  tc.name,",
            "  sp.student_no,",
            "  CASE WHEN ao.seq_no IS NULL THEN 1 ELSE 0 END,",
            "  ao.seq_no,",
            "  ao.id",
            "</script>"
    })
    List<TeacherStudentAssignmentRow> findTeacherStudentAssignmentsForTeacher(
            @Param("teacherId") Integer teacherId,
            @Param("classId") Long classId,
            @Param("classKeyword") String classKeyword,
            @Param("experimentId") Integer experimentId
    );

    @Select({
            "<script>",
            "SELECT",
            "  CAST(MIN(tc.id) AS SIGNED) AS classId,",
            "  sp.student_no AS studentId,",
            "  sp.real_name AS studentName,",
            "  COALESCE(NULLIF(TRIM(student_user.username), ''), sp.student_no) AS studentUsername,",
            "  MIN(tc.name) AS className",
            "FROM tap_user teacher_user",
            "JOIN teaching_class tc ON tc.teacher_id = teacher_user.id",
            "JOIN class_member cm",
            "  ON cm.class_id = tc.id",
            " AND cm.member_status = 'ACTIVE'",
            "JOIN student_profile sp",
            "  ON sp.id = cm.student_id",
            " AND sp.status != 'DELETED'",
            "LEFT JOIN tap_user student_user ON student_user.id = sp.user_id",
            "WHERE teacher_user.id = #{teacherId}",
            "  <if test='classId != null'>",
            "    AND tc.id = #{classId}",
            "  </if>",
            "GROUP BY sp.id, sp.student_no, sp.real_name, COALESCE(NULLIF(TRIM(student_user.username), ''), sp.student_no)",
            "ORDER BY className, sp.student_no",
            "</script>"
    })
    List<TeacherStudentAssignmentRow> findTeacherStudentRoster(
            @Param("teacherId") Integer teacherId,
            @Param("classId") Long classId
    );

    @Select({
            "SELECT",
            "  sp.student_no AS studentId,",
            "  sp.real_name AS studentName,",
            "  COALESCE(NULLIF(TRIM(student_user.username), ''), sp.student_no) AS studentUsername,",
            "  tc.name AS className,",
            "  CAST(ao.id AS SIGNED) AS experimentId,",
            "  " + EXPERIMENT_NAME_EXPR + " AS experimentName,",
            "  ao.deadline_at AS deadline,",
            "  COALESCE(sa.last_submit_at, sa.first_submit_at) AS submitTime,",
            "  COALESCE(sa.best_total_score, sa.latest_total_score) AS score,",
            "  sa.submission_status AS submissionStatus,",
            "  sa.transcript_row_present AS transcriptRowPresent,",
            "  sa.answer_sheet_count AS answerSheetCount,",
            "  sa.scored_code_count AS scoredCodeCount,",
            "  sa.submission_attempt_count AS submissionAttemptCount,",
            "  sa.completion_evidence AS completionEvidence,",
            "  pct.Plagiarism_Rate AS plagiarismRate",
            "FROM student_assignment sa",
            "JOIN assignment_offering ao ON ao.id = sa.offering_id",
            "JOIN assignment_template at ON at.id = ao.template_id",
            "JOIN teaching_class tc ON tc.id = ao.class_id",
            "JOIN student_profile sp ON sp.id = sa.student_id",
            "LEFT JOIN tap_user student_user ON student_user.id = sp.user_id",
            "LEFT JOIN Plagiarism_Check_Table pct",
            "  ON pct.student_id COLLATE utf8mb4_unicode_ci = sp.student_no COLLATE utf8mb4_unicode_ci",
            " AND ao.source_offering_key LIKE 'LEGACY_EXPERIMENT_OFFERING:%'",
            " AND pct.experiment_id = " + LEGACY_EXPERIMENT_ID_EXPR,
            "WHERE ao.id = #{experimentId}",
            "  AND sp.student_no COLLATE utf8mb4_unicode_ci = #{studentId} COLLATE utf8mb4_unicode_ci",
            "  AND " + DATA_STRUCTURE_SCOPE_PREDICATE,
            "LIMIT 1"
    })
    TeacherStudentAssignmentRow findStudentAssignmentBySubmissionKey(
            @Param("studentId") String studentId,
            @Param("experimentId") Integer experimentId
    );

    @Select({
            "SELECT",
            "  sp.student_no AS studentId,",
            "  sp.real_name AS studentName,",
            "  COALESCE(NULLIF(TRIM(student_user.username), ''), sp.student_no) AS studentUsername,",
            "  tc.name AS className,",
            "  CAST(ao.id AS SIGNED) AS experimentId,",
            "  " + EXPERIMENT_NAME_EXPR + " AS experimentName,",
            "  ao.deadline_at AS deadline,",
            "  COALESCE(sa.last_submit_at, sa.first_submit_at) AS submitTime,",
            "  COALESCE(sa.best_total_score, sa.latest_total_score) AS score,",
            "  sa.submission_status AS submissionStatus,",
            "  sa.transcript_row_present AS transcriptRowPresent,",
            "  sa.answer_sheet_count AS answerSheetCount,",
            "  sa.scored_code_count AS scoredCodeCount,",
            "  sa.submission_attempt_count AS submissionAttemptCount,",
            "  sa.completion_evidence AS completionEvidence,",
            "  pct.Plagiarism_Rate AS plagiarismRate",
            "FROM student_assignment sa",
            "JOIN assignment_offering ao ON ao.id = sa.offering_id",
            "JOIN assignment_template at ON at.id = ao.template_id",
            "JOIN teaching_class tc ON tc.id = ao.class_id",
            "JOIN student_profile sp ON sp.id = sa.student_id",
            "LEFT JOIN tap_user student_user ON student_user.id = sp.user_id",
            "LEFT JOIN Plagiarism_Check_Table pct",
            "  ON pct.student_id COLLATE utf8mb4_unicode_ci = sp.student_no COLLATE utf8mb4_unicode_ci",
            " AND ao.source_offering_key LIKE 'LEGACY_EXPERIMENT_OFFERING:%'",
            " AND pct.experiment_id = " + LEGACY_EXPERIMENT_ID_EXPR,
            "WHERE ao.id = #{experimentId}",
            "  AND sp.student_no COLLATE utf8mb4_unicode_ci = #{studentId} COLLATE utf8mb4_unicode_ci",
            "LIMIT 1"
    })
    TeacherStudentAssignmentRow findStudentAssignmentDetailBySubmissionKey(
            @Param("studentId") String studentId,
            @Param("experimentId") Integer experimentId
    );

    @Select({
            "SELECT",
            "  sp.student_no AS studentId,",
            "  sp.real_name AS studentName,",
            "  COALESCE(NULLIF(TRIM(student_user.username), ''), sp.student_no) AS studentUsername,",
            "  tc.name AS className,",
            "  CAST(ao.id AS SIGNED) AS experimentId,",
            "  " + EXPERIMENT_NAME_EXPR + " AS experimentName,",
            "  ao.deadline_at AS deadline,",
            "  COALESCE(sa.last_submit_at, sa.first_submit_at) AS submitTime,",
            "  COALESCE(sa.best_total_score, sa.latest_total_score) AS score,",
            "  sa.submission_status AS submissionStatus,",
            "  sa.transcript_row_present AS transcriptRowPresent,",
            "  sa.answer_sheet_count AS answerSheetCount,",
            "  sa.scored_code_count AS scoredCodeCount,",
            "  sa.submission_attempt_count AS submissionAttemptCount,",
            "  sa.completion_evidence AS completionEvidence,",
            "  pct.Plagiarism_Rate AS plagiarismRate",
            "FROM assignment_offering ao",
            "JOIN assignment_template at ON at.id = ao.template_id",
            "JOIN tap_user teacher_user ON teacher_user.id = #{teacherId}",
            "JOIN teaching_class tc ON tc.id = ao.class_id",
            "JOIN student_assignment sa ON sa.offering_id = ao.id",
            "JOIN student_profile sp ON sp.id = sa.student_id",
            "LEFT JOIN tap_user student_user ON student_user.id = sp.user_id",
            "LEFT JOIN Plagiarism_Check_Table pct",
            "  ON pct.student_id COLLATE utf8mb4_unicode_ci = sp.student_no COLLATE utf8mb4_unicode_ci",
            " AND ao.source_offering_key LIKE 'LEGACY_EXPERIMENT_OFFERING:%'",
            " AND pct.experiment_id = " + LEGACY_EXPERIMENT_ID_EXPR,
            "WHERE ao.teacher_id = teacher_user.id",
            "  AND ao.id = #{experimentId}",
            "  AND sp.student_no COLLATE utf8mb4_unicode_ci = #{studentId} COLLATE utf8mb4_unicode_ci",
            "LIMIT 1"
    })
    TeacherStudentAssignmentRow findStudentAssignmentDetailForTeacher(
            @Param("teacherId") Integer teacherId,
            @Param("studentId") String studentId,
            @Param("experimentId") Integer experimentId
    );

    @Select({
            "SELECT",
            "  CAST(ap.sort_order AS SIGNED) AS sortOrder,",
            "  ap.problem_no AS problemNo,",
            "  ap.title AS problemTitle,",
            "  apd.content AS statementMd,",
            "  sps.latest_status AS latestStatus,",
            "  sps.best_score AS bestScore,",
            "  sps.attempt_count AS attemptCount,",
            "  spa.submitted_at AS submitTime,",
            "  code_artifact.text_content AS code",
            "FROM student_profile sp",
            "JOIN student_problem_state sps",
            "  ON sps.student_id = sp.id",
            " AND sps.offering_id = #{experimentId}",
            "JOIN assignment_problem ap ON ap.id = sps.problem_id",
            "LEFT JOIN pta_problem_detail apd ON apd.problem_set_problem_id = ap.problem_no",
            "LEFT JOIN student_problem_attempt spa ON spa.id = sps.latest_attempt_id",
            "LEFT JOIN artifact code_artifact ON code_artifact.id = sps.latest_code_artifact_id",
            "WHERE sp.student_no COLLATE utf8mb4_unicode_ci = #{studentId} COLLATE utf8mb4_unicode_ci",
            "ORDER BY ap.sort_order, ap.id"
    })
    List<TeacherSubmissionProblemRow> findSubmissionProblemRows(
            @Param("studentId") String studentId,
            @Param("experimentId") Integer experimentId
    );

    @Select({
            "<script>",
            "SELECT",
            "  experiment_id AS experimentId,",
            "  COUNT(DISTINCT CASE",
            "    WHEN LOWER(COALESCE(status, '')) = 'completed' OR (score IS NOT NULL AND score &gt; 0)",
            "    THEN username",
            "  END) AS submissionCount,",
            "  COALESCE(SUM(CASE WHEN score IS NOT NULL AND score &gt; 0 THEN score ELSE 0 END), 0) AS totalPositiveScore",
            "FROM score",
            "WHERE experiment_id IN",
            "<foreach item='experimentId' collection='experimentIds' open='(' separator=',' close=')'>",
            "  #{experimentId}",
            "</foreach>",
            "GROUP BY experiment_id",
            "</script>"
    })
    List<TeacherExperimentScoreAggregate> summarizeByExperimentIds(@Param("experimentIds") List<Integer> experimentIds);

    @Select({
            "<script>",
            "SELECT",
            "  username AS username,",
            "  experiment_id AS experimentId,",
            "  SUM(score) AS score,",
            "  MAX(submit_time) AS submitTime,",
            "  MAX(status) AS status",
            "FROM score",
            "WHERE username IN",
            "<foreach item='username' collection='usernames' open='(' separator=',' close=')'>",
            "  #{username}",
            "</foreach>",
            "GROUP BY username, experiment_id",
            "</script>"
    })
    List<TeacherExperimentScoreRow> findPerExperimentSumScoresByUsernames(@Param("usernames") List<String> usernames);

    @Select({
            "<script>",
            "SELECT",
            "  student_id AS studentId,",
            "  experiment_id AS experimentId,",
            "  Plagiarism_Rate AS plagiarismRate",
            "FROM Plagiarism_Check_Table",
            "WHERE student_id IN",
            "<foreach item='studentId' collection='studentIds' open='(' separator=',' close=')'>",
            "  #{studentId}",
            "</foreach>",
            "AND experiment_id IN",
            "<foreach item='experimentId' collection='experimentIds' open='(' separator=',' close=')'>",
            "  #{experimentId}",
            "</foreach>",
            "</script>"
    })
    List<TeacherExperimentPlagiarismRow> findPlagiarismRates(
            @Param("studentIds") List<String> studentIds,
            @Param("experimentIds") List<Integer> experimentIds
    );

    /**
     * 从 submit_situation 表查询每个学生在每个实验的汇总成绩。
     * submit_situation 是爬虫最原始的提交流水表（每条提交一行），
     * 需要先按 (student_id, experiment_id, serial_number) 取每题最高分，
     * 再按 (student_id, experiment_id) 求总成绩和最新提交时间。
     */
    @Select({
            "<script>",
            "SELECT",
            "  student_id AS username,",
            "  experiment_id AS experimentId,",
            "  CAST(ROUND(SUM(per_problem_best)) AS SIGNED) AS score,",
            "  MAX(latest_submit_time) AS submitTime,",
            "  CASE WHEN MAX(has_ac) = 1 THEN 'completed' ELSE 'submitted' END AS status",
            "FROM (",
            "  SELECT",
            "    ss.student_id,",
            "    ss.experiment_id,",
            "    ss.serial_number,",
            "    MAX(CAST(COALESCE(ss.score, 0) AS DECIMAL(10,2))) AS per_problem_best,",
            "    MAX(STR_TO_DATE(REPLACE(REPLACE(NULLIF(ss.submit_time, ''), 'T', ' '), 'Z', ''), '%Y-%m-%d %H:%i:%s')) AS latest_submit_time,",
            "    MAX(CASE WHEN UPPER(ss.situation) IN ('C','AC','ACCEPTED') THEN 1 ELSE 0 END) AS has_ac",
            "  FROM submit_situation ss",
            "  WHERE ss.student_id IN",
            "  <foreach item='studentId' collection='studentIds' open='(' separator=',' close=')'>",
            "    #{studentId}",
            "  </foreach>",
            "  <if test='experimentId != null'>",
            "    AND ss.experiment_id = #{experimentId}",
            "  </if>",
            "  GROUP BY ss.student_id, ss.experiment_id, ss.serial_number",
            ") t",
            "GROUP BY student_id, experiment_id",
            "</script>"
    })
    List<TeacherExperimentScoreRow> findPerExperimentSumScoresFromSubmitSituation(
            @Param("studentIds") List<String> studentIds,
            @Param("experimentId") Integer experimentId
    );

    /**
     * 从 submit_situation 表查找在指定实验中提交过作业的学生名单。
     * 当 class_member 表没有学生记录、但 submit_situation 有提交数据时作为兜底数据源。
     */
    @Select({
            "SELECT DISTINCT",
            "  #{experimentId} AS experimentId,",
            "  (SELECT e.experiment_name FROM experiment e WHERE e.experiment_id = #{experimentId}) AS experimentName,",
            "  ss.student_id AS studentId,",
            "  COALESCE(sp.real_name, ss.student_id) AS studentName,",
            "  COALESCE(NULLIF(TRIM(student_user.username), ''), ss.student_id) AS studentUsername",
            "FROM submit_situation ss",
            "LEFT JOIN student_profile sp",
            "  ON sp.student_no COLLATE utf8mb4_unicode_ci = ss.student_id COLLATE utf8mb4_unicode_ci",
            " AND sp.status != 'DELETED'",
            "LEFT JOIN tap_user student_user ON student_user.id = sp.user_id",
            "WHERE ss.experiment_id = #{experimentId}",
            "ORDER BY ss.student_id"
    })
    List<TeacherStudentAssignmentRow> findStudentRosterFromSubmitSituation(
            @Param("experimentId") Integer experimentId
    );

    /**
     * 管理员视角：从 class_member + student_profile 全量取学生 roster（不绑定教师）。
     * 可选 classId 过滤（按 teaching_class.id）。
     */
    @Select({
            "<script>",
            "SELECT",
            "  CAST(MIN(tc.id) AS SIGNED) AS classId,",
            "  sp.student_no AS studentId,",
            "  sp.real_name AS studentName,",
            "  COALESCE(NULLIF(TRIM(student_user.username), ''), sp.student_no) AS studentUsername,",
            "  MIN(tc.name) AS className",
            "FROM class_member cm",
            "JOIN student_profile sp",
            "  ON sp.id = cm.student_id",
            " AND sp.status != 'DELETED'",
            "JOIN teaching_class tc ON tc.id = cm.class_id",
            "LEFT JOIN tap_user student_user ON student_user.id = sp.user_id",
            "WHERE cm.member_status = 'ACTIVE'",
            "  <if test='classId != null'>",
            "    AND tc.id = #{classId}",
            "  </if>",
            "GROUP BY sp.id, sp.student_no, sp.real_name, COALESCE(NULLIF(TRIM(student_user.username), ''), sp.student_no)",
            "ORDER BY className, sp.student_no",
            "</script>"
    })
    List<TeacherStudentAssignmentRow> findAllStudentRosterForAdmin(
            @Param("classId") Long classId
    );

    @Select({
            "<script>",
            "SELECT",
            "  CAST(tc.id AS SIGNED) AS classId,",
            "  sp.student_no AS studentId,",
            "  sp.real_name AS studentName,",
            "  COALESCE(NULLIF(TRIM(student_user.username), ''), sp.student_no) AS studentUsername,",
            "  tc.name AS className,",
            "  CAST(ao.id AS SIGNED) AS experimentId,",
            "  " + EXPERIMENT_NAME_EXPR + " AS experimentName,",
            "  ao.deadline_at AS deadline,",
            "  COALESCE(sa.last_submit_at, sa.first_submit_at) AS submitTime,",
            "  COALESCE(sa.best_total_score, sa.latest_total_score) AS score,",
            "  sa.submission_status AS submissionStatus,",
            "  sa.transcript_row_present AS transcriptRowPresent,",
            "  sa.answer_sheet_count AS answerSheetCount,",
            "  sa.scored_code_count AS scoredCodeCount,",
            "  sa.submission_attempt_count AS submissionAttemptCount,",
            "  sa.completion_evidence AS completionEvidence",
            "FROM student_assignment sa",
            "JOIN assignment_offering ao ON ao.id = sa.offering_id",
            "JOIN assignment_template at ON at.id = ao.template_id",
            "JOIN teaching_class tc ON tc.id = ao.class_id",
            "JOIN student_profile sp ON sp.id = sa.student_id",
            "LEFT JOIN tap_user student_user ON student_user.id = sp.user_id",
            "WHERE COALESCE(sa.last_submit_at, sa.first_submit_at) IS NOT NULL",
            "  AND NULLIF(TRIM(COALESCE(sp.real_name, '')), '') IS NOT NULL",
            "  AND NULLIF(TRIM(COALESCE(tc.name, '')), '') IS NOT NULL",
            "  AND NULLIF(TRIM(COALESCE(" + EXPERIMENT_NAME_EXPR + ", '')), '') IS NOT NULL",
            "  <if test='scope == null or scope != \"all\"'>",
            "    AND " + DATA_STRUCTURE_SCOPE_PREDICATE,
            "  </if>",
            "  <if test='classId != null'>",
            "    AND ao.class_id = #{classId}",
            "  </if>",
            "  <if test='classKeyword != null and classKeyword != \"\"'>",
            "    AND (",
            "      " + NORMALIZED_PTA_KEYWORD_EXPR + " COLLATE utf8mb4_unicode_ci = #{classKeyword} COLLATE utf8mb4_unicode_ci",
            "      OR " + NORMALIZED_CLASS_NAME_EXPR + " COLLATE utf8mb4_unicode_ci = #{classKeyword} COLLATE utf8mb4_unicode_ci",
            "    )",
            "  </if>",
            "  <if test='experimentId != null'>",
            "    AND ao.id = #{experimentId}",
            "  </if>",
            "ORDER BY COALESCE(sa.last_submit_at, sa.first_submit_at) DESC, sa.id DESC",
            "LIMIT #{limit}",
            "</script>"
    })
    List<TeacherStudentAssignmentRow> findRecentSubmittedAssignmentsForAdmin(
            @Param("classId") Long classId,
            @Param("classKeyword") String classKeyword,
            @Param("experimentId") Integer experimentId,
            @Param("scope") String scope,
            @Param("limit") Integer limit
    );

    /**
     * 管理员视角：全量取 assignment_offering 实验列表（不绑定教师）。
     * 可选 classId 过滤与 classKeyword（pta_keyword/name）匹配。
     * scope='all' 时跳过课程范围过滤（DATA_STRUCTURE_SCOPE_PREDICATE），返回所有实验。
     */
    @Select({
            "<script>",
            "SELECT",
            "  CAST(ao.id AS SIGNED) AS experimentId,",
            "  " + EXPERIMENT_NAME_EXPR + " AS name,",
            "  ao.deadline_at AS deadline,",
            "  ao.created_at AS createdTime",
            "FROM assignment_offering ao",
            "JOIN assignment_template at ON at.id = ao.template_id",
            "JOIN teaching_class tc ON tc.id = ao.class_id",
            "WHERE 1=1",
            "  AND " + ACTIVE_PROGRAMMING_PROBLEM_PREDICATE,
            "  <if test='scope == null or scope != \"all\"'>",
            "    AND " + DATA_STRUCTURE_SCOPE_PREDICATE,
            "  </if>",
            "  <if test='classId != null'>",
            "    AND ao.class_id = #{classId}",
            "  </if>",
            "  <if test='classKeyword != null and classKeyword != \"\"'>",
            "    AND (",
            "      " + NORMALIZED_PTA_KEYWORD_EXPR + " COLLATE utf8mb4_unicode_ci = #{classKeyword} COLLATE utf8mb4_unicode_ci",
            "      OR " + NORMALIZED_CLASS_NAME_EXPR + " COLLATE utf8mb4_unicode_ci = #{classKeyword} COLLATE utf8mb4_unicode_ci",
            "    )",
            "  </if>",
            "ORDER BY",
            "  CASE WHEN ao.seq_no IS NULL THEN 1 ELSE 0 END,",
            "  ao.seq_no,",
            "  ao.id",
            "</script>"
    })
    List<TeacherExperimentSummaryRow> findAllExperimentsForAdmin(
            @Param("classId") Long classId,
            @Param("classKeyword") String classKeyword,
            @Param("scope") String scope
    );

    /**
     * 获取学生在指定实验中每个题目的所有提交尝试（用于AI错误分析）
     * 查询 student_problem_attempt 获取每次提交的 judgeStatus、compiler、submittedAt 等
     */
    @Select({
            "SELECT",
            "  spa.id AS attemptId,",
            "  spa.judge_status AS judgeStatus,",
            "  spa.compiler AS compiler,",
            "  spa.submitted_at AS submittedAt,",
            "  spa.score AS score,",
            "  spa.runtime_ms AS runtimeMs,",
            "  spa.memory_kb AS memoryKb,",
            "  code_artifact.text_content AS code,",
            "  spa.problem_id AS problemId,",
            "  ap.title AS problemTitle,",
            "  prsr.raw_json AS rawJson",
            "FROM student_problem_attempt spa",
            "JOIN student_problem_state sps",
            "  ON sps.offering_id = spa.offering_id",
            " AND sps.problem_id = spa.problem_id",
            " AND sps.student_id = spa.student_id",
            "JOIN assignment_problem ap ON ap.id = spa.problem_id",
            "LEFT JOIN artifact code_artifact ON code_artifact.id = sps.latest_code_artifact_id",
            "LEFT JOIN pta_raw_submission_row prsr ON prsr.id = spa.raw_row_id",
            "WHERE spa.offering_id = #{experimentId}",
            "  AND spa.student_id = (",
            "    SELECT sp.id FROM student_profile sp",
            "    WHERE sp.student_no COLLATE utf8mb4_unicode_ci = #{studentNo} COLLATE utf8mb4_unicode_ci",
            "    AND sp.status != 'DELETED'",
            "    LIMIT 1",
            "  )",
            "ORDER BY spa.problem_id, spa.submitted_at ASC"
    })
    List<com.tap.backend.academic.entity.StudentSubmissionAttempt> findSubmissionAttemptsForErrorAnalysis(
            @Param("studentNo") String studentNo,
            @Param("experimentId") Integer experimentId
    );

    /**
     * 备选查询：当 student_problem_attempt 无数据时，直接从 PTA 原始表构建提交记录。
     * 通过 external_identity_binding → assignment_problem → pta_raw_answer_sheet 三表桥接。
     */
    @Select({
            "SELECT",
            "  prsr.id AS attemptId,",
            "  prsr.judge_status AS judgeStatus,",
            "  prsr.compiler AS compiler,",
            "  STR_TO_DATE(prsr.submitted_at_text, '%Y-%m-%dT%H:%i:%sZ') AS submittedAt,",
            "  CAST(prsr.score_text AS DECIMAL(10,2)) AS score,",
            "  CAST(prsr.runtime_text AS SIGNED) AS runtimeMs,",
            "  CAST(prsr.memory_text AS SIGNED) AS memoryKb,",
            "  COALESCE(art.text_content, sc.code) AS code,",
            "  ap.id AS problemId,",
            "  ap.title AS problemTitle,",
            "  prsr.raw_json AS rawJson",
            "FROM pta_raw_submission_row prsr",
            "JOIN external_identity_binding eib",
            "  ON eib.external_id = prsr.pta_user_id",
            "  AND eib.source_system = 'PTA'",
            "  AND eib.binding_type = 'PTA_USER_ID'",
            "  AND eib.is_active = 1",
            "JOIN student_profile sp ON sp.id = eib.entity_id",
            "JOIN assignment_problem ap ON ap.source_problem_id = prsr.pta_problem_id",
            "LEFT JOIN student_problem_state sps",
            "  ON sps.offering_id = ap.offering_id",
            "  AND sps.problem_id = ap.id",
            "  AND sps.student_id = sp.id",
            "LEFT JOIN artifact art ON art.id = sps.latest_code_artifact_id",
            "LEFT JOIN student_code sc",
            "  ON sc.student_id = sp.student_no",
            "  AND sc.experiment_id = ap.offering_id",
            "WHERE sp.student_no = #{studentNo}",
            "  AND ap.offering_id = #{experimentId}",
            "ORDER BY ap.id, prsr.submitted_at_text ASC"
    })
    List<com.tap.backend.academic.entity.StudentSubmissionAttempt> findSubmissionAttemptsFromRaw(
            @Param("studentNo") String studentNo,
            @Param("experimentId") Integer experimentId
    );

    /**
     * 仅取代码（无判题记录时的最后兜底）。
     */
    @Select({
            "SELECT sc.code",
            "FROM student_code sc",
            "WHERE sc.student_id = #{studentNo}",
            "  AND sc.experiment_id = #{experimentId}",
            "LIMIT 1"
    })
    String findCodeOnly(@Param("studentNo") String studentNo, @Param("experimentId") int experimentId);

    /**
     * 查实验中所有题目的信息（编号、标题、题面），按 sort_order 排序。
     */
    @Select({
            "SELECT ap.id, ap.problem_no AS problemNo, ap.title, ",
            "  COALESCE(apd.content, ap.statement_md, '') AS description",
            "FROM assignment_problem ap",
            "LEFT JOIN pta_problem_detail apd ON apd.problem_set_problem_id = ap.problem_no",
            "WHERE ap.offering_id = #{experimentId}",
            "ORDER BY COALESCE(ap.sort_order, 0), ap.id"
    })
    List<Map<String, Object>> findProblemInfoForExperiment(@Param("experimentId") int experimentId);

    @Select({
            "SELECT COALESCE(NULLIF(TRIM(ao.title_override), ''), at.title)",
            "FROM assignment_offering ao",
            "JOIN assignment_template at ON at.id = ao.template_id",
            "WHERE ao.id = #{offeringId}"
    })
    String findOfferingName(@Param("offeringId") int offeringId);

    /**
     * 查询学生所有"进行中"的实验（未截止，发布时间可选）。
     * 用于登录后自动预警扫描。
     */
    @Select({
            "SELECT DISTINCT CAST(ao.id AS SIGNED) AS experimentId,",
            "  " + EXPERIMENT_NAME_EXPR + " AS experimentName",
            "FROM student_profile sp",
            "JOIN class_student cs ON cs.student_num = sp.student_no COLLATE utf8mb4_unicode_ci",
            "JOIN teaching_class tc ON tc.id = cs.class_id",
            "JOIN assignment_offering ao ON ao.class_id = cs.class_id",
            "JOIN assignment_template at ON at.id = ao.template_id",
            "WHERE sp.student_no = #{studentNo}",
            "  AND (tc.status IS NULL OR tc.status = 'ACTIVE')",
            "  AND ao.status <> 'ARCHIVED'",
            "  AND (ao.published_at IS NULL OR ao.published_at <= NOW(3))",
            "  AND (ao.deadline_at IS NULL OR ao.deadline_at > NOW(3))",
            "ORDER BY experimentId"
    })
    List<Map<String, Object>> findActiveExperimentsForStudent(@Param("studentNo") String studentNo);
}
