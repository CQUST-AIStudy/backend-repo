package com.tap.backend.academic.entity;

/**
 * AI备注实体类，对应数据库中的AI_remarks表
 */
public class AIRemarks {
    private String studentId;      // 学号（兼容新旧系统，统一用学号字符串）
    private String studentName;    // 学生姓名
    private Integer experimentId;  // 实验ID
    private String experimentName; // 实验名称
    private String airemark;       // AI生成内容
    
    // 构造函数
    public AIRemarks() {
    }
    
    public AIRemarks(String studentId, String studentName, Integer experimentId, String experimentName, String airemark) {
        this.studentId = studentId;
        this.studentName = studentName;
        this.experimentId = experimentId;
        this.experimentName = experimentName;
        this.airemark = airemark;
    }
    
    // getter和setter方法
    public String getStudentId() {
        return studentId;
    }
    
    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }
    
    public String getStudentName() {
        return studentName;
    }
    
    public void setStudentName(String studentName) {
        this.studentName = studentName;
    }
    
    public Integer getExperimentId() {
        return experimentId;
    }
    
    public void setExperimentId(Integer experimentId) {
        this.experimentId = experimentId;
    }
    
    public String getExperimentName() {
        return experimentName;
    }
    
    public void setExperimentName(String experimentName) {
        this.experimentName = experimentName;
    }
    
    public String getAiremark() {
        return airemark;
    }
    
    public void setAiremark(String airemark) {
        this.airemark = airemark;
    }
    
    @Override
    public String toString() {
        return "AIRemarks{" +
                "studentId=" + studentId +
                ", studentName='" + studentName + '\'' +
                ", experimentId=" + experimentId +
                ", experimentName='" + experimentName + '\'' +
                ", airemark='" + (airemark != null ? airemark.substring(0, Math.min(20, airemark.length())) + "..." : null) + '\'' +
                '}';
    }
}