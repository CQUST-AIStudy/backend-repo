package com.tap.backend.academic.service;

import com.tap.backend.academic.entity.teacher.Teacher;

public interface TeacherService {
    
    /**
     * 根据用户名查询老师信息
     * @param username 用户名
     * @return 老师信息
     */
    Teacher findByUsername(String username);
    
    /**
     * 根据用户名获取老师ID
     * @param username 用户名
     * @return 老师ID
     */
    Integer findTeacherIdByUsername(String username);
    
    /**
     * 根据老师ID查询老师信息
     * @param teacherId 老师ID
     * @return 老师信息
     */
    Teacher findByTeacherId(int teacherId);
}