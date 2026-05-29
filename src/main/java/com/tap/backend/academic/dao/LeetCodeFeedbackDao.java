package com.tap.backend.academic.dao;

import com.tap.backend.academic.entity.LeetCodeRecommendFeedback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * LeetCode推荐反馈数据访问接口
 */
@Mapper
public interface LeetCodeFeedbackDao {

    /**
     * 插入反馈记录
     */
    int insertFeedback(LeetCodeRecommendFeedback feedback);

    /**
     * 根据学生ID查询反馈记录
     */
    List<LeetCodeRecommendFeedback> findByStudentId(@Param("studentId") Integer studentId, @Param("limit") Integer limit);

    /**
     * 根据请求ID查询反馈记录
     */
    List<LeetCodeRecommendFeedback> findByRequestId(@Param("requestId") String requestId);

    /**
     * 根据题目ID查询反馈记录
     */
    List<LeetCodeRecommendFeedback> findByProblemId(@Param("problemId") Long problemId, @Param("limit") Integer limit);

    /**
     * 统计学生的行为数据
     */
    List<Object> getStudentActionStats(@Param("studentId") Integer studentId, @Param("days") Integer days);

    /**
     * 统计题目的反馈数据
     */
    List<Object> getProblemFeedbackStats(@Param("problemId") Long problemId);

    /**
     * 删除过期的反馈记录
     */
    int deleteExpiredFeedback(@Param("beforeDate") LocalDateTime beforeDate);
}