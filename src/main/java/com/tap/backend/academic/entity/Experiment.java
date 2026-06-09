package com.tap.backend.academic.entity;

import java.util.Date;
import java.util.List;

public class Experiment {
    private int experiment_id;
    private int num;
    private String name;
    private String describe;
    private String deadline;
    private String status;
    private int score;
    private Date createdAt;
    private Date updatedAt;
    private String requirements;
    private double plagiarismRate;
    private String submitTime;
    private List<String> requirementsList;
    private int topic_sum;
    private String className;
    private String teacherId;

    public int getTopic_sum() {
        return topic_sum;
    }

    public void setTopic_sum(int topic_sum) {
        this.topic_sum = topic_sum;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getTeacherId() {
        return teacherId;
    }

    public void setTeacherId(String teacherId) {
        this.teacherId = teacherId;
    }

    public Experiment() {
    }
    
    public Experiment(int experiment_id, int num, String name, String describe, String deadline) {
        this.experiment_id = experiment_id;
        this.num = num;
        this.name = name;
        this.describe = describe;
        this.deadline = deadline;
    }

    @Override
    public String toString() {
        return "Experiment{" +
                "experiment_id=" + experiment_id +
                ", num=" + num +
                ", name='" + name + '\'' +
                ", describe='" + describe + '\'' +
                ", deadline='" + deadline + '\'' +
                ", className='" + className + '\'' +
                ", teacherId='" + teacherId + '\'' +
                ", requirements='" + requirements + '\'' +
                ", status='" + status + '\'' +
                ", score=" + score +
                '}';
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public int getExperiment_id() {
        return experiment_id;
    }

    public void setExperiment_id(int experiment_id) {
        this.experiment_id = experiment_id;
    }

    public int getNum() {
        return num;
    }

    public void setNum(int num) {
        this.num = num;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescribe() {
        return describe;
    }

    public void setDescribe(String describe) {
        this.describe = describe;
    }

    public String getDeadline() {
        return deadline;
    }

    public void setDeadline(String deadline) {
        this.deadline = deadline;
    }
    
    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getRequirements() {
        return requirements;
    }

    public void setRequirements(String requirements) {
        this.requirements = requirements;
    }
    
    public double getPlagiarismRate() {
        return plagiarismRate;
    }

    public void setPlagiarismRate(double plagiarismRate) {
        this.plagiarismRate = plagiarismRate;
    }

    public String getSubmitTime() {
        return submitTime;
    }

    public void setSubmitTime(String submitTime) {
        this.submitTime = submitTime;
    }

    public List<String> getRequirementsList() {
        return requirementsList;
    }

    public void setRequirementsList(List<String> requirementsList) {
        this.requirementsList = requirementsList;
    }
}
