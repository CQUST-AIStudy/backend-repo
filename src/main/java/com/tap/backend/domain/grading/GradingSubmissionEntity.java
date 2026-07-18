package com.tap.backend.domain.grading;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "grading_submission")
public class GradingSubmissionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private GradingTaskEntity task;

    @Column(name = "task_id", insertable = false, updatable = false)
    private Long taskId;

    @Column(name = "student_id")
    private Long studentId;

    @Column(name = "student_name", length = 128)
    private String studentName;

    @Column(name = "class_name", length = 256)
    private String className;

    @Column(name = "student_no", length = 64)
    private String studentNo;

    @Column(name = "pdf_object_key", nullable = false, columnDefinition = "text")
    private String pdfObjectKey;

    @Column(name = "original_filename", length = 512)
    private String originalFilename;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private SubmissionStatus status = SubmissionStatus.PENDING;

    @Column(name = "total_score", precision = 6, scale = 2)
    private BigDecimal totalScore;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "final_review_comment", columnDefinition = "text")
    private String finalReviewComment;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", nullable = false, length = 24)
    private SubmissionMatchStatus matchStatus = SubmissionMatchStatus.UNMATCHED;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by")
    private Long publishedBy;

    @Column(name = "error_demonstrations_json", columnDefinition = "longtext")
    private String errorDemonstrationsJson;

    @Column(name = "cover_objectives_json", columnDefinition = "longtext")
    private String coverObjectivesJson;

    @Column(name = "code_analysis_json", columnDefinition = "longtext")
    private String codeAnalysisJson;

    @Column(name = "improvement_plan_json", columnDefinition = "longtext")
    private String improvementPlanJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "annotated_report_status", nullable = false, length = 24)
    private AnnotatedReportStatus annotatedReportStatus = AnnotatedReportStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_demonstrations_status", nullable = false, length = 24)
    private ErrorDemonstrationStatus errorDemonstrationsStatus = ErrorDemonstrationStatus.PENDING;

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

    public Long getId() { return id; }
    public GradingTaskEntity getTask() { return task; }
    public void setTask(GradingTaskEntity task) { this.task = task; }
    public Long getTaskId() { return taskId; }
    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }
    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }
    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public String getStudentNo() { return studentNo; }
    public void setStudentNo(String studentNo) { this.studentNo = studentNo; }
    public String getPdfObjectKey() { return pdfObjectKey; }
    public void setPdfObjectKey(String pdfObjectKey) { this.pdfObjectKey = pdfObjectKey; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public SubmissionStatus getStatus() { return status; }
    public void setStatus(SubmissionStatus status) { this.status = status; }
    public BigDecimal getTotalScore() { return totalScore; }
    public void setTotalScore(BigDecimal totalScore) { this.totalScore = totalScore; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getFinalReviewComment() { return finalReviewComment; }
    public void setFinalReviewComment(String finalReviewComment) { this.finalReviewComment = finalReviewComment; }
    public SubmissionMatchStatus getMatchStatus() { return matchStatus; }
    public void setMatchStatus(SubmissionMatchStatus matchStatus) { this.matchStatus = matchStatus; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public Long getPublishedBy() { return publishedBy; }
    public void setPublishedBy(Long publishedBy) { this.publishedBy = publishedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getErrorDemonstrationsJson() { return errorDemonstrationsJson; }
    public void setErrorDemonstrationsJson(String errorDemonstrationsJson) { this.errorDemonstrationsJson = errorDemonstrationsJson; }
    public String getCoverObjectivesJson() { return coverObjectivesJson; }
    public void setCoverObjectivesJson(String coverObjectivesJson) { this.coverObjectivesJson = coverObjectivesJson; }
    public String getCodeAnalysisJson() { return codeAnalysisJson; }
    public void setCodeAnalysisJson(String codeAnalysisJson) { this.codeAnalysisJson = codeAnalysisJson; }
    public String getImprovementPlanJson() { return improvementPlanJson; }
    public void setImprovementPlanJson(String improvementPlanJson) { this.improvementPlanJson = improvementPlanJson; }
    public AnnotatedReportStatus getAnnotatedReportStatus() { return annotatedReportStatus; }
    public void setAnnotatedReportStatus(AnnotatedReportStatus annotatedReportStatus) { this.annotatedReportStatus = annotatedReportStatus; }
    public ErrorDemonstrationStatus getErrorDemonstrationsStatus() { return errorDemonstrationsStatus; }
    public void setErrorDemonstrationsStatus(ErrorDemonstrationStatus errorDemonstrationsStatus) { this.errorDemonstrationsStatus = errorDemonstrationsStatus; }
}
