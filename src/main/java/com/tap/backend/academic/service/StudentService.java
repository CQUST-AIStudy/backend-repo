package com.tap.backend.academic.service;

import com.tap.backend.academic.entity.Student;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface StudentService {
    
    /**
     * 根据用户名查找学生ID
     * @param username 用户名
     * @return 学生ID，如果未找到返回null
     */
    Integer findStudentIdByUsername(String username);
    
    /**
     * 根据用户名查询学生信息
     * @param username 用户名
     * @return 学生实体
     */
    Student findByUsername(String username);
    
    /**
     * 根据学生ID查询学生信息
     * @param studentId 学生ID
     * @return 学生实体
     */
    Student findByStudentId(int studentId);
    
    /**
     * 查询所有学生
     * @return 学生列表
     */
    List<Student> findAllStudents();


    List<Student> getStudentsByTeacherId(@Param("teacherId") Integer teacherId);


    List<Student> getStudentsByClassName(@Param("className") String className);
}