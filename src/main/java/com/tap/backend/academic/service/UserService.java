package com.tap.backend.academic.service;

import com.tap.backend.academic.entity.UserEntity;

public interface UserService {
    
    /**
     * 根据用户名查找用户
     * @param username 用户名
     * @return 用户实体
     */
    UserEntity findByUsername(String username);
    
    /**
     * 保存用户信息
     * @param user 用户实体
     * @return 是否保存成功
     */
    boolean saveUser(UserEntity user);
    
    /**
     * 根据ID查找用户
     * @param id 用户ID
     * @return 用户实体
     */
    UserEntity findById(int id);
    
    /**
     * 更新用户信息
     * @param user 用户实体
     * @return 是否更新成功
     */
    boolean updateUser(UserEntity user);
    
    /**
     * 删除用户
     * @param id 用户ID
     * @return 是否删除成功
     */
    boolean deleteUser(int id);

    /**
     * 绑定学生学号：在 student 表中关联 username
     */
    void bindStudentByUsernum(String username, String usernum, String classname);

    /**
     * 根据学号查询学生真实姓名
     * @param usernum 学号
     * @return 真实姓名，未找到时返回 null
     */
    String getRealNameByUsernum(String usernum);
}