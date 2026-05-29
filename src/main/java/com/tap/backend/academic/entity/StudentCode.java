package com.tap.backend.academic.entity;

import java.io.Serializable;

public class StudentCode implements Serializable {
    private int experiment_id;
    private String experiment_name;
    private int student_id;
    private String student_name;
    private String code;

    public StudentCode() {
    }

    public StudentCode(int experiment_id, String experiment_name, int student_id, String student_name, String code) {
        this.experiment_id = experiment_id;
        this.experiment_name = experiment_name;
        this.student_id = student_id;
        this.student_name = student_name;
        this.code = code;
    }

    // Getters and setters
    public int getExperiment_id() {
        return experiment_id;
    }

    public void setExperiment_id(int experiment_id) {
        this.experiment_id = experiment_id;
    }

    public String getExperiment_name() {
        return experiment_name;
    }

    public void setExperiment_name(String experiment_name) {
        this.experiment_name = experiment_name;
    }

    public int getStudent_id() {
        return student_id;
    }

    public void setStudent_id(int student_id) {
        this.student_id = student_id;
    }

    public String getStudent_name() {
        return student_name;
    }

    public void setStudent_name(String student_name) {
        this.student_name = student_name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    @Override
    public String toString() {
        return "StudentCode{" +
                "experiment_id=" + experiment_id +
                ", experiment_name='" + experiment_name + '\'' +
                ", student_id=" + student_id +
                ", student_name='" + student_name + '\'' +
                ", code='" + (code != null ? "..." : "null") + '\'' +
                '}';
    }
}