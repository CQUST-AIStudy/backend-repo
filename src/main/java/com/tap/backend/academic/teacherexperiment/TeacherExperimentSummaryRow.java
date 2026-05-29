package com.tap.backend.academic.teacherexperiment;

import java.util.Date;

public class TeacherExperimentSummaryRow {

    private Integer experimentId;
    private String name;
    private Date deadline;
    private Date createdTime;
    private Integer rosterCount;
    private Integer submissionCount;
    private Double averageScore;

    public Integer getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(Integer experimentId) {
        this.experimentId = experimentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Date getDeadline() {
        return deadline;
    }

    public void setDeadline(Date deadline) {
        this.deadline = deadline;
    }

    public Date getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(Date createdTime) {
        this.createdTime = createdTime;
    }

    public Integer getRosterCount() {
        return rosterCount;
    }

    public void setRosterCount(Integer rosterCount) {
        this.rosterCount = rosterCount;
    }

    public Integer getSubmissionCount() {
        return submissionCount;
    }

    public void setSubmissionCount(Integer submissionCount) {
        this.submissionCount = submissionCount;
    }

    public Double getAverageScore() {
        return averageScore;
    }

    public void setAverageScore(Double averageScore) {
        this.averageScore = averageScore;
    }
}
