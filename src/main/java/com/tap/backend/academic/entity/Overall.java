package com.tap.backend.academic.entity;

import java.util.List;

public class Overall {
    private int averageScore;
    private int completionRate;
    private List<String> weaknessAreas;

    public int getAverageScore() {
        return averageScore;
    }

    public void setAverageScore(int averageScore) {
        this.averageScore = averageScore;
    }

    public Overall(int averageScore, int completionRate, List<String> weaknessAreas, List<String> suggestionTopics) {
        this.averageScore = averageScore;
        this.completionRate = completionRate;
        this.weaknessAreas = weaknessAreas;
        this.suggestionTopics = suggestionTopics;
    }

    public int getCompletionRate() {
        return completionRate;
    }

    public void setCompletionRate(int completionRate) {
        this.completionRate = completionRate;
    }

    public List<String> getWeaknessAreas() {
        return weaknessAreas;
    }

    public void setWeaknessAreas(List<String> weaknessAreas) {
        this.weaknessAreas = weaknessAreas;
    }

    public List<String> getSuggestionTopics() {
        return suggestionTopics;
    }

    public void setSuggestionTopics(List<String> suggestionTopics) {
        this.suggestionTopics = suggestionTopics;
    }

    private List<String> suggestionTopics;


}
