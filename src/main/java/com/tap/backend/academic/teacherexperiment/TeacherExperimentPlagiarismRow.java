package com.tap.backend.academic.teacherexperiment;

public class TeacherExperimentPlagiarismRow {

    private String studentId;
    private Integer experimentId;
    private String plagiarismRate;

    public String getStudentId() {
        return studentId;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    public Integer getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(Integer experimentId) {
        this.experimentId = experimentId;
    }

    public String getPlagiarismRate() {
        return plagiarismRate;
    }

    public void setPlagiarismRate(String plagiarismRate) {
        this.plagiarismRate = plagiarismRate;
    }
}
