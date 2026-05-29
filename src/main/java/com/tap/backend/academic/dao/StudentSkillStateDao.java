package com.tap.backend.academic.dao;

import com.tap.backend.academic.entity.StudentSkillState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 学生技能状态数据访问接口
 */
@Mapper
public interface StudentSkillStateDao {

    /**
     * 根据学生ID和标签名查询技能状态
     */
    StudentSkillState findByStudentIdAndTag(@Param("studentId") Integer studentId, @Param("tagName") String tagName);

    /**
     * 根据学生ID查询所有技能状态
     */
    List<StudentSkillState> findByStudentId(@Param("studentId") Integer studentId);

    /**
     * 查询学生的弱项技能（掌握度低的技能）
     */
    List<StudentSkillState> findWeakSkills(@Param("studentId") Integer studentId, @Param("limit") Integer limit);

    /**
     * 查询学生的强项技能（掌握度高的技能）
     */
    List<StudentSkillState> findStrongSkills(@Param("studentId") Integer studentId, @Param("limit") Integer limit);

    /**
     * 插入或更新技能状态（原子操作）
     */
    int insertOrUpdate(StudentSkillState skillState);

    /**
     * 批量插入或更新技能状态
     */
    int batchInsertOrUpdate(@Param("skillStates") List<StudentSkillState> skillStates);

    /**
     * 更新技能状态
     */
    int update(StudentSkillState skillState);

    /**
     * 删除技能状态
     */
    int deleteByStudentIdAndTag(@Param("studentId") Integer studentId, @Param("tagName") String tagName);

    /**
     * 删除学生的所有技能状态
     */
    int deleteByStudentId(@Param("studentId") Integer studentId);

    /**
     * 统计学生的技能数量
     */
    int countByStudentId(@Param("studentId") Integer studentId);

    /**
     * 查询需要更新遗忘度的技能（长时间未练习）
     */
    List<StudentSkillState> findSkillsNeedForgettingUpdate(@Param("daysSinceLastPractice") Integer daysSinceLastPractice);
}