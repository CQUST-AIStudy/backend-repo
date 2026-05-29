package com.tap.backend.academic.entity;

public class LearningAnalysis {
    private Overall overall;

    public Overall getOverall() {
        return overall;
    }

    @Override
    public String toString() {
        return "LearningAnalysis{" +
                "overall=" + overall +
                '}';
    }

    public void setOverall(Overall overall) {
        this.overall = overall;
    }

    public LearningAnalysis(Overall overall) {
        this.overall = overall;
    }
}
