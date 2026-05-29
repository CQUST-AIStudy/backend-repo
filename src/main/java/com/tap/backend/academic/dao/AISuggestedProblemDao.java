package com.tap.backend.academic.dao;

import com.tap.backend.academic.entity.AISuggestedProblem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AISuggestedProblemDao {
    /**
     * 根据学生ID和实验ID获取推荐练习
     * @param studentId 学生ID
     * @param experimentId 实验ID
     * @return 推荐练习对象
     */
    AISuggestedProblem findByStudentIdAndExperimentId(@Param("studentId") int studentId, @Param("experimentId") int experimentId);
    
    /**
     * 获取特定学生的所有推荐练习
     * @param studentId 学生ID
     * @return 推荐练习列表
     */
    List<AISuggestedProblem> findByStudentId(@Param("studentId") int studentId);

//    /**
//     * 保存推荐练习
//     * @param problem 推荐练习对象
//     * @return 影响的行数
//     */
//    int saveSuggestedProblem(AISuggestedProblem problem);
} 