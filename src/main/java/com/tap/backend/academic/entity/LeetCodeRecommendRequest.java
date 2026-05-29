package com.tap.backend.academic.entity;

import java.time.LocalDateTime;

/**
 * LeetCode推荐请求实体类
 */
public class LeetCodeRecommendRequest {
    
    private Long id;
    private String requestId;
    private Integer studentId;
    private String scene;
    private Integer requestLimit;
    private String status;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;

    // 构造函数
    public LeetCodeRecommendRequest() {}

    public LeetCodeRecommendRequest(String requestId, Integer studentId, String scene, Integer requestLimit) {
        this.requestId = requestId;
        this.studentId = studentId;
        this.scene = scene;
        this.requestLimit = requestLimit;
        this.status = "pending";
        this.createdAt = LocalDateTime.now();
    }

    // Getter和Setter方法
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Integer getStudentId() {
        return studentId;
    }

    public void setStudentId(Integer studentId) {
        this.studentId = studentId;
    }

    public String getScene() {
        return scene;
    }

    public void setScene(String scene) {
        this.scene = scene;
    }

    public Integer getRequestLimit() {
        return requestLimit;
    }

    public void setRequestLimit(Integer requestLimit) {
        this.requestLimit = requestLimit;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    // 状态常量
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    // 便利方法
    public boolean isCompleted() {
        return STATUS_COMPLETED.equals(this.status);
    }

    public boolean isFailed() {
        return STATUS_FAILED.equals(this.status);
    }

    public boolean isPending() {
        return STATUS_PENDING.equals(this.status);
    }

    @Override
    public String toString() {
        return "LeetCodeRecommendRequest{" +
                "id=" + id +
                ", requestId='" + requestId + '\'' +
                ", studentId=" + studentId +
                ", status='" + status + '\'' +
                '}';
    }
}