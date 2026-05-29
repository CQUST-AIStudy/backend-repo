package com.tap.backend.academic.leetcode.execution;

import java.util.ArrayList;
import java.util.List;

public final class AiEvaluationResult {

    private boolean unavailable;
    private boolean accepted;
    private int score;
    private double confidence;
    private double estimatedPassRate;
    private String feedback = "";
    private List<String> skillSuggestions = new ArrayList<>();
    private List<String> riskNotes = new ArrayList<>();

    public boolean unavailable() {
        return unavailable;
    }

    public void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    public boolean accepted() {
        return accepted;
    }

    public void setAccepted(boolean accepted) {
        this.accepted = accepted;
    }

    public int score() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public double confidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public double estimatedPassRate() {
        return estimatedPassRate;
    }

    public void setEstimatedPassRate(double estimatedPassRate) {
        this.estimatedPassRate = estimatedPassRate;
    }

    public String feedback() {
        return feedback;
    }

    public void setFeedback(String feedback) {
        this.feedback = feedback == null ? "" : feedback;
    }

    public List<String> skillSuggestions() {
        return skillSuggestions;
    }

    public void setSkillSuggestions(List<String> skillSuggestions) {
        this.skillSuggestions = skillSuggestions == null ? new ArrayList<>() : new ArrayList<>(skillSuggestions);
    }

    public List<String> riskNotes() {
        return riskNotes;
    }

    public void setRiskNotes(List<String> riskNotes) {
        this.riskNotes = riskNotes == null ? new ArrayList<>() : new ArrayList<>(riskNotes);
    }
}
