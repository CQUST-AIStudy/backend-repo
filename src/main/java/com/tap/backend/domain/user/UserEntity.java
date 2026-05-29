package com.tap.backend.domain.user;

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
@Table(name = "tap_user")
public class UserEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "username", nullable = false, unique = true, length = 64)
  private String username;

  @Column(name = "display_name", length = 128)
  private String displayName;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 16)
  private UserRole role;

  @Column(name = "password_hash", length = 255)
  private String passwordHash;

  @Column(name = "pta_username", length = 128)
  private String ptaUsername;

  @Column(name = "pta_password_ciphertext", length = 1024)
  private String ptaPasswordCiphertext;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
    if (role == null) role = UserRole.TEACHER;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
    if (role == null) role = UserRole.TEACHER;
  }

  public Long getId() {
    return id;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public UserRole getRole() {
    return role;
  }

  public void setRole(UserRole role) {
    this.role = role;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public String getPtaUsername() {
    return ptaUsername;
  }

  public void setPtaUsername(String ptaUsername) {
    this.ptaUsername = ptaUsername;
  }

  public String getPtaPasswordCiphertext() {
    return ptaPasswordCiphertext;
  }

  public void setPtaPasswordCiphertext(String ptaPasswordCiphertext) {
    this.ptaPasswordCiphertext = ptaPasswordCiphertext;
  }
}
