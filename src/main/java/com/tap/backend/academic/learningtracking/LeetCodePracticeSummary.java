package com.tap.backend.academic.learningtracking;

public class LeetCodePracticeSummary {
    private int totalRecommended;
    private int submittedCount;
    private int acceptedCount;
    private int wrongCount;
    private Double avgScore;
    private double completionRate;

    public int getTotalRecommended() { return totalRecommended; }
    public void setTotalRecommended(int totalRecommended) { this.totalRecommended = totalRecommended; }
    public int getSubmittedCount() { return submittedCount; }
    public void setSubmittedCount(int submittedCount) { this.submittedCount = submittedCount; }
    public int getAcceptedCount() { return acceptedCount; }
    public void setAcceptedCount(int acceptedCount) { this.acceptedCount = acceptedCount; }
    public int getWrongCount() { return wrongCount; }
    public void setWrongCount(int wrongCount) { this.wrongCount = wrongCount; }
    public Double getAvgScore() { return avgScore; }
    public void setAvgScore(Double avgScore) { this.avgScore = avgScore; }
    public double getCompletionRate() { return completionRate; }
    public void setCompletionRate(double completionRate) { this.completionRate = completionRate; }
}
