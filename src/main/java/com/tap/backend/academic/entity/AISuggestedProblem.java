package com.tap.backend.academic.entity;

public class AISuggestedProblem {
    private int studentId;
    private String studentName;
    private int experimentId;
    private String content;

    // 构造函数
    public AISuggestedProblem() {}

    public AISuggestedProblem(int studentId, String studentName, int experimentId, String content) {
        this.studentId = studentId;
        this.studentName = studentName;
        this.experimentId = experimentId;
        this.content = content;
    }

    // getter和setter方法
    public int getStudentId() {
        return studentId;
    }

    public void setStudentId(int studentId) {
        this.studentId = studentId;
    }

    public String getStudentName() {
        return studentName;
    }

    public void setStudentName(String studentName) {
        this.studentName = studentName;
    }

    public int getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(int experimentId) {
        this.experimentId = experimentId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    @Override
    public String toString() {
        return "AISuggestedProblem{" +
                "studentId=" + studentId +
                ", studentName='" + studentName + '\'' +
                ", experimentId=" + experimentId +
                ", content='" + content + '\'' +
                '}';
    }
} 