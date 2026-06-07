package com.tap.backend.academic.dao;

import com.tap.backend.academic.entity.LeetCodeProblem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * LeetCode题目数据访问接口
 */
@Mapper
public interface LeetCodeProblemDao {
    
    /**
     * 根据ID查找题目
     */
    LeetCodeProblem findById(@Param("id") Long id);
    
    /**
     * 根据题目代码查找题目
     */
    LeetCodeProblem findByProblemCode(@Param("problemCode") String problemCode);
    
    /**
     * 获取所有题目
     */
    List<LeetCodeProblem> findAll();
    
    /**
     * 根据难度查找题目
     */
    List<LeetCodeProblem> findByDifficulty(@Param("difficulty") String difficulty);

    /**
     * 批量根据ID查找题目
     */
    List<LeetCodeProblem> findByIds(@Param("ids") List<Long> ids);
    
    /**
     * 插入题目
     */
    void insert(LeetCodeProblem problem);
    
    /**
     * 插入或更新题目
     */
    void insertOrUpdate(LeetCodeProblem problem);
    
    /**
     * 更新题目
     */
    void update(LeetCodeProblem problem);
    
    /**
     * 删除题目
     */
    void deleteById(@Param("id") Long id);
    
    /**
     * 分页查询题目
     */
    List<LeetCodeProblem> findByPage(@Param("offset") int offset, @Param("limit") int limit);
    
    /**
     * 统计题目总数
     */
    int count();

    /**
     * 按关键词、难度搜索题目（含标签匹配）
     */
    List<LeetCodeProblem> search(@Param("keyword") String keyword,
                                  @Param("difficulty") String difficulty,
                                  @Param("limit") int limit,
                                  @Param("offset") int offset);

    /**
     * 统计搜索结果总数
     */
    int countBySearch(@Param("keyword") String keyword,
                      @Param("difficulty") String difficulty);
}
