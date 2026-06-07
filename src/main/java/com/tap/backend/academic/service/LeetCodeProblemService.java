package com.tap.backend.academic.service;

import com.tap.backend.academic.entity.LeetCodeProblem;
import java.util.List;

/**
 * LeetCode题目服务接口
 */
public interface LeetCodeProblemService {
    
    /**
     * 根据ID查找题目
     */
    LeetCodeProblem findById(Long id);
    
    /**
     * 根据题目代码查找题目
     */
    LeetCodeProblem findByProblemCode(String problemCode);
    
    /**
     * 获取所有题目
     */
    List<LeetCodeProblem> findAll();
    
    /**
     * 根据难度查找题目
     */
    List<LeetCodeProblem> findByDifficulty(String difficulty);
    
    /**
     * 保存题目
     */
    void save(LeetCodeProblem problem);
    
    /**
     * 更新题目
     */
    void update(LeetCodeProblem problem);
    
    /**
     * 删除题目
     */
    void deleteById(Long id);

    /**
     * 按关键词、难度搜索题目（含标签匹配）
     * @param keyword 关键词（标题/题号/标签模糊匹配）
     * @param difficulty 难度过滤（Easy/Medium/Hard，null=不限）
     * @param offset 分页偏移
     * @param limit 分页大小
     * @return 匹配的题目列表
     */
    List<LeetCodeProblem> search(String keyword, String difficulty, int offset, int limit);

    /**
     * 统计搜索结果总数
     */
    int countBySearch(String keyword, String difficulty);
}