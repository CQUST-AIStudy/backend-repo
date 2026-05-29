package com.tap.backend.academic.entity;

import java.util.Date;

public class Submission {
    private int submission_id;
    private String username;
    private int experiment_id;
    private String code;
    private String report;
    private Date submit_time;

    public Submission() {
    }

    public Submission(int submission_id, String username, int experiment_id, String code, String report, Date submit_time) {
        this.submission_id = submission_id;
        this.username = username;
        this.experiment_id = experiment_id;
        this.code = code;
        this.report = report;
        this.submit_time = submit_time;
    }

    @Override
    public String toString() {
        return "Submission{" +
                "submission_id=" + submission_id +
                ", username='" + username + '\'' +
                ", experiment_id=" + experiment_id +
                ", submit_time=" + submit_time +
                '}';
    }

    public int getSubmission_id() {
        return submission_id;
    }

    public void setSubmission_id(int submission_id) {
        this.submission_id = submission_id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public int getExperiment_id() {
        return experiment_id;
    }

    public void setExperiment_id(int experiment_id) {
        this.experiment_id = experiment_id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getReport() {
        return report;
    }

    public void setReport(String report) {
        this.report = report;
    }

    public Date getSubmit_time() {
        return submit_time;
    }

    public void setSubmit_time(Date submit_time) {
        this.submit_time = submit_time;
    }
}
