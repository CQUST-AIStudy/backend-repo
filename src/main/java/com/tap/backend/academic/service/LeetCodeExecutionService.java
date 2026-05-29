package com.tap.backend.academic.service;

import java.util.Map;

/**
 * LeetCode代码执行服务接口
 */
public interface LeetCodeExecutionService {
    
    /**
     * 运行代码
     * @param problemId 题目ID
     * @param code 代码
     * @param language 编程语言
     * @param testInput 测试输入
     * @return 运行结果
     */
    Map<String, Object> runCode(Long problemId, String code, String language, String testInput);
    
    /**
     * 提交解答
     * @param studentId 学生ID
     * @param problemId 题目ID
     * @param code 代码
     * @param language 编程语言
     * @return 评测结果
     */
    Map<String, Object> submitSolution(Integer studentId, Long problemId, String code, String language);
}