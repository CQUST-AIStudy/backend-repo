package com.tap.backend.domain.grading;

import com.tap.backend.domain.user.UserEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "grading_batch")
public class GradingBatchEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "teacher_id", nullable = false)
    private UserEntity teacher;

    @Column(name = "teacher_id", insertable = false, updatable = false)
    private Long teacherId;

    @Column(name = "display_code", length = 16)
    private String displayCode;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public UserEntity getTeacher() { return teacher; }
    public void setTeacher(UserEntity teacher) { this.teacher = teacher; }
    public Long getTeacherId() { return teacherId; }
    public String getDisplayCode() { return displayCode; }
    public void setDisplayCode(String displayCode) { this.displayCode = displayCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
