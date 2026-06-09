package com.tap.backend.academic.entity;

public class UserEntity {
    private int id;
    private String username;
    private String password;
    private String email;
    private String role;
    private String usernum;    // 学号或工号
    private String classname;  // 班级名称
    private String phone;      // 联系电话
    private String department; // 部门
    
    public UserEntity() {
    }
    
    public UserEntity(String username, String password) {
        this.username = username;
        this.password = password;
    }
    
    public int getId() {
        return id;
    }
    
    public void setId(int id) {
        this.id = id;
    }
    
    public String getUsername() {
        return username;
    }
    
    public void setUsername(String username) {
        this.username = username;
    }
    
    public String getPassword() {
        return password;
    }
    
    public void setPassword(String password) {
        this.password = password;
    }
    
    public String getEmail() {
        return email;
    }
    
    public void setEmail(String email) {
        this.email = email;
    }
    
    public String getRole() {
        return role;
    }
    
    public void setRole(String role) {
        this.role = role;
    }
    
    public String getUsernum() {
        return usernum;
    }
    
    public void setUsernum(String usernum) {
        this.usernum = usernum;
    }
    
    public String getClassname() {
        return classname;
    }

    public void setClassname(String classname) {
        this.classname = classname;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }
}