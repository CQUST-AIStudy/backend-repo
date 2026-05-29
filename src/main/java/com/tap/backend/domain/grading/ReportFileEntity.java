package com.tap.backend.domain.grading;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "report_file")
public class ReportFileEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private GradingTaskEntity task;

    @Column(name = "task_id", insertable = false, updatable = false)
    private Long taskId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id")
    private GradingSubmissionEntity submission;

    @Column(name = "submission_id", insertable = false, updatable = false)
    private Long submissionId;

    @Column(name = "file_type", nullable = false, length = 8)
    private String fileType;

    @Column(name = "object_key", nullable = false, columnDefinition = "text")
    private String objectKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = Instant.now(); }

    public Long getId() { return id; }
    public GradingTaskEntity getTask() { return task; }
    public void setTask(GradingTaskEntity task) { this.task = task; }
    public Long getTaskId() { return taskId; }
    public GradingSubmissionEntity getSubmission() { return submission; }
    public void setSubmission(GradingSubmissionEntity submission) { this.submission = submission; }
    public Long getSubmissionId() { return submissionId; }
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public Instant getCreatedAt() { return createdAt; }
}
