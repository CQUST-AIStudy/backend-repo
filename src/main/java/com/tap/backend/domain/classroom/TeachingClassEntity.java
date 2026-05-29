package com.tap.backend.domain.classroom;

import com.tap.backend.domain.user.UserEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "teaching_class")
public class TeachingClassEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "teacher_id", nullable = false)
    private UserEntity teacher;

    @Column(name = "teacher_id", insertable = false, updatable = false)
    private Long teacherId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "class_code", nullable = false, unique = true, length = 32)
    private String classCode;

    @Column(name = "join_password", nullable = false, length = 64)
    private String joinPassword;

    @Column(name = "grade", length = 16)
    private String grade;

    @Column(name = "course_name", length = 128)
    private String courseName;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "pta_keyword", length = 128)
    private String ptaKeyword;

    @Column(name = "sync_enabled", nullable = false)
    private Boolean syncEnabled = false;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "sync_status", length = 32)
    private String syncStatus = "IDLE";

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

    // --- getters & setters ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UserEntity getTeacher() { return teacher; }
    public void setTeacher(UserEntity teacher) { this.teacher = teacher; }

    public Long getTeacherId() { return teacherId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getClassCode() { return classCode; }
    public void setClassCode(String classCode) { this.classCode = classCode; }

    public String getJoinPassword() { return joinPassword; }
    public void setJoinPassword(String joinPassword) { this.joinPassword = joinPassword; }

    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }

    public String getCourseName() { return courseName; }
    public void setCourseName(String courseName) { this.courseName = courseName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getPtaKeyword() { return ptaKeyword; }
    public void setPtaKeyword(String ptaKeyword) { this.ptaKeyword = ptaKeyword; }

    public Boolean getSyncEnabled() { return syncEnabled; }
    public void setSyncEnabled(Boolean syncEnabled) { this.syncEnabled = syncEnabled; }

    public Instant getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(Instant lastSyncAt) { this.lastSyncAt = lastSyncAt; }

    public String getSyncStatus() { return syncStatus; }
    public void setSyncStatus(String syncStatus) { this.syncStatus = syncStatus; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
