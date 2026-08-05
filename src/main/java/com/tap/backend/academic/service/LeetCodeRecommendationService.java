package com.tap.backend.academic.service;

import com.tap.backend.academic.entity.LeetCodeRecommendRequest;
import com.tap.backend.academic.entity.LeetCodeRecommendItem;

import java.util.List;

/**
 * LeetCode推荐服务接口
 */
public interface LeetCodeRecommendationService {

    /**
     * 生成推荐请求
     * @param studentId 学生ID
     * @param limit 推荐数量限制
     * @param scene 推荐场景
     * @return 推荐请求ID
     */
    String generateRecommendation(Integer studentId, Integer limit, String scene);

    /**
     * 查询推荐结果
     * @param requestId 推荐请求ID
     * @return 推荐结果
     */
    LeetCodeRecommendRequest getRecommendationResult(String requestId);

    /**
     * 查询推荐题目列表
     * @param requestId 推荐请求ID
     * @return 推荐题目列表
     */
    List<LeetCodeRecommendItem> getRecommendationItems(String requestId);

    /**
     * 同步生成推荐（用于兼容旧接口）
     * @param studentId 学生ID
     * @param limit 推荐数量限制
     * @param scene 推荐场景
     * @return 推荐题目列表
     */
    List<LeetCodeRecommendItem> generateRecommendationSync(Integer studentId, Integer limit, String scene);

    /**
     * 记录推荐反馈
     * @param requestId 推荐请求ID
     * @param studentId 学生ID
     * @param problemId 题目ID
     * @param action 行为类型
     * @param sessionId 会话ID
     * @return 是否成功
     */
    boolean recordFeedback(String requestId, Integer studentId, Long problemId, String action, String sessionId);
}
