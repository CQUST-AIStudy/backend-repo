package com.tap.backend.academic.teacherexperiment;

import java.util.Date;

public class TeacherStudentAssignmentRow {

    private Long classId;
    private String studentId;
    private String studentName;
    private String studentUsername;
    private String className;
    private Integer experimentId;
    private String experimentName;
    private Date deadline;
    private Date submitTime;
    private Double score;
    private String submissionStatus;
    private Boolean transcriptRowPresent;
    private Integer answerSheetCount;
    private Integer scoredCodeCount;
    private Integer submissionAttemptCount;
    private String completionEvidence;
    private String plagiarismRate;

    public Long getClassId() {
        return classId;
    }

    public void setClassId(Long classId) {
        this.classId = classId;
    }

    public String getStudentId() {
        return studentId;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    public String getStudentName() {
        return studentName;
    }

    public void setStudentName(String studentName) {
        this.studentName = studentName;
    }

    public String getStudentUsername() {
        return studentUsername;
    }

    public void setStudentUsername(String studentUsername) {
        this.studentUsername = studentUsername;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public Integer getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(Integer experimentId) {
        this.experimentId = experimentId;
    }

    public String getExperimentName() {
        return experimentName;
    }

    public void setExperimentName(String experimentName) {
        this.experimentName = experimentName;
    }

    public Date getDeadline() {
        return deadline;
    }

    public void setDeadline(Date deadline) {
        this.deadline = deadline;
    }

    public Date getSubmitTime() {
        return submitTime;
    }

    public void setSubmitTime(Date submitTime) {
        this.submitTime = submitTime;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public String getSubmissionStatus() {
        return submissionStatus;
    }

    public void setSubmissionStatus(String submissionStatus) {
        this.submissionStatus = submissionStatus;
    }

    public Boolean getTranscriptRowPresent() {
        return transcriptRowPresent;
    }

    public void setTranscriptRowPresent(Boolean transcriptRowPresent) {
        this.transcriptRowPresent = transcriptRowPresent;
    }

    public Integer getAnswerSheetCount() {
        return answerSheetCount;
    }

    public void setAnswerSheetCount(Integer answerSheetCount) {
        this.answerSheetCount = answerSheetCount;
    }

    public Integer getScoredCodeCount() {
        return scoredCodeCount;
    }

    public void setScoredCodeCount(Integer scoredCodeCount) {
        this.scoredCodeCount = scoredCodeCount;
    }

    public Integer getSubmissionAttemptCount() {
        return submissionAttemptCount;
    }

    public void setSubmissionAttemptCount(Integer submissionAttemptCount) {
        this.submissionAttemptCount = submissionAttemptCount;
    }

    public String getCompletionEvidence() {
        return completionEvidence;
    }

    public void setCompletionEvidence(String completionEvidence) {
        this.completionEvidence = completionEvidence;
    }

    public String getPlagiarismRate() {
        return plagiarismRate;
    }

    public void setPlagiarismRate(String plagiarismRate) {
        this.plagiarismRate = plagiarismRate;
    }
}
