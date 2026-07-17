package com.tap.backend.academic.entity;

import java.util.Date;

/**
 * Persisted AI report for one student and assignment offering.
 */
public class AiExperimentReport {

    private Long id;
    private Long offeringId;
    private Long studentId;
    private String reportMd;
    private Date createdAt;
    private Date updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOfferingId() {
        return offeringId;
    }

    public void setOfferingId(Long offeringId) {
        this.offeringId = offeringId;
    }

    public Long getStudentId() {
        return studentId;
    }

    public void setStudentId(Long studentId) {
        this.studentId = studentId;
    }

    public String getReportMd() {
        return reportMd;
    }

    public void setReportMd(String reportMd) {
        this.reportMd = reportMd;
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
}
