package com.tap.backend.academic.entity;

import java.util.Date;

public class Student {
    private long student_id;
    private String username;
    private String password;
    private String name;
    private String class_name;
    private Date createdAt;

    public Student() {
    }

    public Student(long student_id, String username, String password, String name, String class_name) {
        this.student_id = student_id;
        this.username = username;
        this.password = password;
        this.name = name;
        this.class_name = class_name;
    }

    // Getters and setters
    public long getStudent_id() {
        return student_id;
    }

    public void setStudent_id(long student_id) {
        this.student_id = student_id;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getClass_name() {
        return class_name;
    }

    public void setClass_name(String class_name) {
        this.class_name = class_name;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "Student{" +
                "student_id=" + student_id +
                ", username='" + username + '\'' +
                ", name='" + name + '\'' +
                ", class_name='" + class_name + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}
