package com.tap.backend.domain.practice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "wrong_question_notebook")
public class WrongQuestionEntity {

  public enum SourceType { LEETCODE_PRACTICE, PTA_SYNCED }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "student_no", nullable = false, length = 64)
  private String studentNo;

  @Column(name = "problem_id", nullable = false)
  private Long problemId;

  @Column(name = "problem_title", nullable = false, length = 512)
  private String problemTitle;

  @Column(name = "problem_slug", length = 255)
  private String problemSlug;

  @Column(name = "difficulty", length = 16)
  private String difficulty;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 24)
  private SourceType sourceType;

  @Column(name = "first_wrong_at", nullable = false)
  private Instant firstWrongAt;

  @Column(name = "last_wrong_at", nullable = false)
  private Instant lastWrongAt;

  @Column(name = "total_wrong_count", nullable = false)
  private int totalWrongCount;

  @Column(name = "consecutive_ac_count", nullable = false)
  private int consecutiveAcCount;

  @Column(name = "last_attempt_at")
  private Instant lastAttemptAt;

  @Column(name = "last_judge_status", length = 64)
  private String lastJudgeStatus;

  @Column(name = "last_wrong_code", columnDefinition = "MEDIUMTEXT")
  private String lastWrongCode;

  @Column(name = "last_error_message", columnDefinition = "TEXT")
  private String lastErrorMessage;

  @Column(name = "error_category", length = 32)
  private String errorCategory;

  @Column(name = "is_resolved", nullable = false)
  private boolean resolved;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @Column(name = "tags_cached", length = 512)
  private String tagsCached;

  @Column(name = "notes", columnDefinition = "TEXT")
  private String notes;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (firstWrongAt == null) firstWrongAt = now;
    if (lastWrongAt == null) lastWrongAt = now;
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public Long getId() { return id; }

  public String getStudentNo() { return studentNo; }
  public void setStudentNo(String studentNo) { this.studentNo = studentNo; }

  public Long getProblemId() { return problemId; }
  public void setProblemId(Long problemId) { this.problemId = problemId; }

  public String getProblemTitle() { return problemTitle; }
  public void setProblemTitle(String problemTitle) { this.problemTitle = problemTitle; }

  public String getProblemSlug() { return problemSlug; }
  public void setProblemSlug(String problemSlug) { this.problemSlug = problemSlug; }

  public String getDifficulty() { return difficulty; }
  public void setDifficulty(String difficulty) { this.difficulty = difficulty; }

  public SourceType getSourceType() { return sourceType; }
  public void setSourceType(SourceType sourceType) { this.sourceType = sourceType; }

  public Instant getFirstWrongAt() { return firstWrongAt; }
  public void setFirstWrongAt(Instant firstWrongAt) { this.firstWrongAt = firstWrongAt; }

  public Instant getLastWrongAt() { return lastWrongAt; }
  public void setLastWrongAt(Instant lastWrongAt) { this.lastWrongAt = lastWrongAt; }

  public int getTotalWrongCount() { return totalWrongCount; }
  public void setTotalWrongCount(int totalWrongCount) { this.totalWrongCount = totalWrongCount; }

  public int getConsecutiveAcCount() { return consecutiveAcCount; }
  public void setConsecutiveAcCount(int consecutiveAcCount) { this.consecutiveAcCount = consecutiveAcCount; }

  public Instant getLastAttemptAt() { return lastAttemptAt; }
  public void setLastAttemptAt(Instant lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }

  public String getLastJudgeStatus() { return lastJudgeStatus; }
  public void setLastJudgeStatus(String lastJudgeStatus) { this.lastJudgeStatus = lastJudgeStatus; }

  public String getLastWrongCode() { return lastWrongCode; }
  public void setLastWrongCode(String lastWrongCode) { this.lastWrongCode = lastWrongCode; }

  public String getLastErrorMessage() { return lastErrorMessage; }
  public void setLastErrorMessage(String lastErrorMessage) { this.lastErrorMessage = lastErrorMessage; }

  public String getErrorCategory() { return errorCategory; }
  public void setErrorCategory(String errorCategory) { this.errorCategory = errorCategory; }

  public boolean isResolved() { return resolved; }
  public void setResolved(boolean resolved) { this.resolved = resolved; }

  public Instant getResolvedAt() { return resolvedAt; }
  public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }

  public String getTagsCached() { return tagsCached; }
  public void setTagsCached(String tagsCached) { this.tagsCached = tagsCached; }

  public String getNotes() { return notes; }
  public void setNotes(String notes) { this.notes = notes; }

  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
