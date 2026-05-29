package com.tap.backend.academic.service.impl;

import com.tap.backend.academic.dao.StudentDao;
import com.tap.backend.academic.dao.UserDao;
import com.tap.backend.academic.entity.Student;
import com.tap.backend.academic.entity.UserEntity;
import com.tap.backend.academic.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {
    
    @Autowired
    private UserDao userDao;

    @Autowired
    private StudentDao studentDao;
    
    @Override
    public UserEntity findByUsername(String username) {
        return userDao.findByUsername(username);
    }
    
    @Override
    public boolean saveUser(UserEntity user) {
        return userDao.saveUser(user) > 0;
    }
    
    @Override
    public UserEntity findById(int id) {
        return userDao.findById(id);
    }
    
    @Override
    public boolean updateUser(UserEntity user) {
        return userDao.updateUser(user) > 0;
    }
    
    @Override
    public boolean deleteUser(int id) {
        return userDao.deleteUser(id) > 0;
    }

    @Override
    public void bindStudentByUsernum(String username, String usernum, String classname) {
        // 尝试通过学号查找已有学生
        Student existing = studentDao.findByStudentId(Integer.parseInt(usernum));
        if (existing != null) {
            // 学生已存在，更新 username 关联
            studentDao.bindUsernameByStudentId(usernum, username);
        } else {
            // 学生不存在，创建新记录
            studentDao.insertStudent(usernum, username, username, classname);
        }
    }
}