package com.tap.backend.academic.entity;

import java.time.LocalDateTime;

/**
 * LeetCode推荐反馈实体类
 */
public class LeetCodeRecommendFeedback {
    private Long id;
    private String requestId;
    private Integer studentId;
    private Long problemId;
    private String sessionId;
    private String action;
    private LocalDateTime actionAt;
    private String extraJson;
    private LocalDateTime createdAt;

    // 行为类型常量
    public static final String ACTION_EXPOSURE = "exposure";
    public static final String ACTION_CLICK = "click";
    public static final String ACTION_START = "start";
    public static final String ACTION_COMPLETE = "complete";
    public static final String ACTION_SKIP = "skip";
    public static final String ACTION_DISLIKE = "dislike";

    // 构造函数
    public LeetCodeRecommendFeedback() {}

    public LeetCodeRecommendFeedback(String requestId, Integer studentId, Long problemId, String action) {
        this.requestId = requestId;
        this.studentId = studentId;
        this.problemId = problemId;
        this.action = action;
        this.actionAt = LocalDateTime.now();
    }

    // Getter和Setter方法
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public Integer getStudentId() { return studentId; }
    public void setStudentId(Integer studentId) { this.studentId = studentId; }

    public Long getProblemId() { return problemId; }
    public void setProblemId(Long problemId) { this.problemId = problemId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public LocalDateTime getActionAt() { return actionAt; }
    public void setActionAt(LocalDateTime actionAt) { this.actionAt = actionAt; }

    public String getExtraJson() { return extraJson; }
    public void setExtraJson(String extraJson) { this.extraJson = extraJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return "LeetCodeRecommendFeedback{" +
                "id=" + id +
                ", requestId='" + requestId + '\'' +
                ", studentId=" + studentId +
                ", problemId=" + problemId +
                ", action='" + action + '\'' +
                ", actionAt=" + actionAt +
                '}';
    }
}