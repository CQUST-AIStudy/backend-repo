package com.tap.backend.domain.teaching;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "teaching_advice_report")
public class TeachingAdviceReportEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "teacher_id", nullable = false)
    private Long teacherId;

    @Column(name = "scope_level", nullable = false, length = 16)
    private String scopeLevel;

    @Column(name = "course_id")
    private Long courseId;

    @Column(name = "term_id")
    private Long termId;

    @Column(name = "class_id")
    private Long classId;

    @Column(name = "experiment_id")
    private Long experimentId;

    @Column(name = "scope_json", nullable = false, columnDefinition = "longtext")
    private String scopeJson;

    @Column(name = "metrics_json", nullable = false, columnDefinition = "longtext")
    private String metricsJson;

    @Column(name = "advice_json", columnDefinition = "longtext")
    private String adviceJson;

    @Column(name = "prompt_version", nullable = false, length = 64)
    private String promptVersion;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "source_hash", length = 64)
    private String sourceHash;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public Long getTeacherId() { return teacherId; }
    public void setTeacherId(Long teacherId) { this.teacherId = teacherId; }
    public String getScopeLevel() { return scopeLevel; }
    public void setScopeLevel(String scopeLevel) { this.scopeLevel = scopeLevel; }
    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public Long getTermId() { return termId; }
    public void setTermId(Long termId) { this.termId = termId; }
    public Long getClassId() { return classId; }
    public void setClassId(Long classId) { this.classId = classId; }
    public Long getExperimentId() { return experimentId; }
    public void setExperimentId(Long experimentId) { this.experimentId = experimentId; }
    public String getScopeJson() { return scopeJson; }
    public void setScopeJson(String scopeJson) { this.scopeJson = scopeJson; }
    public String getMetricsJson() { return metricsJson; }
    public void setMetricsJson(String metricsJson) { this.metricsJson = metricsJson; }
    public String getAdviceJson() { return adviceJson; }
    public void setAdviceJson(String adviceJson) { this.adviceJson = adviceJson; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getSourceHash() { return sourceHash; }
    public void setSourceHash(String sourceHash) { this.sourceHash = sourceHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
}
