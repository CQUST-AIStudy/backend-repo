package com.tap.backend.academic.service.impl;

import com.tap.backend.academic.dao.TeacherDao;
import com.tap.backend.academic.entity.teacher.Teacher;
import com.tap.backend.academic.service.TeacherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TeacherServiceImpl implements TeacherService {
    
    @Autowired
    private TeacherDao teacherDao;
    
    @Override
    public Teacher findByUsername(String username) {
        return teacherDao.findByUsername(username);
    }
    
    @Override
    public Integer findTeacherIdByUsername(String username) {
        return teacherDao.findTeacherIdByUsername(username);
    }
    
    @Override
    public Teacher findByTeacherId(int teacherId) {
        return teacherDao.findByTeacherId(teacherId);
    }
}