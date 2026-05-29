package com.tap.backend.domain.agent;

import com.tap.backend.domain.upload.UploadFolderEntity;
import com.tap.backend.domain.user.UserEntity;
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
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "agent_job")
public class AgentJobEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private UserEntity user;

  @Column(name = "user_id", insertable = false, updatable = false)
  private Long userId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "upload_folder_id", nullable = false)
  private UploadFolderEntity uploadFolder;

  @Column(name = "upload_folder_id", insertable = false, updatable = false)
  private Long uploadFolderId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private AgentJobStatus status;

  @Column(name = "progress", nullable = false)
  private int progress;

  @Column(name = "error_message", columnDefinition = "text")
  private String errorMessage;

  @Column(name = "retry_count", nullable = false)
  private int retryCount;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "current_step", length = 32)
  private String currentStep;

  @Column(name = "step_detail", columnDefinition = "text")
  private String stepDetail;

  @Column(name = "organized_prefix", columnDefinition = "text")
  private String organizedPrefix;

  @Column(name = "zip_object_key", columnDefinition = "text")
  private String zipObjectKey;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
    if (status == null) status = AgentJobStatus.PENDING;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public UserEntity getUser() {
    return user;
  }

  public void setUser(UserEntity user) {
    this.user = user;
  }

  public Long getUserId() {
    return userId;
  }

  public UploadFolderEntity getUploadFolder() {
    return uploadFolder;
  }

  public void setUploadFolder(UploadFolderEntity uploadFolder) {
    this.uploadFolder = uploadFolder;
  }

  public Long getUploadFolderId() {
    return uploadFolderId;
  }

  public AgentJobStatus getStatus() {
    return status;
  }

  public void setStatus(AgentJobStatus status) {
    this.status = status;
  }

  public int getProgress() {
    return progress;
  }

  public void setProgress(int progress) {
    this.progress = progress;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public int getRetryCount() {
    return retryCount;
  }

  public void setRetryCount(int retryCount) {
    this.retryCount = retryCount;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  public Instant getFinishedAt() {
    return finishedAt;
  }

  public void setFinishedAt(Instant finishedAt) {
    this.finishedAt = finishedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getVersion() {
    return version;
  }

  public String getCurrentStep() { return currentStep; }
  public void setCurrentStep(String currentStep) { this.currentStep = currentStep; }

  public String getStepDetail() { return stepDetail; }
  public void setStepDetail(String stepDetail) { this.stepDetail = stepDetail; }

  public String getOrganizedPrefix() { return organizedPrefix; }
  public void setOrganizedPrefix(String organizedPrefix) { this.organizedPrefix = organizedPrefix; }

  public String getZipObjectKey() { return zipObjectKey; }
  public void setZipObjectKey(String zipObjectKey) { this.zipObjectKey = zipObjectKey; }
}
