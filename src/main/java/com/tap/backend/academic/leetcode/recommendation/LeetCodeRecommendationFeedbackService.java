package com.tap.backend.academic.leetcode.recommendation;

import com.tap.backend.academic.dao.LeetCodeFeedbackDao;
import com.tap.backend.academic.entity.LeetCodeRecommendFeedback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class LeetCodeRecommendationFeedbackService {

    private static final Logger logger = LoggerFactory.getLogger(LeetCodeRecommendationFeedbackService.class);

    private static final Set<String> VALID_FEEDBACK_ACTIONS = Set.of(
            LeetCodeRecommendFeedback.ACTION_EXPOSURE,
            LeetCodeRecommendFeedback.ACTION_CLICK,
            LeetCodeRecommendFeedback.ACTION_START,
            LeetCodeRecommendFeedback.ACTION_COMPLETE,
            LeetCodeRecommendFeedback.ACTION_SKIP,
            LeetCodeRecommendFeedback.ACTION_DISLIKE
    );

    private final LeetCodeFeedbackDao feedbackDao;

    public LeetCodeRecommendationFeedbackService(LeetCodeFeedbackDao feedbackDao) {
        this.feedbackDao = feedbackDao;
    }

    public RecommendationFeedbackContext buildContext(Integer studentId) {
        RecommendationFeedbackContext context = new RecommendationFeedbackContext();
        if (studentId == null) {
            return context;
        }

        try {
            List<LeetCodeRecommendFeedback> feedbackList = feedbackDao.findByStudentId(studentId, 300);
            if (feedbackList == null || feedbackList.isEmpty()) {
                return context;
            }

            Map<Long, int[]> counters = new HashMap<>();
            for (int i = 0; i < feedbackList.size(); i++) {
                LeetCodeRecommendFeedback feedback = feedbackList.get(i);
                Long problemId = feedback.getProblemId();
                if (problemId == null) {
                    continue;
                }

                String action = normalizeAction(feedback.getAction());
                if (action == null || !VALID_FEEDBACK_ACTIONS.contains(action)) {
                    continue;
                }

                int[] stat = counters.computeIfAbsent(problemId, key -> new int[6]);
                switch (action) {
                    case LeetCodeRecommendFeedback.ACTION_EXPOSURE -> stat[0]++;
                    case LeetCodeRecommendFeedback.ACTION_CLICK -> stat[1]++;
                    case LeetCodeRecommendFeedback.ACTION_START -> stat[2]++;
                    case LeetCodeRecommendFeedback.ACTION_COMPLETE -> stat[3]++;
                    case LeetCodeRecommendFeedback.ACTION_SKIP -> stat[4]++;
                    case LeetCodeRecommendFeedback.ACTION_DISLIKE -> stat[5]++;
                    default -> {
                    }
                }

                double recencyFactor = Math.max(0.30, 1.0 - (i * 0.008));
                context.scoreAdjustments().merge(problemId, getFeedbackScoreDelta(action) * recencyFactor, Double::sum);
            }

            for (Map.Entry<Long, int[]> entry : counters.entrySet()) {
                Long problemId = entry.getKey();
                int[] stat = entry.getValue();
                int exposureCount = stat[0];
                int clickCount = stat[1];
                int startCount = stat[2];
                int completeCount = stat[3];
                int skipCount = stat[4];
                int dislikeCount = stat[5];
                int engagedCount = clickCount + startCount + completeCount;

                if (completeCount > 0) {
                    context.completedProblemIds().add(problemId);
                }
                if (dislikeCount > 0) {
                    context.dislikedProblemIds().add(problemId);
                }

                int idleExposure = Math.max(0, exposureCount - engagedCount);
                if (idleExposure >= 2) {
                    double penalty = -Math.min(0.25, (idleExposure - 1) * 0.05);
                    context.scoreAdjustments().merge(problemId, penalty, Double::sum);
                }

                if (skipCount > 1) {
                    double penalty = -Math.min(0.20, (skipCount - 1) * 0.04);
                    context.scoreAdjustments().merge(problemId, penalty, Double::sum);
                }
            }

            context.scoreAdjustments().replaceAll((k, v) -> clamp(v, -0.55, 0.25));
            return context;
        } catch (Exception e) {
            logger.warn("Failed to build recommendation feedback context. studentId={}, error={}", studentId, e.getMessage());
            return context;
        }
    }

    public boolean recordFeedback(String requestId, Integer studentId, Long problemId, String action, String sessionId) {
        try {
            String normalizedAction = normalizeAction(action);
            if (studentId == null || problemId == null || normalizedAction == null || !VALID_FEEDBACK_ACTIONS.contains(normalizedAction)) {
                logger.warn("Ignore invalid recommendation feedback. requestId={}, studentId={}, problemId={}, action={}",
                        requestId, studentId, problemId, action);
                return false;
            }

            LeetCodeRecommendFeedback feedback = new LeetCodeRecommendFeedback();
            feedback.setRequestId(requestId);
            feedback.setStudentId(studentId);
            feedback.setProblemId(problemId);
            feedback.setSessionId(sessionId);
            feedback.setAction(normalizedAction);
            feedback.setActionAt(LocalDateTime.now());

            boolean success = feedbackDao.insertFeedback(feedback) > 0;
            logger.info("Record recommendation feedback. requestId={}, studentId={}, problemId={}, action={}, success={}",
                    requestId, studentId, problemId, normalizedAction, success);
            return success;
        } catch (Exception e) {
            logger.error("Failed to record recommendation feedback", e);
            return false;
        }
    }

    private String normalizeAction(String action) {
        return action == null ? null : action.toLowerCase(Locale.ROOT).trim();
    }

    private double getFeedbackScoreDelta(String action) {
        return switch (action) {
            case LeetCodeRecommendFeedback.ACTION_EXPOSURE -> -0.01;
            case LeetCodeRecommendFeedback.ACTION_CLICK -> 0.04;
            case LeetCodeRecommendFeedback.ACTION_START -> 0.06;
            case LeetCodeRecommendFeedback.ACTION_COMPLETE -> -0.28;
            case LeetCodeRecommendFeedback.ACTION_SKIP -> -0.12;
            case LeetCodeRecommendFeedback.ACTION_DISLIKE -> -0.35;
            default -> 0.0;
        };
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
