package com.tap.backend.academic.learningtracking;

import java.time.LocalDateTime;

public class TimelineEntry implements Comparable<TimelineEntry> {
    private LocalDateTime timestamp;
    private String source;
    private String sourceLabel;
    private String title;
    private String action;
    private String result;
    private Double score;
    private String problemUrl;

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getSourceLabel() { return sourceLabel; }
    public void setSourceLabel(String sourceLabel) { this.sourceLabel = sourceLabel; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public String getProblemUrl() { return problemUrl; }
    public void setProblemUrl(String problemUrl) { this.problemUrl = problemUrl; }

    @Override
    public int compareTo(TimelineEntry other) {
        if (this.timestamp == null && other.timestamp == null) return 0;
        if (this.timestamp == null) return 1;
        if (other.timestamp == null) return -1;
        return other.timestamp.compareTo(this.timestamp);
    }
}
