package com.tap.backend.domain.grading;

import com.tap.backend.domain.user.UserEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "grading_task")
public class GradingTaskEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "teacher_id", nullable = false)
    private UserEntity teacher;

    @Column(name = "teacher_id", insertable = false, updatable = false)
    private Long teacherId;

    @Column(name = "experiment_id")
    private Long experimentId;

    @Column(name = "assignment_offering_id")
    private Long assignmentOfferingId;

    @Column(name = "class_id")
    private Long classId;

    @Column(name = "teacher_signature", length = 64)
    private String teacherSignature;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rubric_id", nullable = false)
    private GradingRubricEntity rubric;

    @Column(name = "rubric_id", insertable = false, updatable = false)
    private Long rubricId;

    @Column(name = "score_range_min", precision = 5, scale = 1)
    private java.math.BigDecimal scoreRangeMin;

    @Column(name = "score_range_max", precision = 5, scale = 1)
    private java.math.BigDecimal scoreRangeMax;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private GradingTaskStatus status = GradingTaskStatus.PENDING;

    @Column(name = "total_count", nullable = false)
    private int totalCount = 0;

    @Column(name = "completed_count", nullable = false)
    private int completedCount = 0;

    @Column(name = "failed_count", nullable = false)
    private int failedCount = 0;

    @Column(name = "display_code", length = 16)
    private String displayCode;

    @Column(name = "batch_review_json", columnDefinition = "json")
    private String batchReviewJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "batch_review_status", nullable = false, length = 24)
    private BatchReviewStatus batchReviewStatus = BatchReviewStatus.PENDING;

    @Column(name = "batch_review_prompt", columnDefinition = "text")
    private String batchReviewPrompt;

    @Column(name = "batch_review_model", length = 128)
    private String batchReviewModel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private GradingBatchEntity batch;

    @Column(name = "batch_id", insertable = false, updatable = false)
    private Long batchId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public enum BatchReviewStatus {
        PENDING, GENERATING, COMPLETED, FAILED
    }

    public Long getId() { return id; }
    public UserEntity getTeacher() { return teacher; }
    public void setTeacher(UserEntity teacher) { this.teacher = teacher; }
    public Long getTeacherId() { return teacherId; }
    public Long getExperimentId() { return experimentId; }
    public void setExperimentId(Long experimentId) { this.experimentId = experimentId; }
    public Long getAssignmentOfferingId() { return assignmentOfferingId; }
    public void setAssignmentOfferingId(Long assignmentOfferingId) { this.assignmentOfferingId = assignmentOfferingId; }
    public Long getClassId() { return classId; }
    public void setClassId(Long classId) { this.classId = classId; }
    public String getTeacherSignature() { return teacherSignature; }
    public void setTeacherSignature(String teacherSignature) { this.teacherSignature = teacherSignature; }
    public GradingRubricEntity getRubric() { return rubric; }
    public void setRubric(GradingRubricEntity rubric) { this.rubric = rubric; }
    public Long getRubricId() { return rubricId; }
    public java.math.BigDecimal getScoreRangeMin() { return scoreRangeMin; }
    public void setScoreRangeMin(java.math.BigDecimal scoreRangeMin) { this.scoreRangeMin = scoreRangeMin; }
    public java.math.BigDecimal getScoreRangeMax() { return scoreRangeMax; }
    public void setScoreRangeMax(java.math.BigDecimal scoreRangeMax) { this.scoreRangeMax = scoreRangeMax; }
    public GradingTaskStatus getStatus() { return status; }
    public void setStatus(GradingTaskStatus status) { this.status = status; }
    public int getTotalCount() { return totalCount; }
    public void setTotalCount(int totalCount) { this.totalCount = totalCount; }
    public int getCompletedCount() { return completedCount; }
    public void setCompletedCount(int completedCount) { this.completedCount = completedCount; }
    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int failedCount) { this.failedCount = failedCount; }
    public String getDisplayCode() { return displayCode; }
    public void setDisplayCode(String displayCode) { this.displayCode = displayCode; }
    public String getBatchReviewJson() { return batchReviewJson; }
    public void setBatchReviewJson(String batchReviewJson) { this.batchReviewJson = batchReviewJson; }
    public BatchReviewStatus getBatchReviewStatus() { return batchReviewStatus; }
    public void setBatchReviewStatus(BatchReviewStatus batchReviewStatus) { this.batchReviewStatus = batchReviewStatus; }
    public String getBatchReviewPrompt() { return batchReviewPrompt; }
    public void setBatchReviewPrompt(String batchReviewPrompt) { this.batchReviewPrompt = batchReviewPrompt; }
    public String getBatchReviewModel() { return batchReviewModel; }
    public void setBatchReviewModel(String batchReviewModel) { this.batchReviewModel = batchReviewModel; }
    public GradingBatchEntity getBatch() { return batch; }
    public void setBatch(GradingBatchEntity batch) { this.batch = batch; }
    public Long getBatchId() { return batchId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
