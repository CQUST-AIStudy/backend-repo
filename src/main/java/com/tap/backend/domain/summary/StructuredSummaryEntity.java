package com.tap.backend.domain.summary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "structured_summary",
    uniqueConstraints = @UniqueConstraint(name = "uq_structured_summary_scope_provider_model",
        columnNames = {"scope_type", "scope_key", "provider", "model"})
)
public class StructuredSummaryEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "scope_type", nullable = false, length = 16)
  private String scopeType;

  @Column(name = "scope_key", nullable = false, length = 256)
  private String scopeKey;

  @Column(name = "content_hash", nullable = false, length = 64)
  private String contentHash;

  @Column(name = "provider", nullable = false, length = 32)
  private String provider;

  @Column(name = "model", nullable = false, length = 64)
  private String model;

  @Column(name = "summary_json", nullable = false, columnDefinition = "json")
  @JdbcTypeCode(SqlTypes.JSON)
  private String summaryJson;

  @Column(name = "markdown", nullable = false, columnDefinition = "text")
  private String markdown;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
    if (model == null) model = "";
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
    if (model == null) model = "";
  }

  public Long getId() {
    return id;
  }

  public String getScopeType() {
    return scopeType;
  }

  public void setScopeType(String scopeType) {
    this.scopeType = scopeType;
  }

  public String getScopeKey() {
    return scopeKey;
  }

  public void setScopeKey(String scopeKey) {
    this.scopeKey = scopeKey;
  }

  public String getContentHash() {
    return contentHash;
  }

  public void setContentHash(String contentHash) {
    this.contentHash = contentHash;
  }

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public String getSummaryJson() {
    return summaryJson;
  }

  public void setSummaryJson(String summaryJson) {
    this.summaryJson = summaryJson;
  }

  public String getMarkdown() {
    return markdown;
  }

  public void setMarkdown(String markdown) {
    this.markdown = markdown;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
