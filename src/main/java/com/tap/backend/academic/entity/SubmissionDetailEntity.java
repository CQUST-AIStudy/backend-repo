package com.tap.backend.academic.entity;

public class SubmissionDetailEntity {
    private   int student_id;
    private  String class_name;
    private  String date;
    private  String code;
    private  String name;

    @Override
    public String toString() {
        return "SubmissionDetailEntity{" +
                "student_id=" + student_id +
                ", class_name='" + class_name + '\'' +
                ", date='" + date + '\'' +
                ", code='" + code + '\'' +
                ", name='" + name + '\'' +
                '}';
    }

    public SubmissionDetailEntity(int student_id, String class_name, String date, String code, String name) {
        this.student_id = student_id;
        this.class_name = class_name;
        this.date = date;
        this.code = code;
        this.name = name;
    }

    public int getStudent_id() {
        return student_id;
    }

    public void setStudent_id(int student_id) {
        this.student_id = student_id;
    }

    public String getClass_name() {
        return class_name;
    }

    public void setClass_name(String class_name) {
        this.class_name = class_name;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
