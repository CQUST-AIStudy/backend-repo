package com.tap.backend.service.practice.impl;

import com.tap.backend.domain.practice.WrongQuestionEntity;
import com.tap.backend.dto.practice.RecordSubmissionCommand;
import com.tap.backend.service.practice.WrongQuestionService;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Scans student_problem_attempt rows produced by PTA sync and pushes non-AC
 * submissions into the wrong-question notebook via {@link WrongQuestionService}.
 *
 * <p>Mapping strategy: PTA attempts reference {@code assignment_problem.source_problem_id};
 * we join that to {@code leetcode_problem_bank.source_key}. Rows that do not map are skipped
 * (counted in {@code ingestionSkippedCount}) — this matches the conservative v1 plan.</p>
 *
 * <p>Dedup: per (student, leetcode_problem) pair we only ingest the single most recent
 * non-AC attempt per call. This keeps repeated syncs idempotent without requiring an
 * extra watermark column.</p>
 */
@Service
public class WrongQuestionPtaIngestionService {

  private static final Logger log = LoggerFactory.getLogger(WrongQuestionPtaIngestionService.class);

  private final EntityManager em;
  private final WrongQuestionService wrongQuestionService;

  @Value("${wrong-question.pta.ingest-window-days:30}")
  private int ingestWindowDays;

  public WrongQuestionPtaIngestionService(EntityManager em, WrongQuestionService wrongQuestionService) {
    this.em = em;
    this.wrongQuestionService = wrongQuestionService;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public IngestionSummary ingestRecentPtaWrongAttempts(Long classId) {
    if (classId == null) {
      return new IngestionSummary(0, 0, 0);
    }

    String sql = """
        SELECT
          sp.student_no        AS student_no,
          lb.id                AS leetcode_id,
          lb.title_main        AS problem_title,
          lb.source_key        AS problem_slug,
          lb.difficulty        AS difficulty,
          latest.judge_status  AS judge_status,
          latest.runtime_ms    AS runtime_ms,
          latest.memory_kb     AS memory_kb
        FROM (
          SELECT
            spa.student_id            AS student_id,
            ap.source_problem_id      AS source_problem_id,
            spa.judge_status           AS judge_status,
            spa.runtime_ms             AS runtime_ms,
            spa.memory_kb              AS memory_kb,
            ROW_NUMBER() OVER (
              PARTITION BY spa.student_id, ap.source_problem_id
              ORDER BY spa.submitted_at DESC, spa.id DESC
            ) AS rn
          FROM student_problem_attempt spa
          JOIN class_student cs ON cs.student_num COLLATE utf8mb4_unicode_ci = (
            SELECT sp2.student_no FROM student_profile sp2
            WHERE sp2.id = spa.student_id AND sp2.status <> 'DELETED'
            LIMIT 1
          ) COLLATE utf8mb4_unicode_ci
          JOIN assignment_problem ap ON ap.id = spa.problem_id
          WHERE cs.class_id = ?1
            AND ap.source_problem_id IS NOT NULL
            AND UPPER(COALESCE(spa.judge_status, '')) NOT IN ('C', 'AC', 'ACCEPTED')
            AND spa.submitted_at >= DATE_SUB(NOW(), INTERVAL ?2 DAY)
        ) latest
        JOIN student_profile sp ON sp.id = latest.student_id
        JOIN leetcode_problem_bank lb
          ON lb.source_key COLLATE utf8mb4_unicode_ci = latest.source_problem_id COLLATE utf8mb4_unicode_ci
        WHERE latest.rn = 1
        """;

    @SuppressWarnings("unchecked")
    List<Object[]> rows = em.createNativeQuery(sql)
        .setParameter(1, classId)
        .setParameter(2, ingestWindowDays)
        .getResultList();

    int ingested = 0;
    int skipped = 0;
    int errors = 0;

    for (Object[] row : rows) {
      try {
        String studentNo = stringOrNull(row[0]);
        Long leetcodeId = longOrNull(row[1]);
        if (studentNo == null || leetcodeId == null) {
          skipped++;
          continue;
        }
        RecordSubmissionCommand cmd = new RecordSubmissionCommand(
            studentNo,
            leetcodeId,
            stringOrNull(row[2]),
            stringOrNull(row[3]),
            stringOrNull(row[4]),
            stringOrFallback(row[5], "WRONG_ANSWER"),
            null,
            null,
            intOrNull(row[6]),
            intOrNull(row[7]),
            WrongQuestionEntity.SourceType.PTA_SYNCED
        );
        wrongQuestionService.recordSubmission(cmd);
        ingested++;
      } catch (Exception ex) {
        log.warn("PTA wrong-question ingest row failed for class {}: {}", classId, ex.getMessage());
        errors++;
      }
    }

    log.info("PTA wrong-question ingest class={} window_days={} ingested={} skipped={} errors={}",
        classId, ingestWindowDays, ingested, skipped, errors);
    return new IngestionSummary(ingested, skipped, errors);
  }

  public record IngestionSummary(int ingested, int skipped, int errors) {}

  private static String stringOrNull(Object v) {
    if (v == null) return null;
    String s = v.toString();
    return s.isEmpty() ? null : s;
  }

  private static String stringOrFallback(Object v, String fallback) {
    String s = stringOrNull(v);
    return s == null ? fallback : s;
  }

  private static Long longOrNull(Object v) {
    if (v == null) return null;
    if (v instanceof Number n) return n.longValue();
    try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return null; }
  }

  private static Integer intOrNull(Object v) {
    if (v == null) return null;
    if (v instanceof Number n) return n.intValue();
    try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return null; }
  }

  @SuppressWarnings("unused")
  private static Instant instantOrNull(Object v) {
    if (v == null) return null;
    if (v instanceof Timestamp ts) return ts.toInstant();
    if (v instanceof java.util.Date d) return d.toInstant();
    return null;
  }
}
