package com.tap.backend.academic.dao;

import com.tap.backend.academic.entity.Student;
import com.tap.backend.academic.entity.StudentCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StudentDao {
    
    /**
     * 根据 user 表中的 username 查询对应的 student_id
     * @param username 用户名
     * @return 学生 ID
     */
    Integer findStudentIdByUsername(@Param("username") String username);
    
    /**
     * 根据用户名查询学生信息
     * @param username 用户名
     * @return 学生实体
     */
    Student findByUsername(@Param("username") String username);
    
    /**
     * 根据学生ID查询学生信息
     * @param studentId 学生ID
     * @return 学生实体
     */
    Student findByStudentId(@Param("studentId") int studentId);
    
    /**
     * 查询所有学生
     * @return 学生列表
     */
    List<Student> findAllStudents();
    
    /**
     * 根据学生ID查询该学生所有代码
     * @param studentId 学生ID
     * @return 学生代码列表
     */
    List<StudentCode> findCodeByStudentId(@Param("studentId") int studentId);
    
    /**
     * 根据学生ID和实验ID查询特定代码
     * @param studentId 学生ID
     * @param experimentId 实验ID
     * @return 学生代码
     */
    StudentCode findCodeByStudentIdAndExperimentId(@Param("studentId") int studentId, @Param("experimentId") int experimentId);
    
    /**
     * 根据老师用户名获取对应班级的学生人数
     * @param teacherUsername 老师用户名
     * @return 学生人数
     */
    Integer getStudentCountByTeacher(@Param("teacherUsername") String teacherUsername);
    
    /**
     * 根据班级名称查询学生数量
     * @param className 班级名称
     * @return 学生数量
     */
    Integer getStudentCountByClassName(@Param("className") String className);
    
    /**
     * 根据老师ID查询该老师所教班级的学生数量
     * @param teacherId 老师ID
     * @return 学生数量
     */
    Integer getStudentCountByTeacherId(@Param("teacherId") Integer teacherId);
    
    /**
     * 根据老师ID获取该老师班级所有学生的ID
     * @param teacherId 老师ID
     * @return 学生ID列表
     */
    List<Long> getStudentIdsByTeacherId(@Param("teacherId") Integer teacherId);

    /**
     * 根据老师ID获取该老师班级所有学生的信息
     * @param teacherId 老师ID
     * @return 学生列表
     */
    List<Student> getStudentsByTeacherId(@Param("teacherId") Integer teacherId);


    List<Student> getStudentsByClassName(@Param("className") String className);

    /**
     * 通过学号绑定用户名（更新 student 表的 username 字段）
     */
    int bindUsernameByStudentId(@Param("studentId") String studentId, @Param("username") String username);

    /**
     * 插入新学生记录
     */
    int insertStudent(@Param("studentId") String studentId, @Param("username") String username,
                      @Param("name") String name, @Param("className") String className);
}
