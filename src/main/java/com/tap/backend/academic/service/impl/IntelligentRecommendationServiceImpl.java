package com.tap.backend.academic.service.impl;

import com.tap.backend.academic.dao.StudentSkillStateDao;
import com.tap.backend.academic.entity.LeetCodeProblem;
import com.tap.backend.academic.entity.LeetCodeRecommendItem;
import com.tap.backend.academic.entity.LeetCodeRecommendRequest;
import com.tap.backend.academic.entity.StudentSkillState;
import com.tap.backend.academic.leetcode.recommendation.LeetCodeRecommendationCandidateService;
import com.tap.backend.academic.leetcode.recommendation.LeetCodeRecommendationFeedbackService;
import com.tap.backend.academic.leetcode.recommendation.LeetCodeRecommendationRankingService;
import com.tap.backend.academic.leetcode.recommendation.LeetCodeRecommendationRequestStore;
import com.tap.backend.academic.leetcode.recommendation.RecommendationFeedbackContext;
import com.tap.backend.academic.service.LeetCodeRecommendationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service("intelligentRecommendationService")
public class IntelligentRecommendationServiceImpl implements LeetCodeRecommendationService {

    private static final Logger logger = LoggerFactory.getLogger(IntelligentRecommendationServiceImpl.class);

    private final StudentSkillStateDao skillStateDao;
    private final LeetCodeRecommendationRequestStore requestStore;
    private final LeetCodeRecommendationCandidateService candidateService;
    private final LeetCodeRecommendationRankingService rankingService;
    private final LeetCodeRecommendationFeedbackService feedbackService;

    public IntelligentRecommendationServiceImpl(
            StudentSkillStateDao skillStateDao,
            LeetCodeRecommendationRequestStore requestStore,
            LeetCodeRecommendationCandidateService candidateService,
            LeetCodeRecommendationRankingService rankingService,
            LeetCodeRecommendationFeedbackService feedbackService) {
        this.skillStateDao = skillStateDao;
        this.requestStore = requestStore;
        this.candidateService = candidateService;
        this.rankingService = rankingService;
        this.feedbackService = feedbackService;
    }

    @Override
    public String generateRecommendation(Integer studentId, Integer limit, String scene) {
        if (studentId == null) {
            throw new IllegalArgumentException("studentId cannot be null");
        }

        int actualLimit = normalizeLimit(limit);
        String actualScene = (scene == null || scene.isBlank()) ? "default" : scene;
        LeetCodeRecommendRequest request = requestStore.createPendingRequest(studentId, actualScene, actualLimit);
        logger.info("Generate recommendation requestId={} studentId={} limit={} scene={}",
                request.getRequestId(), studentId, actualLimit, actualScene);

        try {
            List<LeetCodeRecommendItem> items = generateRecommendationSync(studentId, actualLimit);
            if (items.isEmpty()) {
                requestStore.failRequest(request.getRequestId(), "no recommendation generated");
            } else {
                requestStore.completeRequest(request.getRequestId(), items);
            }
        } catch (Exception e) {
            requestStore.failRequest(request.getRequestId(), e.getMessage());
            logger.error("Failed to generate recommendation requestId={} studentId={}",
                    request.getRequestId(), studentId, e);
        }

        return request.getRequestId();
    }

    @Override
    public LeetCodeRecommendRequest getRecommendationResult(String requestId) {
        return requestStore.getRequest(requestId);
    }

    @Override
    public List<LeetCodeRecommendItem> getRecommendationItems(String requestId) {
        return requestStore.getItems(requestId);
    }

    @Override
    public List<LeetCodeRecommendItem> generateRecommendationSync(Integer studentId, Integer limit) {
        int actualLimit = normalizeLimit(limit);
        try {
            logger.info("Generate sync recommendation studentId={} limit={}", studentId, actualLimit);

            Map<String, StudentSkillState> skillProfile = getStudentSkillProfile(studentId);
            RecommendationFeedbackContext feedbackContext = feedbackService.buildContext(studentId);
            Map<Long, LeetCodeProblem> candidates = candidateService.collectCandidates(skillProfile, feedbackContext, actualLimit);

            if (candidates.isEmpty()) {
                logger.warn("Recommendation candidates empty, use fallback strategy. studentId={}", studentId);
                return rankingService.fallbackRecommendations(studentId, actualLimit);
            }

            List<LeetCodeRecommendItem> items = rankingService.rankRecommendations(
                    new ArrayList<>(candidates.values()),
                    skillProfile,
                    studentId,
                    feedbackContext.scoreAdjustments(),
                    actualLimit
            );

            if (items.isEmpty()) {
                return rankingService.fallbackRecommendations(studentId, actualLimit);
            }

            logger.info("Generate recommendation completed studentId={} itemCount={}", studentId, items.size());
            return items;
        } catch (Exception e) {
            logger.error("Failed to generate recommendation studentId={}", studentId, e);
            return rankingService.fallbackRecommendations(studentId, actualLimit);
        }
    }

    @Override
    public boolean recordFeedback(String requestId, Integer studentId, Long problemId, String action, String sessionId) {
        return feedbackService.recordFeedback(requestId, studentId, problemId, action, sessionId);
    }

    private Map<String, StudentSkillState> getStudentSkillProfile(Integer studentId) {
        List<StudentSkillState> skillStates = skillStateDao.findByStudentId(studentId);
        if (skillStates == null || skillStates.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return skillStates.stream()
                .filter(state -> state.getTagName() != null)
                .collect(Collectors.toMap(
                        StudentSkillState::getTagName,
                        state -> state,
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ));
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return 20;
        }
        return Math.max(1, Math.min(limit, 50));
    }
}
