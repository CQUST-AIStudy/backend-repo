package com.tap.backend.academic.leetcode.execution;

import com.tap.backend.academic.dao.LeetCodeSubmissionRecordDao;
import com.tap.backend.academic.entity.LeetCodeProblem;
import com.tap.backend.academic.entity.LeetCodeSubmissionRecord;
import com.tap.backend.academic.service.LeetCodeExecutionService;
import com.tap.backend.academic.service.LeetCodeProblemService;
import com.tap.backend.academic.service.LeetCodeRecommendationService;
import com.tap.backend.domain.practice.WrongQuestionEntity;
import com.tap.backend.dto.practice.RecordSubmissionCommand;
import com.tap.backend.service.practice.WrongQuestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class LeetCodeSubmissionFacade {

    private static final Logger log = LoggerFactory.getLogger(LeetCodeSubmissionFacade.class);

    private final LeetCodeExecutionService executionService;
    private final LeetCodeRecommendationService recommendationService;
    private final LeetCodeProblemService problemService;
    private final WrongQuestionService wrongQuestionService;
    private final LeetCodeSubmissionRecordDao submissionRecordDao;

    public LeetCodeSubmissionFacade(
            LeetCodeExecutionService executionService,
            @Qualifier("intelligentRecommendationService") LeetCodeRecommendationService recommendationService,
            LeetCodeProblemService problemService,
            WrongQuestionService wrongQuestionService,
            LeetCodeSubmissionRecordDao submissionRecordDao) {
        this.executionService = executionService;
        this.recommendationService = recommendationService;
        this.problemService = problemService;
        this.wrongQuestionService = wrongQuestionService;
        this.submissionRecordDao = submissionRecordDao;
    }

    public Map<String, Object> submitSolution(
            Integer studentId,
            Long problemId,
            String code,
            String language,
            String recommendationRequestId,
            String recommendationSessionId) {
        return submitSolution(studentId, null, problemId, code, language,
                recommendationRequestId, recommendationSessionId);
    }

    public Map<String, Object> submitSolution(
            Integer studentId,
            String studentNo,
            Long problemId,
            String code,
            String language,
            String recommendationRequestId,
            String recommendationSessionId) {
        Map<String, Object> result = executionService.submitSolution(studentId, problemId, code, language);

        // 持久化到 LeetCodeSubmissionRecord（供学情追踪查询）
        try {
            LeetCodeSubmissionRecord record = new LeetCodeSubmissionRecord();
            record.setStudentId(studentId);
            record.setProblemId(problemId);
            record.setCode(code);
            record.setLanguage(language);
            record.setAccepted(Boolean.TRUE.equals(result.get("accepted")));
            record.setScore(result.get("score") instanceof Number ? ((Number) result.get("score")).intValue() : null);
            record.setAiFeedback(result.get("aiFeedback") instanceof String ? (String) result.get("aiFeedback") : null);
            record.setConfidence(extractConfidence(result));
            record.setPassedCases(extractIntFromDetails(result, "passedCases"));
            record.setTotalCases(extractIntFromDetails(result, "totalCases"));
            record.setRecommendationRequestId(recommendationRequestId);
            record.setRecommendationSessionId(recommendationSessionId);
            submissionRecordDao.insert(record);
        } catch (Exception e) {
            log.warn("Failed to persist LeetCode submission record for student={} problem={}", studentId, problemId, e);
        }

        if (Boolean.TRUE.equals(result.get("accepted"))
                && recommendationRequestId != null
                && !recommendationRequestId.isBlank()) {
            try {
                recommendationService.recordFeedback(
                        recommendationRequestId,
                        studentId,
                        problemId,
                        "complete",
                        recommendationSessionId
                );
            } catch (Exception ex) {
                log.warn("Recommendation feedback recording failed (student={}, problem={}): {}",
                        studentId, problemId, ex.getMessage());
            }
        }
        ingestWrongQuestionIfNeeded(studentNo, problemId, code, result);
        return result;
    }

    private void ingestWrongQuestionIfNeeded(String studentNo, Long problemId, String code, Map<String, Object> result) {
        if (studentNo == null || studentNo.isBlank() || problemId == null || result == null) {
            return;
        }
        try {
            boolean accepted = Boolean.TRUE.equals(result.get("accepted"));
            if (accepted) {
                // AC submissions are tracked via the notebook retry endpoint, not here.
                return;
            }
            LeetCodeProblem problem = problemService.findById(problemId);
            String title = problem == null ? null : problem.getTitleMain();
            String slug = problem == null ? null : problem.getSourceKey();
            String difficulty = problem == null ? null : problem.getDifficulty();
            String status = stringifyOrDefault(result.get("status"), "FAILED");
            String errorMessage = extractErrorMessage(result);

            RecordSubmissionCommand cmd = new RecordSubmissionCommand(
                    studentNo,
                    problemId,
                    title,
                    slug,
                    difficulty,
                    status,
                    code,
                    errorMessage,
                    null,
                    null,
                    WrongQuestionEntity.SourceType.LEETCODE_PRACTICE
            );
            wrongQuestionService.recordSubmission(cmd);
        } catch (Exception ex) {
            log.warn("Wrong-question ingest failed (studentNo={}, problem={}): {}",
                    studentNo, problemId, ex.getMessage());
        }
    }

    private static String extractErrorMessage(Map<String, Object> result) {
        Object details = result.get("details");
        if (details instanceof Map<?, ?> d) {
            Object err = d.get("error");
            if (err instanceof String s && !s.isBlank()) return s;
        }
        Object msg = result.get("message");
        return msg == null ? null : msg.toString();
    }

    private static String stringifyOrDefault(Object value, String fallback) {
        if (value == null) return fallback;
        String s = value.toString();
        return s.isBlank() ? fallback : s;
    }

    @SuppressWarnings("unchecked")
    private int extractIntFromDetails(Map<String, Object> result, String key) {
        try {
            Object details = result.get("details");
            if (details instanceof Map) {
                Object val = ((Map<String, Object>) details).get(key);
                if (val instanceof Number) return ((Number) val).intValue();
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private Double extractConfidence(Map<String, Object> result) {
        try {
            Object details = result.get("details");
            if (details instanceof Map) {
                Object confidence = ((Map<String, Object>) details).get("confidence");
                if (confidence instanceof String) {
                    String s = ((String) confidence).replace("%", "").trim();
                    return Double.parseDouble(s) / 100.0;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
