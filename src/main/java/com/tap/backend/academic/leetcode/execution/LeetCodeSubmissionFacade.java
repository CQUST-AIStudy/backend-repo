package com.tap.backend.academic.leetcode.execution;

import com.tap.backend.academic.dao.LeetCodeSubmissionRecordDao;
import com.tap.backend.academic.entity.LeetCodeSubmissionRecord;
import com.tap.backend.academic.service.LeetCodeExecutionService;
import com.tap.backend.academic.service.LeetCodeRecommendationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class LeetCodeSubmissionFacade {

    private static final Logger logger = LoggerFactory.getLogger(LeetCodeSubmissionFacade.class);

    private final LeetCodeExecutionService executionService;
    private final LeetCodeRecommendationService recommendationService;
    private final LeetCodeSubmissionRecordDao submissionRecordDao;

    public LeetCodeSubmissionFacade(
            LeetCodeExecutionService executionService,
            @Qualifier("intelligentRecommendationService") LeetCodeRecommendationService recommendationService,
            LeetCodeSubmissionRecordDao submissionRecordDao) {
        this.executionService = executionService;
        this.recommendationService = recommendationService;
        this.submissionRecordDao = submissionRecordDao;
    }

    public Map<String, Object> submitSolution(
            Integer studentId,
            Long problemId,
            String code,
            String language,
            String recommendationRequestId,
            String recommendationSessionId) {
        Map<String, Object> result = executionService.submitSolution(studentId, problemId, code, language);

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
            logger.warn("Failed to persist LeetCode submission record for student={} problem={}", studentId, problemId, e);
        }

        if (Boolean.TRUE.equals(result.get("accepted"))
                && recommendationRequestId != null
                && !recommendationRequestId.isBlank()) {
            recommendationService.recordFeedback(
                    recommendationRequestId,
                    studentId,
                    problemId,
                    "complete",
                    recommendationSessionId
            );
        }
        return result;
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
