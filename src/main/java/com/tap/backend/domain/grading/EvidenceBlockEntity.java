package com.tap.backend.domain.grading;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "evidence_block")
public class EvidenceBlockEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private GradingSubmissionEntity submission;

    @Column(name = "submission_id", insertable = false, updatable = false)
    private Long submissionId;

    @Column(name = "evidence_id", nullable = false, length = 64, unique = true)
    private String evidenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private EvidenceKind kind;

    @Column(name = "page")
    private Integer page;

    @Column(name = "bbox_json", columnDefinition = "json")
    private String bboxJson;

    @Column(name = "content", columnDefinition = "text")
    private String content;

    @Column(name = "confidence", precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "image_key", columnDefinition = "text")
    private String imageKey;

    @Column(name = "metadata_json", columnDefinition = "json")
    private String metadataJson;

    @Column(name = "location_json", columnDefinition = "json")
    private String locationJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = Instant.now(); }

    public Long getId() { return id; }
    public GradingSubmissionEntity getSubmission() { return submission; }
    public void setSubmission(GradingSubmissionEntity submission) { this.submission = submission; }
    public Long getSubmissionId() { return submissionId; }
    public String getEvidenceId() { return evidenceId; }
    public void setEvidenceId(String evidenceId) { this.evidenceId = evidenceId; }
    public EvidenceKind getKind() { return kind; }
    public void setKind(EvidenceKind kind) { this.kind = kind; }
    public Integer getPage() { return page; }
    public void setPage(Integer page) { this.page = page; }
    public String getBboxJson() { return bboxJson; }
    public void setBboxJson(String bboxJson) { this.bboxJson = bboxJson; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public String getImageKey() { return imageKey; }
    public void setImageKey(String imageKey) { this.imageKey = imageKey; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public String getLocationJson() { return locationJson; }
    public void setLocationJson(String locationJson) { this.locationJson = locationJson; }
    public Instant getCreatedAt() { return createdAt; }
}
