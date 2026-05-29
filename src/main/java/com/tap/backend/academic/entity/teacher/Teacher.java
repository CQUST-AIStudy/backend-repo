package com.tap.backend.academic.entity.teacher;

import java.io.Serializable;

public class Teacher implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Integer teacher_id;
    private String teacher_name;
    private String username;
    private String classroom;
    
    public Teacher() {
    }
    
    public Teacher(Integer teacher_id, String teacher_name, String username, String classroom) {
        this.teacher_id = teacher_id;
        this.teacher_name = teacher_name;
        this.username = username;
        this.classroom = classroom;
    }
    
    public Integer getTeacher_id() {
        return teacher_id;
    }
    
    public void setTeacher_id(Integer teacher_id) {
        this.teacher_id = teacher_id;
    }
    
    public String getTeacher_name() {
        return teacher_name;
    }
    
    public void setTeacher_name(String teacher_name) {
        this.teacher_name = teacher_name;
    }
    
    public String getUsername() {
        return username;
    }
    
    public void setUsername(String username) {
        this.username = username;
    }
    
    public String getClassroom() {
        return classroom;
    }
    
    public void setClassroom(String classroom) {
        this.classroom = classroom;
    }
    
    @Override
    public String toString() {
        return "Teacher{" +
                "teacher_id=" + teacher_id +
                ", teacher_name='" + teacher_name + '\'' +
                ", username='" + username + '\'' +
                ", classroom='" + classroom + '\'' +
                '}';
    }
}