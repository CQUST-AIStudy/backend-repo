package com.tap.backend.academic.dao;

import com.tap.backend.academic.entity.LeetCodeRecommendRequest;
import com.tap.backend.academic.entity.LeetCodeRecommendItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * LeetCode推荐数据访问接口
 */
@Mapper
public interface LeetCodeRecommendDao {

    // ========== 推荐请求相关 ==========
    
    /**
     * 根据requestId查询推荐请求
     */
    LeetCodeRecommendRequest findRequestById(@Param("requestId") String requestId);

    /**
     * 根据学生ID查询最近的推荐请求
     */
    List<LeetCodeRecommendRequest> findRequestsByStudentId(@Param("studentId") Integer studentId, @Param("limit") Integer limit);

    /**
     * 插入推荐请求
     */
    int insertRequest(LeetCodeRecommendRequest request);

    /**
     * 更新推荐请求状态
     */
    int updateRequestStatus(@Param("requestId") String requestId, @Param("status") String status, @Param("errorMessage") String errorMessage);

    /**
     * 删除推荐请求
     */
    int deleteRequest(@Param("requestId") String requestId);

    // ========== 推荐结果相关 ==========
    
    /**
     * 根据requestId查询推荐结果
     */
    List<LeetCodeRecommendItem> findItemsByRequestId(@Param("requestId") String requestId);

    /**
     * 根据requestId和排名查询推荐结果
     */
    LeetCodeRecommendItem findItemByRequestIdAndRank(@Param("requestId") String requestId, @Param("rankNo") Integer rankNo);

    /**
     * 插入推荐结果
     */
    int insertItem(LeetCodeRecommendItem item);

    /**
     * 批量插入推荐结果
     */
    int batchInsertItems(@Param("items") List<LeetCodeRecommendItem> items);

    /**
     * 删除推荐结果
     */
    int deleteItemsByRequestId(@Param("requestId") String requestId);

    /**
     * 查询学生最近推荐过的题目ID（用于去重）
     */
    List<Long> findRecentRecommendedProblemIds(@Param("studentId") Integer studentId, @Param("days") Integer days);

    /**
     * 统计推荐结果数量
     */
    int countItemsByRequestId(@Param("requestId") String requestId);
}