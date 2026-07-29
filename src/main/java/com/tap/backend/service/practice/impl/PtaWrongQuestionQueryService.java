package com.tap.backend.service.practice.impl;

import com.tap.backend.academic.service.CourseScopeMatcher;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads native PTA wrong attempts without requiring a LeetCode problem mapping. */
@Service
public class PtaWrongQuestionQueryService {

  private static final String ACCEPTED_STATUS_SQL = "(" +
      "UPPER(TRIM(COALESCE(spa.judge_status, ''))) IN " +
      "('C','AC','ACCEPTED','CORRECT','PASS','PASSED','100') " +
      "OR TRIM(COALESCE(spa.judge_status, '')) IN " +
      "('\u6ee1\u5206','\u6210\u529f','\u901a\u8fc7','\u7b54\u6848\u6b63\u786e'))";

  private final EntityManager em;

  public PtaWrongQuestionQueryService(EntityManager em) {
    this.em = em;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> list(String studentNo, Long classId, int minErrors) {
    int threshold = Math.max(1, Math.min(100, minErrors));
    String classPredicate = classId == null ? "" : " AND ao.class_id = :classId ";
    String sql = """
        SELECT
          CAST(spa.offering_id AS SIGNED) AS offering_id,
          CAST(spa.problem_id AS SIGNED) AS problem_id,
          COUNT(*) AS attempt_count,
          SUM(CASE WHEN %s THEN 1 ELSE 0 END) AS accepted_count,
          SUM(CASE WHEN %s THEN 0 ELSE 1 END) AS error_count,
          ap.title AS problem_title,
          ap.problem_no,
          ap.source_problem_id,
          COALESCE(NULLIF(TRIM(ao.title_override), ''), at.title) AS offering_title,
          ao.pta_problem_set_id,
          MAX(apd.knowledge_leaf) AS knowledge_point,
          MAX(apd.knowledge_path) AS knowledge_path,
          MAX(apd.difficulty_label) AS difficulty_label,
          MAX(apd.problem_url) AS problem_url,
          COALESCE(NULLIF(TRIM(c.name), ''), NULLIF(TRIM(tc.course_name), ''), '') AS course_name
        FROM student_problem_attempt spa
        JOIN student_profile sp ON sp.id = spa.student_id
        JOIN assignment_problem ap
          ON ap.id = spa.problem_id AND ap.offering_id = spa.offering_id
        JOIN assignment_offering ao ON ao.id = spa.offering_id
        JOIN assignment_template at ON at.id = ao.template_id
        JOIN teaching_class tc ON tc.id = ao.class_id
        JOIN class_member cm
          ON cm.class_id = ao.class_id
         AND cm.student_id = spa.student_id
         AND cm.member_status = 'ACTIVE'
        LEFT JOIN course c ON c.id = tc.course_id
        LEFT JOIN pta_problem_detail apd ON apd.id = (
          SELECT pd.id
          FROM pta_problem_detail pd
          WHERE (
            pd.problem_set_problem_id COLLATE utf8mb4_unicode_ci = ap.problem_no COLLATE utf8mb4_unicode_ci
            OR (ap.source_problem_id IS NOT NULL
                AND pd.problem_set_problem_id COLLATE utf8mb4_unicode_ci = ap.source_problem_id COLLATE utf8mb4_unicode_ci)
            OR (ap.source_problem_id IS NOT NULL
                AND pd.pta_global_problem_id COLLATE utf8mb4_unicode_ci = ap.source_problem_id COLLATE utf8mb4_unicode_ci)
          )
          ORDER BY CASE
            WHEN ao.pta_problem_set_id IS NOT NULL
             AND pd.problem_set_id COLLATE utf8mb4_unicode_ci = ao.pta_problem_set_id COLLATE utf8mb4_unicode_ci THEN 0
            WHEN CAST(pd.experiment_id AS CHAR) = CAST(ao.id AS CHAR) THEN 1
            ELSE 2
          END, pd.updated_at DESC, pd.id DESC
          LIMIT 1
        )
        WHERE sp.student_no = :studentNo
        %s
        GROUP BY spa.offering_id, spa.problem_id, ap.title, ap.problem_no,
                 ap.source_problem_id, ao.title_override, at.title,
                 ao.pta_problem_set_id, c.name, tc.course_name
        HAVING error_count >= :minErrors
        ORDER BY error_count DESC, MAX(spa.submitted_at) DESC
        LIMIT 100
        """.formatted(ACCEPTED_STATUS_SQL, ACCEPTED_STATUS_SQL, classPredicate);

    var query = em.createNativeQuery(sql)
        .setParameter("studentNo", studentNo)
        .setParameter("minErrors", threshold);
    if (classId != null) {
      query.setParameter("classId", classId);
    }

    @SuppressWarnings("unchecked")
    List<Object[]> rows = query.getResultList();
    List<Map<String, Object>> items = new ArrayList<>(rows.size());
    for (Object[] row : rows) {
      if (!CourseScopeMatcher.belongsToCourse(row[14], row[8])) {
        continue;
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("offering_id", toLong(row[0]));
      item.put("problem_id", toLong(row[1]));
      item.put("attempt_count", toInt(row[2]));
      item.put("accepted_count", toInt(row[3]));
      item.put("error_count", toInt(row[4]));
      item.put("problem_title", row[5]);
      item.put("problem_no", row[6]);
      item.put("source_problem_id", row[7]);
      item.put("offering_title", row[8]);
      item.put("pta_problem_set_id", row[9]);
      item.put("knowledge_point", row[10]);
      item.put("knowledge_path", row[11]);
      item.put("difficulty_label", row[12]);
      item.put("problem_url", row[13]);
      items.add(item);
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("minErrors", threshold);
    result.put("totalCount", items.size());
    result.put("items", items);
    return result;
  }

  private static long toLong(Object value) {
    return value instanceof Number number ? number.longValue() : 0L;
  }

  private static int toInt(Object value) {
    return value instanceof Number number ? number.intValue() : 0;
  }
}
