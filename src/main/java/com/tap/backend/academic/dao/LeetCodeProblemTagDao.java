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
    List<Long> findProblemIdsByTag(@Param("tagCategory") String tagCategory, @Param("tagName") String tagName);
    
    /**
     * 根据多个标签查找题目ID
     */
    List<Long> findProblemIdsByTags(@Param("tagCategory") String tagCategory, @Param("tagNames") List<String> tagNames);

    /**
     * 跨标签类型根据标签值查找题目ID
     */
    List<Long> findProblemIdsByTagNames(@Param("tagNames") List<String> tagNames);
    
    /**
     * 获取所有标签类型
     */
    List<String> findAllTagCategories();
    
    /**
     * 根据标签类型获取所有标签值
     */
    List<String> findTagNamesByCategory(@Param("tagCategory") String tagCategory);
    
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
