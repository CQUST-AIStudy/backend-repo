package com.tap.backend.domain.grading;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "grading_trace")
public class GradingTraceEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private GradingSubmissionEntity submission;

    @Column(name = "submission_id", insertable = false, updatable = false)
    private Long submissionId;

    @Column(name = "step", nullable = false, length = 64)
    private String step;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "model_used", length = 64)
    private String modelUsed;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "metadata_json", columnDefinition = "json")
    private String metadataJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = Instant.now(); }

    public Long getId() { return id; }
    public GradingSubmissionEntity getSubmission() { return submission; }
    public void setSubmission(GradingSubmissionEntity submission) { this.submission = submission; }
    public Long getSubmissionId() { return submissionId; }
    public String getStep() { return step; }
    public void setStep(String step) { this.step = step; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }
    public Integer getInputTokens() { return inputTokens; }
    public void setInputTokens(Integer inputTokens) { this.inputTokens = inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Integer outputTokens) { this.outputTokens = outputTokens; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public Instant getCreatedAt() { return createdAt; }
}
