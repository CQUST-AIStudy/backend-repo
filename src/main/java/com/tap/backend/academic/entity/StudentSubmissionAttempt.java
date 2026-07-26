package com.tap.backend.academic.entity;

import java.util.Date;

/**
 * 学生提交尝试实体 —— 对应 student_problem_attempt 表
 * 用于 AI 错误分析时获取学生在实验中的每次提交详情
 */
public class StudentSubmissionAttempt {

    private Integer attemptId;
    private String judgeStatus;
    private String compiler;
    private Date submittedAt;
    private Integer score;
    private Integer runtimeMs;
    private Integer memoryKb;
    private String code;
    private Long problemId;
    private String problemTitle;
    private String errorMessage;
    private String rawJson;

    public StudentSubmissionAttempt() {}

    public Integer getAttemptId() { return attemptId; }
    public void setAttemptId(Integer attemptId) { this.attemptId = attemptId; }

    public String getJudgeStatus() { return judgeStatus; }
    public void setJudgeStatus(String judgeStatus) { this.judgeStatus = judgeStatus; }

    public String getCompiler() { return compiler; }
    public void setCompiler(String compiler) { this.compiler = compiler; }

    public Date getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Date submittedAt) { this.submittedAt = submittedAt; }

    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }

    public Integer getRuntimeMs() { return runtimeMs; }
    public void setRuntimeMs(Integer runtimeMs) { this.runtimeMs = runtimeMs; }

    public Integer getMemoryKb() { return memoryKb; }
    public void setMemoryKb(Integer memoryKb) { this.memoryKb = memoryKb; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public Long getProblemId() { return problemId; }
    public void setProblemId(Long problemId) { this.problemId = problemId; }

    public String getProblemTitle() { return problemTitle; }
    public void setProblemTitle(String problemTitle) { this.problemTitle = problemTitle; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getRawJson() { return rawJson; }
    public void setRawJson(String rawJson) { this.rawJson = rawJson; }
}
