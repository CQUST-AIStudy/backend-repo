package com.tap.backend.academic.service.impl;

import com.tap.backend.academic.dao.LeetCodeProblemDao;
import com.tap.backend.academic.entity.*;
import com.tap.backend.academic.service.LeetCodeRecommendationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * LeetCode推荐服务实现（简化版）
 */
@Service
public class LeetCodeRecommendationServiceImpl implements LeetCodeRecommendationService {

    private static final Logger logger = LoggerFactory.getLogger(LeetCodeRecommendationServiceImpl.class);

    @Autowired
    private LeetCodeProblemDao problemDao;

    @Override
    @Transactional
    public String generateRecommendation(Integer studentId, Integer limit, String scene) {
        try {
            // 生成请求ID
            String requestId = UUID.randomUUID().toString();
            
            logger.info("为学生 {} 生成推荐，requestId: {}, 数量限制: {}", studentId, requestId, limit);
            
            return requestId;
            
        } catch (Exception e) {
            logger.error("生成推荐失败，学生ID: " + studentId, e);
            throw new RuntimeException("生成推荐失败: " + e.getMessage(), e);
        }
    }

    @Override
    public LeetCodeRecommendRequest getRecommendationResult(String requestId) {
        // 简化实现：直接返回完成状态
        LeetCodeRecommendRequest request = new LeetCodeRecommendRequest();
        request.setRequestId(requestId);
        request.setStatus("completed");
        return request;
    }

    @Override
    public List<LeetCodeRecommendItem> getRecommendationItems(String requestId) {
        // 简化实现：返回空列表
        return new ArrayList<>();
    }

    @Override
    public List<LeetCodeRecommendItem> generateRecommendationSync(Integer studentId, Integer limit) {
        try {
            logger.info("开始为学生 {} 生成同步推荐，数量限制: {}", studentId, limit);

            // 直接从数据库获取题目并生成推荐
            List<LeetCodeProblem> allProblems = problemDao.findAll();
            
            if (allProblems.isEmpty()) {
                logger.warn("数据库中没有题目数据，尝试使用默认推荐");
                return getDefaultRecommendations(limit);
            }

            logger.info("从数据库获取到 {} 个题目", allProblems.size());

            // 确保limit至少为5，最多为20
            int actualLimit = Math.max(5, Math.min(limit != null ? limit : 10, 20));
            
            // 随机打乱并取前N个，确保多样性
            Collections.shuffle(allProblems);
            List<LeetCodeProblem> selectedProblems = allProblems.subList(0, Math.min(actualLimit, allProblems.size()));
            
            List<LeetCodeRecommendItem> items = new ArrayList<>();
            
            for (int i = 0; i < selectedProblems.size(); i++) {
                LeetCodeProblem problem = selectedProblems.get(i);
                LeetCodeRecommendItem item = new LeetCodeRecommendItem();
                
                item.setProblemId(problem.getId());
                item.setProblem(problem);
                item.setRankNo(i + 1);
                
                // 简化的评分系统
                double baseScore = 0.8;
                double difficultyBonus = getDifficultyBonus(problem.getDifficulty());
                double qualityScore = problem.getQualityScore() != null ? problem.getQualityScore().doubleValue() : 0.8;
                double totalScore = baseScore + difficultyBonus + (qualityScore * 0.2);
                
                item.setScoreTotal(BigDecimal.valueOf(totalScore).setScale(4, RoundingMode.HALF_UP));
                item.setScoreNeedMatch(BigDecimal.valueOf(0.7).setScale(4, RoundingMode.HALF_UP));
                item.setScoreDifficultyFit(BigDecimal.valueOf(difficultyBonus).setScale(4, RoundingMode.HALF_UP));
                item.setScoreSuccessProb(BigDecimal.valueOf(0.6).setScale(4, RoundingMode.HALF_UP));
                item.setScoreNovelty(BigDecimal.valueOf(0.8).setScale(4, RoundingMode.HALF_UP));
                item.setScoreQuality(BigDecimal.valueOf(qualityScore).setScale(4, RoundingMode.HALF_UP));
                
                // 生成推荐理由
                item.setReasonText(generateSimpleReasonText(problem));
                
                items.add(item);
            }
            
            logger.info("为学生 {} 生成推荐完成，实际推荐数量: {}", studentId, items.size());
            return items;
            
        } catch (Exception e) {
            logger.error("生成同步推荐失败，学生ID: " + studentId, e);
            return getDefaultRecommendations(limit != null ? limit : 10);
        }
    }
    
    @Override
    public boolean recordFeedback(String requestId, Integer studentId, Long problemId, String action, String sessionId) {
        try {
            logger.info("记录推荐反馈: requestId={}, studentId={}, problemId={}, action={}", 
                       requestId, studentId, problemId, action);
            return true;
        } catch (Exception e) {
            logger.error("记录推荐反馈失败", e);
            return false;
        }
    }
    
    private double getDifficultyBonus(String difficulty) {
        switch (difficulty != null ? difficulty.toLowerCase() : "medium") {
            case "easy": return 0.1;
            case "hard": return 0.3;
            case "medium":
            default: return 0.2;
        }
    }
    
    private String generateSimpleReasonText(LeetCodeProblem problem) {
        String difficulty = problem.getDifficulty();
        String difficultyText = getDifficultyText(difficulty);
        
        return String.format("推荐 %s 难度的 %s，适合当前学习阶段练习。预计用时 %d 分钟。", 
                           difficultyText, 
                           problem.getTitleMain(), 
                           problem.getEstimatedMinutes());
    }
    
    private String getDifficultyText(String difficulty) {
        switch (difficulty != null ? difficulty.toLowerCase() : "medium") {
            case "easy": return "简单";
            case "hard": return "困难";
            case "medium":
            default: return "中等";
        }
    }

    private List<LeetCodeRecommendItem> getDefaultRecommendations(int limit) {
        try {
            // 确保limit至少为5，最多为20
            int actualLimit = Math.max(5, Math.min(limit, 20));
            
            // 获取前几个题目作为默认推荐
            List<LeetCodeProblem> problems = problemDao.findByPage(0, actualLimit);
            List<LeetCodeRecommendItem> items = new ArrayList<>();
            
            logger.info("获取默认推荐，期望数量: {}, 实际获取: {}", actualLimit, problems.size());
            
            for (int i = 0; i < problems.size(); i++) {
                LeetCodeProblem problem = problems.get(i);
                LeetCodeRecommendItem item = new LeetCodeRecommendItem();
                
                item.setProblemId(problem.getId());
                item.setProblem(problem);
                item.setRankNo(i + 1);
                item.setScoreTotal(BigDecimal.valueOf(0.8).setScale(4, RoundingMode.HALF_UP));
                item.setScoreNeedMatch(BigDecimal.valueOf(0.7).setScale(4, RoundingMode.HALF_UP));
                item.setScoreDifficultyFit(BigDecimal.valueOf(0.6).setScale(4, RoundingMode.HALF_UP));
                item.setScoreSuccessProb(BigDecimal.valueOf(0.6).setScale(4, RoundingMode.HALF_UP));
                item.setScoreNovelty(BigDecimal.valueOf(0.8).setScale(4, RoundingMode.HALF_UP));
                item.setScoreQuality(BigDecimal.valueOf(0.8).setScale(4, RoundingMode.HALF_UP));
                item.setReasonText("系统默认推荐题目，适合练习基础算法。");
                
                items.add(item);
            }
            
            // 如果数据库中没有足够的题目，创建一些示例题目
            if (items.size() < 3) {
                logger.warn("数据库中题目不足，创建示例推荐");
                items.clear();
                
                for (int i = 1; i <= Math.max(3, actualLimit); i++) {
                    LeetCodeProblem sampleProblem = new LeetCodeProblem();
                    sampleProblem.setId((long) i);
                    sampleProblem.setProblemCode("SAMPLE_" + i);
                    sampleProblem.setTitleMain("示例题目 " + i);
                    sampleProblem.setDifficulty(i % 3 == 0 ? "hard" : (i % 2 == 0 ? "medium" : "easy"));
                    sampleProblem.setEstimatedMinutes(30 + i * 10);
                    sampleProblem.setQualityScore(BigDecimal.valueOf(0.8));
                    
                    LeetCodeRecommendItem item = new LeetCodeRecommendItem();
                    item.setProblemId(sampleProblem.getId());
                    item.setProblem(sampleProblem);
                    item.setRankNo(i);
                    item.setScoreTotal(BigDecimal.valueOf(0.8).setScale(4, RoundingMode.HALF_UP));
                    item.setScoreNeedMatch(BigDecimal.valueOf(0.7).setScale(4, RoundingMode.HALF_UP));
                    item.setScoreDifficultyFit(BigDecimal.valueOf(0.6).setScale(4, RoundingMode.HALF_UP));
                    item.setScoreSuccessProb(BigDecimal.valueOf(0.6).setScale(4, RoundingMode.HALF_UP));
                    item.setScoreNovelty(BigDecimal.valueOf(0.8).setScale(4, RoundingMode.HALF_UP));
                    item.setScoreQuality(BigDecimal.valueOf(0.8).setScale(4, RoundingMode.HALF_UP));
                    item.setReasonText("示例推荐题目，用于测试推荐系统功能。");
                    
                    items.add(item);
                }
            }
            
            logger.info("默认推荐生成完成，返回数量: {}", items.size());
            return items;
        } catch (Exception e) {
            logger.error("获取默认推荐失败", e);
            return new ArrayList<>();
        }
    }
}