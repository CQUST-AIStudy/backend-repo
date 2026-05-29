package com.tap.backend.domain.library;

import com.tap.backend.domain.paper.PaperEntity;
import com.tap.backend.domain.user.UserEntity;
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
import java.time.Instant;

@Entity
@Table(name = "library_item")
public class LibraryItemEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private UserEntity user;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "paper_id", nullable = false)
  private PaperEntity paper;

  @Column(name = "saved_at", nullable = false)
  private Instant savedAt;

  @Column(name = "downloaded_at")
  private Instant downloadedAt;

  @Column(name = "note", columnDefinition = "text")
  private String note;

  @PrePersist
  void onCreate() {
    if (savedAt == null) savedAt = Instant.now();
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

  public PaperEntity getPaper() {
    return paper;
  }

  public void setPaper(PaperEntity paper) {
    this.paper = paper;
  }

  public Instant getSavedAt() {
    return savedAt;
  }

  public void setSavedAt(Instant savedAt) {
    this.savedAt = savedAt;
  }

  public Instant getDownloadedAt() {
    return downloadedAt;
  }

  public void setDownloadedAt(Instant downloadedAt) {
    this.downloadedAt = downloadedAt;
  }

  public String getNote() {
    return note;
  }

  public void setNote(String note) {
    this.note = note;
  }
}
