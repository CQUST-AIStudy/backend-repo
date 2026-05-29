package com.tap.backend.academic.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * LeetCode题目实体类
 */
public class LeetCodeProblem {
    
    private Long id;
    private String sourceKey;
    private String problemCode;
    private Integer numericId;
    private String titleMain;
    private String titleAlt;
    private String problemText;
    private String solutionText;
    private String sourceUrl;
    private String difficulty;
    private Integer estimatedMinutes;
    private BigDecimal qualityScore;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 构造函数
    public LeetCodeProblem() {}

    public LeetCodeProblem(String sourceKey, String problemCode, String titleMain, 
                          String problemText, String solutionText) {
        this.sourceKey = sourceKey;
        this.problemCode = problemCode;
        this.titleMain = titleMain;
        this.problemText = problemText;
        this.solutionText = solutionText;
        this.difficulty = "Unknown";
        this.estimatedMinutes = 30;
        this.qualityScore = new BigDecimal("0.8000");
    }

    // Getter和Setter方法
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public void setSourceKey(String sourceKey) {
        this.sourceKey = sourceKey;
    }

    public String getProblemCode() {
        return problemCode;
    }

    public void setProblemCode(String problemCode) {
        this.problemCode = problemCode;
    }

    public Integer getNumericId() {
        return numericId;
    }

    public void setNumericId(Integer numericId) {
        this.numericId = numericId;
    }

    public String getTitleMain() {
        return titleMain;
    }

    public void setTitleMain(String titleMain) {
        this.titleMain = titleMain;
    }

    public String getTitleAlt() {
        return titleAlt;
    }

    public void setTitleAlt(String titleAlt) {
        this.titleAlt = titleAlt;
    }

    public String getProblemText() {
        return problemText;
    }

    public void setProblemText(String problemText) {
        this.problemText = problemText;
    }

    public String getSolutionText() {
        return solutionText;
    }

    public void setSolutionText(String solutionText) {
        this.solutionText = solutionText;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public Integer getEstimatedMinutes() {
        return estimatedMinutes;
    }

    public void setEstimatedMinutes(Integer estimatedMinutes) {
        this.estimatedMinutes = estimatedMinutes;
    }

    public BigDecimal getQualityScore() {
        return qualityScore;
    }

    public void setQualityScore(BigDecimal qualityScore) {
        this.qualityScore = qualityScore;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "LeetCodeProblem{" +
                "id=" + id +
                ", sourceKey='" + sourceKey + '\'' +
                ", problemCode='" + problemCode + '\'' +
                ", titleMain='" + titleMain + '\'' +
                ", difficulty='" + difficulty + '\'' +
                '}';
    }
}