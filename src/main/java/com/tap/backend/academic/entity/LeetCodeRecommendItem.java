package com.tap.backend.academic.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * LeetCode推荐结果项实体类
 */
public class LeetCodeRecommendItem {
    private Long id;
    private String requestId;
    private Integer studentId;
    private Integer rankNo;
    private Long problemId;
    private BigDecimal scoreTotal;
    private BigDecimal scoreNeedMatch;
    private BigDecimal scoreDifficultyFit;
    private BigDecimal scoreSuccessProb;
    private BigDecimal scoreNovelty;
    private BigDecimal scoreQuality;
    private String reasonText;
    private String reasonJson;
    private LocalDateTime createdAt;

    // 关联的题目信息（用于查询结果）
    private LeetCodeProblem problem;

    // 遗忘曲线相关字段（透传推荐服务返回的数据）
    private BigDecimal forgettingScore;
    private String lastPracticeAt;

    // 构造函数
    public LeetCodeRecommendItem() {}

    public LeetCodeRecommendItem(String requestId, Integer studentId, Integer rankNo, Long problemId) {
        this.requestId = requestId;
        this.studentId = studentId;
        this.rankNo = rankNo;
        this.problemId = problemId;
    }

    // Getter和Setter方法
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public Integer getStudentId() { return studentId; }
    public void setStudentId(Integer studentId) { this.studentId = studentId; }

    public Integer getRankNo() { return rankNo; }
    public void setRankNo(Integer rankNo) { this.rankNo = rankNo; }

    public Long getProblemId() { return problemId; }
    public void setProblemId(Long problemId) { this.problemId = problemId; }

    public BigDecimal getScoreTotal() { return scoreTotal; }
    public void setScoreTotal(BigDecimal scoreTotal) { this.scoreTotal = scoreTotal; }
    public BigDecimal getScoreNeedMatch() { return scoreNeedMatch; }
    public void setScoreNeedMatch(BigDecimal scoreNeedMatch) { this.scoreNeedMatch = scoreNeedMatch; }

    public BigDecimal getScoreDifficultyFit() { return scoreDifficultyFit; }
    public void setScoreDifficultyFit(BigDecimal scoreDifficultyFit) { this.scoreDifficultyFit = scoreDifficultyFit; }

    public BigDecimal getScoreSuccessProb() { return scoreSuccessProb; }
    public void setScoreSuccessProb(BigDecimal scoreSuccessProb) { this.scoreSuccessProb = scoreSuccessProb; }

    public BigDecimal getScoreNovelty() { return scoreNovelty; }
    public void setScoreNovelty(BigDecimal scoreNovelty) { this.scoreNovelty = scoreNovelty; }

    public BigDecimal getScoreQuality() { return scoreQuality; }
    public void setScoreQuality(BigDecimal scoreQuality) { this.scoreQuality = scoreQuality; }

    public String getReasonText() { return reasonText; }
    public void setReasonText(String reasonText) { this.reasonText = reasonText; }

    public String getReasonJson() { return reasonJson; }
    public void setReasonJson(String reasonJson) { this.reasonJson = reasonJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LeetCodeProblem getProblem() { return problem; }
    public void setProblem(LeetCodeProblem problem) { this.problem = problem; }

    public BigDecimal getForgettingScore() { return forgettingScore; }
    public void setForgettingScore(BigDecimal forgettingScore) { this.forgettingScore = forgettingScore; }

    public String getLastPracticeAt() { return lastPracticeAt; }
    public void setLastPracticeAt(String lastPracticeAt) { this.lastPracticeAt = lastPracticeAt; }

    @Override
    public String toString() {
        return "LeetCodeRecommendItem{" +
                "id=" + id +
                ", requestId='" + requestId + '\'' +
                ", studentId=" + studentId +
                ", rankNo=" + rankNo +
                ", problemId=" + problemId +
                ", scoreTotal=" + scoreTotal +
                ", reasonText='" + reasonText + '\'' +
                '}';
    }
}