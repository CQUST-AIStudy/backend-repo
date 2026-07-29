package com.tap.backend.academic.dao;

import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

public interface ProfileDao {

    /** 获取学生在每个实验的聚合统计 */
    List<Map<String, Object>> getStudentExperimentStats(@Param("studentId") String studentId);

    /** 获取学生在某实验的错误类型分布 */
    List<Map<String, Object>> getStudentErrorDistribution(
            @Param("studentId") String studentId,
            @Param("experimentId") int experimentId);

    /** 获取班级所有学生的实验聚合统计 */
    List<Map<String, Object>> getClassExperimentStats(
            @Param("classId") Long classId,
            @Param("className") String className);

    /** 获取所有学生列表 */
    List<Map<String, Object>> getAllStudents(
            @Param("classId") Long classId,
            @Param("className") String className);

    /** 获取学生基本信息 */
    Map<String, Object> getStudentInfo(@Param("studentId") String studentId);

    /** 获取学生在某实验的代表性错误题目 */
    List<Map<String, Object>> getStudentWeakQuestions(
            @Param("studentId") String studentId,
            @Param("experimentId") int experimentId);

    /** 获取学生的AI反馈缓存 */
    Map<String, Object> getAiFeedback(@Param("studentId") String studentId);

    /** 保存或更新学生的AI反馈缓存 */
    void saveAiFeedback(@Param("studentId") String studentId,
                        @Param("feedback") String feedback,
                        @Param("profileJson") String profileJson);
}
