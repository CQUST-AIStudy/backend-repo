package com.tap.backend.academic.service;

import com.tap.backend.academic.entity.AISuggestedProblem;
import java.util.List;
import java.util.Map;

public interface AISuggestedProblemService {
    /**
     * 根据学生ID和实验ID获取推荐练习
     * @param studentId 学生ID
     * @param experimentId 实验ID
     * @return 推荐练习对象
     */
    AISuggestedProblem findByStudentIdAndExperimentId(int studentId, int experimentId);
    
    /**
     * 获取特定学生的所有推荐练习
     * @param studentId 学生ID
     * @return 推荐练习列表
     */
    List<AISuggestedProblem> findByStudentId(int studentId);
    
    /**
     * 解析推荐练习内容为结构化的推荐列表
     * @param content Markdown格式的内容
     * @return 推荐练习列表
     */
    List<Map<String, Object>> parseRecommendedPractices(String content);
    
//    /**
//     * 保存推荐练习
//     * @param studentId 学生ID
//     * @param studentName 学生姓名
//     * @param experimentId 实验ID
//     * @param content 内容
//     * @return 是否成功
//     */
//    boolean saveSuggestedProblem(int studentId, String studentName, int experimentId, String content);
} 