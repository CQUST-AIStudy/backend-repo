package com.tap.backend.academic.entity;

import java.util.Date;

public class Score {
    private int score_id;
    private String username;
    private String real_name;
    private int experiment_id;
    private Integer num;
    private Integer score;

    public String getReal_name() {
        return real_name;
    }

    public void setReal_name(String real_name) {
        this.real_name = real_name;
    }

    private Date submit_time;
    private String plagiarism_rate;
    private String status;
    private int serial_number;

    public Score() {
    }


    public Score(int score_id, String username, String real_name, int experiment_id, Integer score, Date submit_time, String plagiarism_rate, String status, int serial_number) {
        this.score_id = score_id;
        this.username = username;
        this.real_name = real_name;
        this.experiment_id = experiment_id;
        this.score = score;
        this.submit_time = submit_time;
        this.plagiarism_rate = plagiarism_rate;
        this.status = status;
        this.serial_number = serial_number;
    }

    public int getSerial_number() {
        return serial_number;
    }

    public void setSerial_number(int serial_number) {
        this.serial_number = serial_number;
    }

    @Override
    public String toString() {
        return "Score{" +
                "score_id=" + score_id +
                ", username='" + username + '\'' +
                ", experiment_id=" + experiment_id +
                ", num=" + num +
                ", score=" + score +
                ", submit_time=" + submit_time +
                ", plagiarism_rate=" + plagiarism_rate +
                ", status='" + status + '\'' +
                ", serial_number=" + serial_number +
                '}';
    }

    public int getScore_id() {
        return score_id;
    }

    public void setScore_id(int score_id) {
        this.score_id = score_id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }





    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public Date getSubmit_time() {
        return submit_time;
    }

    public int getExperiment_id() {
        return experiment_id;
    }

    public void setExperiment_id(int experiment_id) {
        this.experiment_id = experiment_id;
    }

    public Integer getNum() {
        return num;
    }

    public void setNum(Integer num) {
        this.num = num;
    }

    public void setSubmit_time(Date submit_time) {
        this.submit_time = submit_time;
    }

    public String getPlagiarism_rate() {
        return plagiarism_rate;
    }

    public void setPlagiarism_rate(String plagiarism_rate) {
        this.plagiarism_rate = plagiarism_rate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
