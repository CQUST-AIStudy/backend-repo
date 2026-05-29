package com.tap.backend.domain.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "agent_result")
public class AgentResultEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "job_id", nullable = false, unique = true)
  private AgentJobEntity job;

  @Column(name = "topic", length = 256)
  private String topic;

  @Column(name = "tags_json", columnDefinition = "json")
  @JdbcTypeCode(SqlTypes.JSON)
  private String tagsJson;

  @Column(name = "summary", columnDefinition = "text")
  private String summary;

  @Column(name = "translation_link", columnDefinition = "text")
  private String translationLink;

  @Column(name = "result_json", columnDefinition = "json")
  @JdbcTypeCode(SqlTypes.JSON)
  private String resultJson;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) createdAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public AgentJobEntity getJob() {
    return job;
  }

  public void setJob(AgentJobEntity job) {
    this.job = job;
  }

  public String getTopic() {
    return topic;
  }

  public void setTopic(String topic) {
    this.topic = topic;
  }

  public String getTagsJson() {
    return tagsJson;
  }

  public void setTagsJson(String tagsJson) {
    this.tagsJson = tagsJson;
  }

  public String getSummary() {
    return summary;
  }

  public void setSummary(String summary) {
    this.summary = summary;
  }

  public String getTranslationLink() {
    return translationLink;
  }

  public void setTranslationLink(String translationLink) {
    this.translationLink = translationLink;
  }

  public String getResultJson() {
    return resultJson;
  }

  public void setResultJson(String resultJson) {
    this.resultJson = resultJson;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
