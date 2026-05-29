package com.tap.backend.domain.translation;

import com.tap.backend.domain.document.DocumentEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
    name = "translation_segment",
    uniqueConstraints = @UniqueConstraint(name = "uq_translation_segment_doc_lang_idx",
        columnNames = {"document_id", "target_lang", "segment_index"})
)
public class TranslationSegmentEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "document_id", nullable = false)
  private DocumentEntity document;

  @Column(name = "document_id", insertable = false, updatable = false)
  private Long documentId;

  @Column(name = "target_lang", nullable = false, length = 16)
  private String targetLang;

  @Column(name = "segment_index", nullable = false)
  private int segmentIndex;

  @Column(name = "source_text", nullable = false, columnDefinition = "text")
  private String sourceText;

  @Column(name = "target_text", nullable = false, columnDefinition = "text")
  private String targetText;

  @Column(name = "provider", nullable = false, length = 32)
  private String provider;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) createdAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public DocumentEntity getDocument() {
    return document;
  }

  public void setDocument(DocumentEntity document) {
    this.document = document;
  }

  public Long getDocumentId() {
    return documentId;
  }

  public String getTargetLang() {
    return targetLang;
  }

  public void setTargetLang(String targetLang) {
    this.targetLang = targetLang;
  }

  public int getSegmentIndex() {
    return segmentIndex;
  }

  public void setSegmentIndex(int segmentIndex) {
    this.segmentIndex = segmentIndex;
  }

  public String getSourceText() {
    return sourceText;
  }

  public void setSourceText(String sourceText) {
    this.sourceText = sourceText;
  }

  public String getTargetText() {
    return targetText;
  }

  public void setTargetText(String targetText) {
    this.targetText = targetText;
  }

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
