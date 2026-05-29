package com.tap.backend.domain.document;

import com.tap.backend.domain.upload.UploadFolderEntity;
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
@Table(name = "document")
public class DocumentEntity {
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

  @Column(name = "original_path", nullable = false, columnDefinition = "text")
  private String originalPath;

  @Column(name = "filename", nullable = false, length = 512)
  private String filename;

  @Column(name = "content_type", nullable = false, length = 128)
  private String contentType;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "sha256", nullable = false, length = 64)
  private String sha256;

  @Column(name = "language", length = 16)
  private String language;

  @Column(name = "extracted_text", columnDefinition = "text")
  private String extractedText;

  @Column(name = "extracted_text_key", columnDefinition = "text")
  private String extractedTextKey;

  @Column(name = "extracted_text_truncated", nullable = false)
  private boolean extractedTextTruncated;

  @Column(name = "object_key", nullable = false, columnDefinition = "text")
  private String objectKey;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) createdAt = Instant.now();
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

  public String getOriginalPath() {
    return originalPath;
  }

  public void setOriginalPath(String originalPath) {
    this.originalPath = originalPath;
  }

  public String getFilename() {
    return filename;
  }

  public void setFilename(String filename) {
    this.filename = filename;
  }

  public String getContentType() {
    return contentType;
  }

  public void setContentType(String contentType) {
    this.contentType = contentType;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public void setSizeBytes(long sizeBytes) {
    this.sizeBytes = sizeBytes;
  }

  public String getSha256() {
    return sha256;
  }

  public void setSha256(String sha256) {
    this.sha256 = sha256;
  }

  public String getLanguage() {
    return language;
  }

  public void setLanguage(String language) {
    this.language = language;
  }

  public String getExtractedText() {
    return extractedText;
  }

  public void setExtractedText(String extractedText) {
    this.extractedText = extractedText;
  }

  public String getExtractedTextKey() {
    return extractedTextKey;
  }

  public void setExtractedTextKey(String extractedTextKey) {
    this.extractedTextKey = extractedTextKey;
  }

  public boolean isExtractedTextTruncated() {
    return extractedTextTruncated;
  }

  public void setExtractedTextTruncated(boolean extractedTextTruncated) {
    this.extractedTextTruncated = extractedTextTruncated;
  }

  public String getObjectKey() {
    return objectKey;
  }

  public void setObjectKey(String objectKey) {
    this.objectKey = objectKey;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
