package com.tap.backend.academic.service;

import com.tap.backend.academic.dao.LeetCodeRecommendDao;
import com.tap.backend.academic.dao.LeetCodeSubmissionRecordDao;
import com.tap.backend.academic.entity.LeetCodeRecommendItem;
import com.tap.backend.academic.entity.LeetCodeRecommendRequest;
import com.tap.backend.academic.entity.LeetCodeSubmissionRecord;
import com.tap.backend.academic.learningtracking.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class LearningTrackingService {

    private static final Logger logger = LoggerFactory.getLogger(LearningTrackingService.class);
    private static final int TIMELINE_LIMIT = 200;

    @PersistenceContext
    private EntityManager em;

    private final LeetCodeSubmissionRecordDao submissionRecordDao;
    private final LeetCodeRecommendDao recommendDao;

    public LearningTrackingService(
            LeetCodeSubmissionRecordDao submissionRecordDao,
            LeetCodeRecommendDao recommendDao) {
        this.submissionRecordDao = submissionRecordDao;
        this.recommendDao = recommendDao;
    }

    public LearningTrackingResponse getLearningTracking(String studentNo, int experimentId, Integer studentProfileId) {
        LearningTrackingResponse resp = new LearningTrackingResponse();
        resp.setStudentId(studentNo);

        try {
            Object nameRow = em.createNativeQuery(
                    "SELECT real_name FROM student_profile WHERE student_no = ?1 LIMIT 1"
            ).setParameter(1, studentNo).getSingleResult();
            resp.setStudentName(nameRow != null ? String.valueOf(nameRow) : studentNo);
        } catch (Exception e) {
            resp.setStudentName(studentNo);
        }

        LearningTrackingResponse.LearningTrackingSummary summary =
                new LearningTrackingResponse.LearningTrackingSummary();

        List<PtaPracticeSetSummary> ptaSets = getPtaPracticeSummary(studentNo);
        summary.setPtaPracticeSets(ptaSets);
        summary.setPtaTotalSets(ptaSets.size());
        summary.setPtaCompletedSets((int) ptaSets.stream()
                .filter(s -> "completed".equalsIgnoreCase(s.getStatus())).count());

        LeetCodePracticeSummary lcSummary = getLeetCodePracticeSummary(studentProfileId);
        summary.setLeetcode(lcSummary);

        resp.setSummary(summary);
        resp.setTimeline(getMergedTimeline(studentNo, experimentId, studentProfileId, ptaSets));
        return resp;
    }

    @SuppressWarnings("unchecked")
    List<PtaPracticeSetSummary> getPtaPracticeSummary(String studentNo) {
        List<PtaPracticeSetSummary> result = new ArrayList<>();
        try {
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT ao.id, COALESCE(NULLIF(ao.title_override, ''), at.title) AS title, " +
                            "sa.accepted_problem_count, sa.submitted_problem_count, sa.problem_count, " +
                            "sa.best_total_score, sa.submission_status " +
                            "FROM student_profile sp " +
                            "JOIN student_assignment sa ON sa.student_id = sp.id " +
                            "JOIN assignment_offering ao ON ao.id = sa.offering_id " +
                            "JOIN assignment_template at ON at.id = ao.template_id " +
                            "WHERE sp.student_no = ?1 " +
                            "AND ao.status <> 'ARCHIVED' " +
                            "ORDER BY ao.id"
            ).setParameter(1, studentNo).getResultList();

            for (Object[] row : rows) {
                PtaPracticeSetSummary s = new PtaPracticeSetSummary();
                s.setOfferingId(toLong(row[0]));
                s.setTitle(toStringVal(row[1]));
                s.setAcceptedCount(toInt(row[2]));
                s.setSubmittedCount(toInt(row[3]));
                s.setProblemCount(toInt(row[4]));
                s.setScore(toDouble(row[5]));
                s.setStatus(mapSubmissionStatus(toStringVal(row[6])));
                s.setSourceUrl("https://pintia.cn/problem-sets/" + s.getOfferingId());
                result.add(s);
            }
        } catch (Exception e) {
            logger.warn("Failed to load PTA practice summary for studentNo={}: {}", studentNo, e.getMessage());
        }
        return result;
    }

    LeetCodePracticeSummary getLeetCodePracticeSummary(Integer studentProfileId) {
        LeetCodePracticeSummary s = new LeetCodePracticeSummary();
        if (studentProfileId == null) return s;
        try {
            Map<String, Object> counts = submissionRecordDao.countByStudentId(studentProfileId);
            if (counts != null) {
                s.setSubmittedCount(toInt(counts.get("totalSubmissions")));
                s.setAcceptedCount(toInt(counts.get("acceptedCount")));
                s.setWrongCount(toInt(counts.get("wrongCount")));
                Object avgScore = counts.get("avgScore");
                s.setAvgScore(avgScore instanceof BigDecimal ? ((BigDecimal) avgScore).doubleValue() :
                              avgScore instanceof Number ? ((Number) avgScore).doubleValue() : null);
            }
            List<LeetCodeRecommendRequest> requests = recommendDao.findRequestsByStudentId(studentProfileId, 1);
            if (requests != null && !requests.isEmpty()) {
                List<LeetCodeRecommendItem> items = recommendDao.findItemsByRequestId(requests.get(0).getRequestId());
                s.setTotalRecommended(items != null ? items.size() : 0);
            }
            if (s.getTotalRecommended() > 0 && s.getSubmittedCount() > 0) {
                s.setCompletionRate(Math.min(1.0,
                        Math.round((double) s.getAcceptedCount() / s.getTotalRecommended() * 100.0) / 100.0));
            }
        } catch (Exception e) {
            logger.warn("Failed to load LeetCode practice summary for studentId={}: {}", studentProfileId, e.getMessage());
        }
        return s;
    }

    List<TimelineEntry> getMergedTimeline(String studentNo, int experimentId,
                                          Integer studentProfileId,
                                          List<PtaPracticeSetSummary> ptaSets) {
        List<TimelineEntry> timeline = new ArrayList<>();
        timeline.addAll(getPtaTimeline(studentNo, ptaSets));
        if (studentProfileId != null) {
            timeline.addAll(getLeetCodeTimeline(studentProfileId));
        }
        Collections.sort(timeline);
        return timeline;
    }

    @SuppressWarnings("unchecked")
    private List<TimelineEntry> getPtaTimeline(String studentNo, List<PtaPracticeSetSummary> ptaSets) {
        List<TimelineEntry> entries = new ArrayList<>();
        if (ptaSets.isEmpty()) return entries;
        try {
            String offeringIds = ptaSets.stream()
                    .map(s -> String.valueOf(s.getOfferingId()))
                    .collect(Collectors.joining(","));

            List<Object[]> rows = em.createNativeQuery(
                    "SELECT spa.submitted_at, spa.judge_status, spa.score, " +
                            "ap.title AS problem_title, " +
                            "COALESCE(apd.title, ap.title) AS display_title, " +
                            "apd.difficulty_label " +
                            "FROM student_profile sp " +
                            "JOIN student_assignment sa ON sa.student_id = sp.id " +
                            "JOIN student_problem_attempt spa ON spa.student_id = sp.id AND spa.offering_id = sa.offering_id " +
                            "JOIN assignment_problem ap ON ap.id = spa.problem_id " +
                            "LEFT JOIN pta_problem_detail apd ON apd.problem_set_problem_id = ap.problem_no " +
                            "WHERE sp.student_no = ?1 " +
                            "AND sa.offering_id IN (" + offeringIds + ") " +
                            "ORDER BY spa.submitted_at DESC " +
                            "LIMIT " + TIMELINE_LIMIT
            ).setParameter(1, studentNo).getResultList();

            for (Object[] row : rows) {
                TimelineEntry e = new TimelineEntry();
                e.setSource("pta");
                e.setSourceLabel("PTA");
                e.setTimestamp(toLocalDateTime(row[0]));
                e.setResult(toStringVal(row[1]));
                e.setScore(toDouble(row[2]));
                e.setTitle(firstNonEmpty(toStringVal(row[4]), toStringVal(row[3]), "未知题目"));
                e.setAction("submitted");
                entries.add(e);
            }
        } catch (Exception e) {
            logger.warn("Failed to load PTA timeline for studentNo={}: {}", studentNo, e.getMessage());
        }
        return entries;
    }

    private List<TimelineEntry> getLeetCodeTimeline(Integer studentProfileId) {
        List<TimelineEntry> entries = new ArrayList<>();
        try {
            List<LeetCodeSubmissionRecord> records =
                    submissionRecordDao.findTimelineByStudentId(studentProfileId, TIMELINE_LIMIT);
            if (records == null) return entries;
            for (LeetCodeSubmissionRecord r : records) {
                TimelineEntry e = new TimelineEntry();
                e.setSource("leetcode");
                e.setSourceLabel("LeetCode");
                e.setTimestamp(r.getSubmittedAt());
                e.setTitle(r.getProblemTitle() != null ? r.getProblemTitle() : "Problem #" + r.getProblemId());
                e.setAction("submitted");
                e.setResult(Boolean.TRUE.equals(r.getAccepted()) ? "ACCEPTED" : "WRONG");
                e.setScore(r.getScore() != null ? r.getScore().doubleValue() : null);
                e.setProblemUrl(r.getProblemSourceUrl());
                entries.add(e);
            }
        } catch (Exception e) {
            logger.warn("Failed to load LeetCode timeline for studentId={}: {}", studentProfileId, e.getMessage());
        }
        return entries;
    }

    private String mapSubmissionStatus(String raw) {
        if (raw == null) return "not_started";
        switch (raw.toLowerCase()) {
            case "submitted": return "in_progress";
            case "graded": return "completed";
            case "not_started": return "not_started";
            default: return raw.toLowerCase();
        }
    }

    private Long toLong(Object val) {
        if (val instanceof Number) return ((Number) val).longValue();
        if (val instanceof String) { try { return Long.parseLong((String) val); } catch (NumberFormatException e) {} }
        return null;
    }

    private int toInt(Object val) {
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) { try { return Integer.parseInt((String) val); } catch (NumberFormatException e) {} }
        return 0;
    }

    private Double toDouble(Object val) {
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val instanceof String) { try { return Double.parseDouble((String) val); } catch (NumberFormatException e) {} }
        return null;
    }

    private String toStringVal(Object val) { return val == null ? null : String.valueOf(val); }

    private String firstNonEmpty(String... vals) {
        for (String v : vals) { if (v != null && !v.isEmpty()) return v; }
        return "";
    }

    private LocalDateTime toLocalDateTime(Object val) {
        if (val instanceof LocalDateTime) return (LocalDateTime) val;
        if (val instanceof Timestamp) return ((Timestamp) val).toLocalDateTime();
        if (val instanceof java.util.Date) return new java.sql.Timestamp(((java.util.Date) val).getTime()).toLocalDateTime();
        return null;
    }
}
