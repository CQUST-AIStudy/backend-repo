package com.tap.backend.academic.entity;

import java.time.LocalDateTime;

/**
 * LeetCode 提交判题记录实体
 * 持久化 AI 判题结果，用于学情追踪
 */
public class LeetCodeSubmissionRecord {
    private Long id;
    private Integer studentId;
    private Long problemId;
    private String code;
    private String language;
    private Boolean accepted;
    private Integer score;
    private String aiFeedback;
    private Integer passedCases;
    private Integer totalCases;
    private Double confidence;
    private String recommendationRequestId;
    private String recommendationSessionId;
    private LocalDateTime submittedAt;
    private LocalDateTime createdAt;

    // -- 题目联查字段（非表列） --
    private String problemTitle;
    private String problemDifficulty;
    private String problemSourceUrl;

    public LeetCodeSubmissionRecord() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getStudentId() { return studentId; }
    public void setStudentId(Integer studentId) { this.studentId = studentId; }
    public Long getProblemId() { return problemId; }
    public void setProblemId(Long problemId) { this.problemId = problemId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public Boolean getAccepted() { return accepted; }
    public void setAccepted(Boolean accepted) { this.accepted = accepted; }
    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }
    public String getAiFeedback() { return aiFeedback; }
    public void setAiFeedback(String aiFeedback) { this.aiFeedback = aiFeedback; }
    public Integer getPassedCases() { return passedCases; }
    public void setPassedCases(Integer passedCases) { this.passedCases = passedCases; }
    public Integer getTotalCases() { return totalCases; }
    public void setTotalCases(Integer totalCases) { this.totalCases = totalCases; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public String getRecommendationRequestId() { return recommendationRequestId; }
    public void setRecommendationRequestId(String recommendationRequestId) { this.recommendationRequestId = recommendationRequestId; }
    public String getRecommendationSessionId() { return recommendationSessionId; }
    public void setRecommendationSessionId(String recommendationSessionId) { this.recommendationSessionId = recommendationSessionId; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getProblemTitle() { return problemTitle; }
    public void setProblemTitle(String problemTitle) { this.problemTitle = problemTitle; }
    public String getProblemDifficulty() { return problemDifficulty; }
    public void setProblemDifficulty(String problemDifficulty) { this.problemDifficulty = problemDifficulty; }
    public String getProblemSourceUrl() { return problemSourceUrl; }
    public void setProblemSourceUrl(String problemSourceUrl) { this.problemSourceUrl = problemSourceUrl; }
}
