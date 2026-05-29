package com.tap.backend.domain.ziporganize;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "zip_organize_item")
public class ZipOrganizeItemEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "job_id", nullable = false)
  private ZipOrganizeJobEntity job;

  @Column(name = "job_id", insertable = false, updatable = false)
  private Long jobId;

  @Column(name = "original_path", nullable = false, columnDefinition = "text")
  private String originalPath;

  @Column(name = "filename", nullable = false, length = 512)
  private String filename;

  @Column(name = "content_type", length = 128)
  private String contentType;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "sha256", length = 64)
  private String sha256;

  @Column(name = "ext", length = 32)
  private String ext;

  @Column(name = "object_key", nullable = false, columnDefinition = "text")
  private String objectKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "extract_status", nullable = false, length = 16)
  private ZipOrganizeExtractStatus extractStatus;

  @Column(name = "extracted_text_preview", columnDefinition = "text")
  private String extractedTextPreview;

  @Column(name = "title_candidate", length = 512)
  private String titleCandidate;

  @Column(name = "doc_kind", length = 32)
  private String docKind;

  @Column(name = "topic", length = 256)
  private String topic;

  @Column(name = "keywords_json", columnDefinition = "json")
  @JdbcTypeCode(SqlTypes.JSON)
  private String keywordsJson;

  @Column(name = "summary_zh", columnDefinition = "text")
  private String summaryZh;

  @Column(name = "year_value", length = 16)
  private String yearValue;

  @Column(name = "confidence")
  private double confidence;

  @Column(name = "review_flag", nullable = false)
  private boolean reviewFlag;

  @Column(name = "review_reason", length = 256)
  private String reviewReason;

  @Column(name = "target_folder", length = 512)
  private String targetFolder;

  @Column(name = "new_filename", length = 512)
  private String newFilename;

  @Column(name = "duplicate_group_id", length = 64)
  private String duplicateGroupId;

  @Column(name = "final_path", length = 1024)
  private String finalPath;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
    if (extractStatus == null) extractStatus = ZipOrganizeExtractStatus.PENDING;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public Long getId() { return id; }
  public ZipOrganizeJobEntity getJob() { return job; }
  public void setJob(ZipOrganizeJobEntity job) { this.job = job; }
  public Long getJobId() { return jobId; }
  public String getOriginalPath() { return originalPath; }
  public void setOriginalPath(String originalPath) { this.originalPath = originalPath; }
  public String getFilename() { return filename; }
  public void setFilename(String filename) { this.filename = filename; }
  public String getContentType() { return contentType; }
  public void setContentType(String contentType) { this.contentType = contentType; }
  public long getSizeBytes() { return sizeBytes; }
  public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
  public String getSha256() { return sha256; }
  public void setSha256(String sha256) { this.sha256 = sha256; }
  public String getExt() { return ext; }
  public void setExt(String ext) { this.ext = ext; }
  public String getObjectKey() { return objectKey; }
  public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
  public ZipOrganizeExtractStatus getExtractStatus() { return extractStatus; }
  public void setExtractStatus(ZipOrganizeExtractStatus extractStatus) { this.extractStatus = extractStatus; }
  public String getExtractedTextPreview() { return extractedTextPreview; }
  public void setExtractedTextPreview(String extractedTextPreview) { this.extractedTextPreview = extractedTextPreview; }
  public String getTitleCandidate() { return titleCandidate; }
  public void setTitleCandidate(String titleCandidate) { this.titleCandidate = titleCandidate; }
  public String getDocKind() { return docKind; }
  public void setDocKind(String docKind) { this.docKind = docKind; }
  public String getTopic() { return topic; }
  public void setTopic(String topic) { this.topic = topic; }
  public String getKeywordsJson() { return keywordsJson; }
  public void setKeywordsJson(String keywordsJson) { this.keywordsJson = keywordsJson; }
  public String getSummaryZh() { return summaryZh; }
  public void setSummaryZh(String summaryZh) { this.summaryZh = summaryZh; }
  public String getYearValue() { return yearValue; }
  public void setYearValue(String yearValue) { this.yearValue = yearValue; }
  public double getConfidence() { return confidence; }
  public void setConfidence(double confidence) { this.confidence = confidence; }
  public boolean isReviewFlag() { return reviewFlag; }
  public void setReviewFlag(boolean reviewFlag) { this.reviewFlag = reviewFlag; }
  public String getReviewReason() { return reviewReason; }
  public void setReviewReason(String reviewReason) { this.reviewReason = reviewReason; }
  public String getTargetFolder() { return targetFolder; }
  public void setTargetFolder(String targetFolder) { this.targetFolder = targetFolder; }
  public String getNewFilename() { return newFilename; }
  public void setNewFilename(String newFilename) { this.newFilename = newFilename; }
  public String getDuplicateGroupId() { return duplicateGroupId; }
  public void setDuplicateGroupId(String duplicateGroupId) { this.duplicateGroupId = duplicateGroupId; }
  public String getFinalPath() { return finalPath; }
  public void setFinalPath(String finalPath) { this.finalPath = finalPath; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
