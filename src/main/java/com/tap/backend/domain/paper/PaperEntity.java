package com.tap.backend.domain.paper;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "paper")
public class PaperEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "arxiv_id", nullable = false, unique = true, length = 256)
  private String arxivId;

  @Column(name = "title", nullable = false, columnDefinition = "text")
  private String title;

  @Column(name = "abstract_text", columnDefinition = "text")
  private String abstractText;

  @Column(name = "pdf_url", columnDefinition = "text")
  private String pdfUrl;

  @Column(name = "published_at")
  private Instant publishedAt;

  @Column(name = "updated_at")
  private Instant updatedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(name = "paper_author", joinColumns = @JoinColumn(name = "paper_id"))
  @Column(name = "author_name", nullable = false, length = 256)
  private List<String> authors = new ArrayList<>();

  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(name = "paper_category", joinColumns = @JoinColumn(name = "paper_id"))
  @Column(name = "category", nullable = false, length = 64)
  private List<String> categories = new ArrayList<>();

  @PrePersist
  void onCreate() {
    if (createdAt == null) createdAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public String getArxivId() {
    return arxivId;
  }

  public void setArxivId(String arxivId) {
    this.arxivId = arxivId;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getAbstractText() {
    return abstractText;
  }

  public void setAbstractText(String abstractText) {
    this.abstractText = abstractText;
  }

  public String getPdfUrl() {
    return pdfUrl;
  }

  public void setPdfUrl(String pdfUrl) {
    this.pdfUrl = pdfUrl;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public void setPublishedAt(Instant publishedAt) {
    this.publishedAt = publishedAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public List<String> getAuthors() {
    return authors;
  }

  public void setAuthors(List<String> authors) {
    this.authors = authors == null ? new ArrayList<>() : authors;
  }

  public List<String> getCategories() {
    return categories;
  }

  public void setCategories(List<String> categories) {
    this.categories = categories == null ? new ArrayList<>() : categories;
  }
}
