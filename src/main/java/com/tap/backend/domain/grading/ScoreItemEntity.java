package com.tap.backend.domain.grading;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "score_item")
public class ScoreItemEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private GradingSubmissionEntity submission;

    @Column(name = "submission_id", insertable = false, updatable = false)
    private Long submissionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dimension_id", nullable = false)
    private RubricDimensionEntity dimension;

    @Column(name = "dimension_id", insertable = false, updatable = false)
    private Long dimensionId;

    @Column(name = "score", precision = 5, scale = 1)
    private BigDecimal score;

    @Column(name = "max_score", nullable = false, precision = 5, scale = 1)
    private BigDecimal maxScore;

    @Column(name = "weight", nullable = false)
    private Integer weight;

    @Column(name = "comment", columnDefinition = "text")
    private String comment;

    @Column(name = "evidence_ids_json", columnDefinition = "json")
    private String evidenceIdsJson;

    @Column(name = "annotations_json", columnDefinition = "json")
    private String annotationsJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ScoreItemStatus status = ScoreItemStatus.PENDING;

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
    public GradingSubmissionEntity getSubmission() { return submission; }
    public void setSubmission(GradingSubmissionEntity submission) { this.submission = submission; }
    public Long getSubmissionId() { return submissionId; }
    public RubricDimensionEntity getDimension() { return dimension; }
    public void setDimension(RubricDimensionEntity dimension) { this.dimension = dimension; }
    public Long getDimensionId() { return dimensionId; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public BigDecimal getMaxScore() { return maxScore; }
    public void setMaxScore(BigDecimal maxScore) { this.maxScore = maxScore; }
    public Integer getWeight() { return weight; }
    public void setWeight(Integer weight) { this.weight = weight; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public String getEvidenceIdsJson() { return evidenceIdsJson; }
    public void setEvidenceIdsJson(String evidenceIdsJson) { this.evidenceIdsJson = evidenceIdsJson; }
    public String getAnnotationsJson() { return annotationsJson; }
    public void setAnnotationsJson(String annotationsJson) { this.annotationsJson = annotationsJson; }
    public ScoreItemStatus getStatus() { return status; }
    public void setStatus(ScoreItemStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
