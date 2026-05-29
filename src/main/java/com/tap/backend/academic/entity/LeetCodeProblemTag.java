package com.tap.backend.academic.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * LeetCode题目标签实体类
 */
public class LeetCodeProblemTag {
    
    private Long id;
    private Long problemId;
    private String tagType;
    private String tagValue;
    private BigDecimal confidence;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 构造函数
    public LeetCodeProblemTag() {}

    public LeetCodeProblemTag(Long problemId, String tagType, String tagValue) {
        this.problemId = problemId;
        this.tagType = tagType;
        this.tagValue = tagValue;
        this.confidence = new BigDecimal("0.80");
    }

    public LeetCodeProblemTag(Long problemId, String tagType, String tagValue, BigDecimal confidence) {
        this.problemId = problemId;
        this.tagType = tagType;
        this.tagValue = tagValue;
        this.confidence = confidence;
    }

    // Getter和Setter方法
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProblemId() {
        return problemId;
    }

    public void setProblemId(Long problemId) {
        this.problemId = problemId;
    }

    public String getTagType() {
        return tagType;
    }

    public void setTagType(String tagType) {
        this.tagType = tagType;
    }

    public String getTagValue() {
        return tagValue;
    }

    public void setTagValue(String tagValue) {
        this.tagValue = tagValue;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
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
        return "LeetCodeProblemTag{" +
                "id=" + id +
                ", problemId=" + problemId +
                ", tagType='" + tagType + '\'' +
                ", tagValue='" + tagValue + '\'' +
                ", confidence=" + confidence +
                '}';
    }
}