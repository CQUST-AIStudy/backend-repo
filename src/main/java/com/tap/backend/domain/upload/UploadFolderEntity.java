package com.tap.backend.domain.upload;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "upload_folder")
public class UploadFolderEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private UserEntity user;

  @Column(name = "user_id", insertable = false, updatable = false)
  private Long userId;

  @Column(name = "folder_name", nullable = false, length = 256)
  private String folderName;

  @Column(name = "original_structure_json", columnDefinition = "json")
  @JdbcTypeCode(SqlTypes.JSON)
  private String originalStructureJson;

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

  public String getFolderName() {
    return folderName;
  }

  public void setFolderName(String folderName) {
    this.folderName = folderName;
  }

  public String getOriginalStructureJson() {
    return originalStructureJson;
  }

  public void setOriginalStructureJson(String originalStructureJson) {
    this.originalStructureJson = originalStructureJson;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
