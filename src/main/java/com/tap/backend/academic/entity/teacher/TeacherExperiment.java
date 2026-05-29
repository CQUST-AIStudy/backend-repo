package com.tap.backend.academic.entity.teacher;

import java.util.Date;

/**
 * 教师实验管理实体类
 * 用于展示实验列表和统计信息
 */
public class TeacherExperiment {
    
    private Integer id;                // 实验ID，从1开始递增
    private String name;               // 实验名称
    private Date deadline;             // 截止日期
    private Date createdTime;          // 创建日期
    private String status;             // 状态：active(进行中)、draft(草稿)、expired(已截止)
    private Integer submissionCount;   // 提交人数
    private Double averageScore;       // 平均分数
    
    // 构造函数
    public TeacherExperiment(int experimentId, String name, String deadlineStr, Date createdAt) {
        this.id = experimentId;
        this.name = name;
        // 如果deadline是字符串格式，需要转换为Date
        try {
            if (deadlineStr != null && !deadlineStr.trim().isEmpty()) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
                this.deadline = sdf.parse(deadlineStr);
            }
        } catch (Exception e) {
            System.out.println("无法解析截止日期字符串: " + deadlineStr);
        }
        this.createdTime = createdAt;
        updateStatus();
    }
    
    public TeacherExperiment(Integer id, String name, Date deadline, Date createdTime) {
        this.id = id;
        this.name = name;
        this.deadline = deadline;
        this.createdTime = createdTime;
        updateStatus();
    }
    
    // 根据截止日期自动更新状态
    public void updateStatus() {
        if (deadline == null) {
            this.status = "draft"; // 没有截止日期视为草稿
            return;
        }
        
        Date now = new Date();
        if (now.after(deadline)) {
            this.status = "expired"; // 当前日期在截止日期之后，已截止
        } else {
            this.status = "active";  // 当前日期在截止日期之前，进行中
        }
    }

    // Getters and Setters
    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Date getDeadline() {
        return deadline;
    }

    public void setDeadline(Date deadline) {
        this.deadline = deadline;
        updateStatus(); // 更新截止日期时重新计算状态
    }

    public Date getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(Date createdTime) {
        this.createdTime = createdTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getSubmissionCount() {
        return submissionCount;
    }

    public void setSubmissionCount(Integer submissionCount) {
        this.submissionCount = submissionCount;
    }

    public Double getAverageScore() {
        return averageScore;
    }

    public void setAverageScore(Double averageScore) {
        this.averageScore = averageScore;
    }
    
    @Override
    public String toString() {
        return "TeacherExperiment{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", deadline=" + deadline +
                ", createdTime=" + createdTime +
                ", status='" + status + '\'' +
                ", submissionCount=" + submissionCount +
                ", averageScore=" + averageScore +
                '}';
    }
}