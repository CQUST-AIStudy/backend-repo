package com.tap.backend.domain.agent;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agent_organize_plan")
public class AgentOrganizePlanEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private AgentJobEntity job;

    @Column(name = "job_id", insertable = false, updatable = false)
    private Long jobId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_file_id", nullable = false)
    private AgentJobFileEntity jobFile;

    @Column(name = "job_file_id", insertable = false, updatable = false)
    private Long jobFileId;

    @Column(name = "source_object_key", nullable = false, columnDefinition = "text")
    private String sourceObjectKey;

    @Column(name = "target_object_key", nullable = false, columnDefinition = "text")
    private String targetObjectKey;

    @Column(name = "new_filename", nullable = false, length = 512)
    private String newFilename;

    @Column(name = "target_folder", length = 512)
    private String targetFolder;

    @Column(name = "doc_kind", length = 32)
    private String docKind;

    @Column(name = "topic", length = 256)
    private String topic;

    @Column(name = "confidence")
    private double confidence;

    @Column(name = "decision_source", length = 16)
    private String decisionSource = "ai";

    @Column(name = "review_flag", nullable = false)
    private boolean reviewFlag;

    @Column(name = "review_reason", length = 256)
    private String reviewReason;

    @Column(name = "duplicate_group_id", length = 64)
    private String duplicateGroupId;

    @Column(name = "conflict_resolved", nullable = false)
    private boolean conflictResolved;

    @Column(name = "applied", nullable = false)
    private boolean applied;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist void onCreate() { if (createdAt == null) createdAt = Instant.now(); }

    // Getters & Setters
    public Long getId() { return id; }
    public AgentJobEntity getJob() { return job; }
    public void setJob(AgentJobEntity j) { this.job = j; }
    public Long getJobId() { return jobId; }
    public AgentJobFileEntity getJobFile() { return jobFile; }
    public void setJobFile(AgentJobFileEntity jf) { this.jobFile = jf; }
    public Long getJobFileId() { return jobFileId; }
    public String getSourceObjectKey() { return sourceObjectKey; }
    public void setSourceObjectKey(String s) { this.sourceObjectKey = s; }
    public String getTargetObjectKey() { return targetObjectKey; }
    public void setTargetObjectKey(String t) { this.targetObjectKey = t; }
    public String getNewFilename() { return newFilename; }
    public void setNewFilename(String n) { this.newFilename = n; }
    public String getTargetFolder() { return targetFolder; }
    public void setTargetFolder(String f) { this.targetFolder = f; }
    public String getDocKind() { return docKind; }
    public void setDocKind(String k) { this.docKind = k; }
    public String getTopic() { return topic; }
    public void setTopic(String t) { this.topic = t; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double c) { this.confidence = c; }
    public String getDecisionSource() { return decisionSource; }
    public void setDecisionSource(String d) { this.decisionSource = d; }
    public boolean isReviewFlag() { return reviewFlag; }
    public void setReviewFlag(boolean r) { this.reviewFlag = r; }
    public String getReviewReason() { return reviewReason; }
    public void setReviewReason(String r) { this.reviewReason = r; }
    public String getDuplicateGroupId() { return duplicateGroupId; }
    public void setDuplicateGroupId(String d) { this.duplicateGroupId = d; }
    public boolean isConflictResolved() { return conflictResolved; }
    public void setConflictResolved(boolean c) { this.conflictResolved = c; }
    public boolean isApplied() { return applied; }
    public void setApplied(boolean a) { this.applied = a; }
}
