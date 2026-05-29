package com.tap.backend.academic.dao;

import com.tap.backend.academic.entity.UserEntity;

public interface UserDao {
    
    /**
     * 根据用户名查找用户
     * @param username 用户名
     * @return 用户实体
     */
    UserEntity findByUsername(String username);
    
    /**
     * 保存用户信息
     * @param user 用户实体
     * @return 影响的行数
     */
    int saveUser(UserEntity user);
    
    /**
     * 根据ID查找用户
     * @param id 用户ID
     * @return 用户实体
     */
    UserEntity findById(int id);
    
    /**
     * 更新用户信息
     * @param user 用户实体
     * @return 影响的行数
     */
    int updateUser(UserEntity user);
    
    /**
     * 删除用户
     * @param id 用户ID
     * @return 影响的行数
     */
    int deleteUser(int id);
}