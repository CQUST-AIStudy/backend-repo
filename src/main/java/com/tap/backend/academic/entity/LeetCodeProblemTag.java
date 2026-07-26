package com.tap.backend.academic.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * LeetCode题目标签实体类
 */
public class LeetCodeProblemTag {
    
    private Long id;
    private Long problemId;
    private String tagName;
    private String tagCategory;
    private BigDecimal relevanceScore;
    private Boolean primary;
    private LocalDateTime createdAt;

    // 构造函数
    public LeetCodeProblemTag() {}

    public LeetCodeProblemTag(Long problemId, String tagCategory, String tagName) {
        this.problemId = problemId;
        this.tagCategory = tagCategory;
        this.tagName = tagName;
        this.relevanceScore = new BigDecimal("0.8000");
        this.primary = false;
    }

    public LeetCodeProblemTag(Long problemId, String tagCategory, String tagName, BigDecimal relevanceScore) {
        this.problemId = problemId;
        this.tagCategory = tagCategory;
        this.tagName = tagName;
        this.relevanceScore = relevanceScore;
        this.primary = false;
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

    public String getTagName() {
        return tagName;
    }

    public void setTagName(String tagName) {
        this.tagName = tagName;
    }

    public String getTagCategory() {
        return tagCategory;
    }

    public void setTagCategory(String tagCategory) {
        this.tagCategory = tagCategory;
    }

    public BigDecimal getRelevanceScore() {
        return relevanceScore;
    }

    public void setRelevanceScore(BigDecimal relevanceScore) {
        this.relevanceScore = relevanceScore;
    }

    public Boolean getPrimary() {
        return primary;
    }

    public void setPrimary(Boolean primary) {
        this.primary = primary;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "LeetCodeProblemTag{" +
                "id=" + id +
                ", problemId=" + problemId +
                ", tagName='" + tagName + '\'' +
                ", tagCategory='" + tagCategory + '\'' +
                ", relevanceScore=" + relevanceScore +
                ", primary=" + primary +
                '}';
    }
}
