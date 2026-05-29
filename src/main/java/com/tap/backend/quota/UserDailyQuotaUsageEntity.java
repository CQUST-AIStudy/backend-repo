package com.tap.backend.quota;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "user_daily_quota_usage")
public class UserDailyQuotaUsageEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "usage_date", nullable = false)
  private LocalDate usageDate;

  @Column(name = "translation_chars", nullable = false)
  private long translationChars;

  @Column(name = "ai_requests", nullable = false)
  private long aiRequests;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void onCreate() {
    if (updatedAt == null) updatedAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public Long getUserId() {
    return userId;
  }

  public LocalDate getUsageDate() {
    return usageDate;
  }

  public long getTranslationChars() {
    return translationChars;
  }

  public long getAiRequests() {
    return aiRequests;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
