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
}