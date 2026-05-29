package com.tap.backend.academic.service.impl;

import com.tap.backend.academic.dao.StudentDao;
import com.tap.backend.academic.entity.Student;
import com.tap.backend.academic.service.StudentService;
import org.apache.ibatis.annotations.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StudentServiceImpl implements StudentService {

    private static final Logger logger = LoggerFactory.getLogger(StudentServiceImpl.class);

    @Autowired
    private StudentDao studentDao;

    @Override
    public Integer findStudentIdByUsername(String username) {
        try {
            logger.debug("尝试查找用户名为 {} 的学生ID", username);
            return studentDao.findStudentIdByUsername(username);
        } catch (Exception e) {
            logger.error("查找用户名为 {} 的学生ID时出错: {}", username, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public Student findByUsername(String username) {
        try {
            logger.debug("尝试查找用户名为 {} 的学生", username);
            return studentDao.findByUsername(username);
        } catch (Exception e) {
            logger.error("查找用户名为 {} 的学生时出错: {}", username, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public Student findByStudentId(int studentId) {
        try {
            logger.debug("尝试查找学生ID为 {} 的学生", studentId);
            return studentDao.findByStudentId(studentId);
        } catch (Exception e) {
            logger.error("查找学生ID为 {} 的学生时出错: {}", studentId, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public List<Student> findAllStudents() {
        try {
            logger.debug("尝试查找所有学生");
            return studentDao.findAllStudents();
        } catch (Exception e) {
            logger.error("查找所有学生时出错: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public List<Student> getStudentsByTeacherId(@Param("teacherId") Integer teacherId) {
    	return studentDao.getStudentsByTeacherId(teacherId);
    }

    @Override
    public List<Student> getStudentsByClassName(@Param("className") String className) {
    	return studentDao.getStudentsByClassName(className);
    }
}