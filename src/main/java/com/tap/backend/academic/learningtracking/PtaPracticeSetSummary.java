package com.tap.backend.academic.learningtracking;

public class PtaPracticeSetSummary {
    private Long offeringId;
    private String title;
    private int problemCount;
    private int submittedCount;
    private int acceptedCount;
    private Double score;
    private String status;
    private String sourceUrl;

    public Long getOfferingId() { return offeringId; }
    public void setOfferingId(Long offeringId) { this.offeringId = offeringId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public int getProblemCount() { return problemCount; }
    public void setProblemCount(int problemCount) { this.problemCount = problemCount; }
    public int getSubmittedCount() { return submittedCount; }
    public void setSubmittedCount(int submittedCount) { this.submittedCount = submittedCount; }
    public int getAcceptedCount() { return acceptedCount; }
    public void setAcceptedCount(int acceptedCount) { this.acceptedCount = acceptedCount; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
}
