package com.tap.backend.academic.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 学生技能状态实体类（画像核心）
 */
public class StudentSkillState {
    private Long id;
    private Integer studentId;
    private String tagName;
    private BigDecimal masteryScore;
    private BigDecimal forgettingScore;
    private BigDecimal confidenceScore;
    private Integer attemptCount;
    private Integer successCount;
    private BigDecimal avgAttemptsToSuccess;
    private LocalDateTime lastPracticeAt;
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;

    // 构造函数
    public StudentSkillState() {}

    public StudentSkillState(Integer studentId, String tagName) {
        this.studentId = studentId;
        this.tagName = tagName;
        this.masteryScore = new BigDecimal("50.00");
        this.forgettingScore = new BigDecimal("0.00");
        this.confidenceScore = new BigDecimal("0.00");
        this.attemptCount = 0;
        this.successCount = 0;
    }

    // Getter和Setter方法
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getStudentId() { return studentId; }
    public void setStudentId(Integer studentId) { this.studentId = studentId; }

    public String getTagName() { return tagName; }
    public void setTagName(String tagName) { this.tagName = tagName; }

    public BigDecimal getMasteryScore() { return masteryScore; }
    public void setMasteryScore(BigDecimal masteryScore) { this.masteryScore = masteryScore; }

    public BigDecimal getForgettingScore() { return forgettingScore; }
    public void setForgettingScore(BigDecimal forgettingScore) { this.forgettingScore = forgettingScore; }

    public BigDecimal getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(BigDecimal confidenceScore) { this.confidenceScore = confidenceScore; }

    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }

    public Integer getSuccessCount() { return successCount; }
    public void setSuccessCount(Integer successCount) { this.successCount = successCount; }

    public BigDecimal getAvgAttemptsToSuccess() { return avgAttemptsToSuccess; }
    public void setAvgAttemptsToSuccess(BigDecimal avgAttemptsToSuccess) { this.avgAttemptsToSuccess = avgAttemptsToSuccess; }

    public LocalDateTime getLastPracticeAt() { return lastPracticeAt; }
    public void setLastPracticeAt(LocalDateTime lastPracticeAt) { this.lastPracticeAt = lastPracticeAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    /**
     * 计算掌握度归一化值 (0-1)
     */
    public double getMasteryNorm() {
        return masteryScore.doubleValue() / 100.0;
    }

    /**
     * 计算遗忘度归一化值 (0-1)
     */
    public double getForgettingNorm() {
        return forgettingScore.doubleValue() / 100.0;
    }

    /**
     * 计算成功率
     */
    public double getSuccessRate() {
        if (attemptCount == 0) return 0.0;
        return (double) successCount / attemptCount;
    }

    @Override
    public String toString() {
        return "StudentSkillState{" +
                "id=" + id +
                ", studentId=" + studentId +
                ", tagName='" + tagName + '\'' +
                ", masteryScore=" + masteryScore +
                ", forgettingScore=" + forgettingScore +
                ", confidenceScore=" + confidenceScore +
                ", attemptCount=" + attemptCount +
                ", successCount=" + successCount +
                '}';
    }
}