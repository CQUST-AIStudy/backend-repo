package com.tap.backend.academic.entity;

import java.util.Date;

public class AiFeedback {
    private int feedback_id;
    private int submission_id;
    private String ai_comment;
    private String suggestion;
    private Date createdAt;

    public AiFeedback() {
    }

    public AiFeedback(int feedback_id, int submission_id, String ai_comment, String suggestion, Date createdAt) {
        this.feedback_id = feedback_id;
        this.submission_id = submission_id;
        this.ai_comment = ai_comment;
        this.suggestion = suggestion;
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "AiFeedback{" +
                "feedback_id=" + feedback_id +
                ", submission_id=" + submission_id +
                ", ai_comment='" + ai_comment + '\'' +
                ", suggestion='" + suggestion + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }

    public int getFeedback_id() {
        return feedback_id;
    }

    public void setFeedback_id(int feedback_id) {
        this.feedback_id = feedback_id;
    }

    public int getSubmission_id() {
        return submission_id;
    }

    public void setSubmission_id(int submission_id) {
        this.submission_id = submission_id;
    }

    public String getAi_comment() {
        return ai_comment;
    }

    public void setAi_comment(String ai_comment) {
        this.ai_comment = ai_comment;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public void setSuggestion(String suggestion) {
        this.suggestion = suggestion;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }
}