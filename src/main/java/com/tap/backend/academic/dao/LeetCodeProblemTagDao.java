package com.tap.backend.academic.dao;

import com.tap.backend.academic.entity.LeetCodeProblemTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * LeetCode题目标签数据访问接口
 */
@Mapper
public interface LeetCodeProblemTagDao {
    
    /**
     * 根据题目ID查找标签
     */
    List<LeetCodeProblemTag> findByProblemId(@Param("problemId") Long problemId);
    
    /**
     * 根据标签类型和值查找题目ID
     */
    List<Long> findProblemIdsByTag(@Param("tagType") String tagType, @Param("tagValue") String tagValue);
    
    /**
     * 根据多个标签查找题目ID
     */
    List<Long> findProblemIdsByTags(@Param("tagType") String tagType, @Param("tagValues") List<String> tagValues);

    /**
     * 跨标签类型根据标签值查找题目ID
     */
    List<Long> findProblemIdsByTagValues(@Param("tagValues") List<String> tagValues);
    
    /**
     * 获取所有标签类型
     */
    List<String> findAllTagTypes();
    
    /**
     * 根据标签类型获取所有标签值
     */
    List<String> findTagValuesByType(@Param("tagType") String tagType);
    
    /**
     * 插入标签
     */
    void insert(LeetCodeProblemTag tag);
    
    /**
     * 批量插入标签
     */
    void batchInsert(@Param("tags") List<LeetCodeProblemTag> tags);
    
    /**
     * 删除题目的所有标签
     */
    void deleteByProblemId(@Param("problemId") Long problemId);
}
