package com.tap.backend.academic.teacherexperiment;

import java.util.Date;

public class TeacherSubmissionProblemRow {

    private Integer sortOrder;
    private String problemNo;
    private String problemTitle;
    private String statementMd;
    private String latestStatus;
    private Double bestScore;
    private Integer attemptCount;
    private Date submitTime;
    private String code;

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getProblemNo() {
        return problemNo;
    }

    public void setProblemNo(String problemNo) {
        this.problemNo = problemNo;
    }

    public String getProblemTitle() {
        return problemTitle;
    }

    public void setProblemTitle(String problemTitle) {
        this.problemTitle = problemTitle;
    }

    public String getStatementMd() {
        return statementMd;
    }

    public void setStatementMd(String statementMd) {
        this.statementMd = statementMd;
    }

    public String getLatestStatus() {
        return latestStatus;
    }

    public void setLatestStatus(String latestStatus) {
        this.latestStatus = latestStatus;
    }

    public Double getBestScore() {
        return bestScore;
    }

    public void setBestScore(Double bestScore) {
        this.bestScore = bestScore;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Date getSubmitTime() {
        return submitTime;
    }

    public void setSubmitTime(Date submitTime) {
        this.submitTime = submitTime;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
