package com.tap.backend.academic.teacherexperiment;

public class TeacherExperimentScoreAggregate {

    private Integer experimentId;
    private Integer submissionCount;
    private Integer totalPositiveScore;

    public Integer getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(Integer experimentId) {
        this.experimentId = experimentId;
    }

    public Integer getSubmissionCount() {
        return submissionCount;
    }

    public void setSubmissionCount(Integer submissionCount) {
        this.submissionCount = submissionCount;
    }

    public Integer getTotalPositiveScore() {
        return totalPositiveScore;
    }

    public void setTotalPositiveScore(Integer totalPositiveScore) {
        this.totalPositiveScore = totalPositiveScore;
    }
}
