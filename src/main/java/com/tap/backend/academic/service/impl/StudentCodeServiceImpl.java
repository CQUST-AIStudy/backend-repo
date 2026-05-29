package com.tap.backend.academic.service.impl;

import com.tap.backend.academic.dao.StudentDao;
import com.tap.backend.academic.entity.StudentCode;
import com.tap.backend.academic.service.StudentCodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StudentCodeServiceImpl implements StudentCodeService {

    private static final Logger logger = LoggerFactory.getLogger(StudentCodeServiceImpl.class);

    @Autowired
    private StudentDao studentDao;

    @Override
    public List<StudentCode> findCodeByStudentId(int studentId) {
        try {
            logger.debug("尝试查找学生ID为 {} 的所有代码", studentId);
            return studentDao.findCodeByStudentId(studentId);
        } catch (Exception e) {
            logger.error("查找学生ID为 {} 的代码时出错: {}", studentId, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public StudentCode findCodeByStudentIdAndExperimentId(int studentId, int experimentId) {
        try {
            logger.debug("尝试查找学生ID为 {} 的实验ID为 {} 的代码", studentId, experimentId);
            return studentDao.findCodeByStudentIdAndExperimentId(studentId, experimentId);
        } catch (Exception e) {
            logger.error("查找学生ID为 {} 实验ID为 {} 的代码时出错: {}", studentId, experimentId, e.getMessage(), e);
            return null;
        }
    }
}