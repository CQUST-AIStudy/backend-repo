package com.tap.backend.domain.practice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "wrong_question_attempt_log")
public class WrongQuestionAttemptLogEntity {

  public enum AttemptLogSource { NOTEBOOK_PRACTICE, EXTERNAL_PRACTICE }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "notebook_id", nullable = false)
  private Long notebookId;

  @Column(name = "attempt_at", nullable = false)
  private Instant attemptAt;

  @Column(name = "judge_status", nullable = false, length = 64)
  private String judgeStatus;

  @Column(name = "was_ac", nullable = false)
  private boolean wasAc;

  @Column(name = "code_snippet", columnDefinition = "MEDIUMTEXT")
  private String codeSnippet;

  @Column(name = "runtime_ms")
  private Integer runtimeMs;

  @Column(name = "memory_kb")
  private Integer memoryKb;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 24)
  private AttemptLogSource source;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (attemptAt == null) attemptAt = now;
    if (createdAt == null) createdAt = now;
  }

  public Long getId() { return id; }

  public Long getNotebookId() { return notebookId; }
  public void setNotebookId(Long notebookId) { this.notebookId = notebookId; }

  public Instant getAttemptAt() { return attemptAt; }
  public void setAttemptAt(Instant attemptAt) { this.attemptAt = attemptAt; }

  public String getJudgeStatus() { return judgeStatus; }
  public void setJudgeStatus(String judgeStatus) { this.judgeStatus = judgeStatus; }

  public boolean isWasAc() { return wasAc; }
  public void setWasAc(boolean wasAc) { this.wasAc = wasAc; }

  public String getCodeSnippet() { return codeSnippet; }
  public void setCodeSnippet(String codeSnippet) { this.codeSnippet = codeSnippet; }

  public Integer getRuntimeMs() { return runtimeMs; }
  public void setRuntimeMs(Integer runtimeMs) { this.runtimeMs = runtimeMs; }

  public Integer getMemoryKb() { return memoryKb; }
  public void setMemoryKb(Integer memoryKb) { this.memoryKb = memoryKb; }

  public AttemptLogSource getSource() { return source; }
  public void setSource(AttemptLogSource source) { this.source = source; }

  public Instant getCreatedAt() { return createdAt; }
}
