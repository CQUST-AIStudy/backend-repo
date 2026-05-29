package com.tap.backend.academic.dao;

import com.tap.backend.academic.entity.teacher.Teacher;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TeacherDao {
    
    /**
     * 根据用户名查询老师信息
     * @param username 用户名
     * @return 老师信息
     */
    Teacher findByUsername(@Param("username") String username);
    
    /**
     * 根据用户名获取老师ID
     * @param username 用户名
     * @return 老师ID
     */
    Integer findTeacherIdByUsername(@Param("username") String username);
    
    /**
     * 根据老师ID查询老师信息
     * @param teacherId 老师ID
     * @return 老师信息
     */
    Teacher findByTeacherId(@Param("teacherId") int teacherId);
}