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

    @Column(name = "pta_problem_set_id", length = 64)
    private String ptaProblemSetId;

    @Column(name = "pta_problem_set_name", length = 256)
    private String ptaProblemSetName;

    @Column(name = "pta_group_id", length = 64)
    private String ptaGroupId;

    @Column(name = "pta_group_name", length = 256)
    private String ptaGroupName;

    @Column(name = "pta_binding_verified_at")
    private Instant ptaBindingVerifiedAt;

    @Column(name = "pta_binding_verify_status", length = 32)
    private String ptaBindingVerifyStatus;

    @Column(name = "pta_binding_verify_message", length = 512)
    private String ptaBindingVerifyMessage;

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

    public String getPtaProblemSetId() { return ptaProblemSetId; }
    public void setPtaProblemSetId(String ptaProblemSetId) { this.ptaProblemSetId = ptaProblemSetId; }

    public String getPtaProblemSetName() { return ptaProblemSetName; }
    public void setPtaProblemSetName(String ptaProblemSetName) { this.ptaProblemSetName = ptaProblemSetName; }

    public String getPtaGroupId() { return ptaGroupId; }
    public void setPtaGroupId(String ptaGroupId) { this.ptaGroupId = ptaGroupId; }

    public String getPtaGroupName() { return ptaGroupName; }
    public void setPtaGroupName(String ptaGroupName) { this.ptaGroupName = ptaGroupName; }

    public Instant getPtaBindingVerifiedAt() { return ptaBindingVerifiedAt; }
    public void setPtaBindingVerifiedAt(Instant ptaBindingVerifiedAt) { this.ptaBindingVerifiedAt = ptaBindingVerifiedAt; }

    public String getPtaBindingVerifyStatus() { return ptaBindingVerifyStatus; }
    public void setPtaBindingVerifyStatus(String ptaBindingVerifyStatus) { this.ptaBindingVerifyStatus = ptaBindingVerifyStatus; }

    public String getPtaBindingVerifyMessage() { return ptaBindingVerifyMessage; }
    public void setPtaBindingVerifyMessage(String ptaBindingVerifyMessage) { this.ptaBindingVerifyMessage = ptaBindingVerifyMessage; }

    public Boolean getSyncEnabled() { return syncEnabled; }
    public void setSyncEnabled(Boolean syncEnabled) { this.syncEnabled = syncEnabled; }

    public Instant getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(Instant lastSyncAt) { this.lastSyncAt = lastSyncAt; }

    public String getSyncStatus() { return syncStatus; }
    public void setSyncStatus(String syncStatus) { this.syncStatus = syncStatus; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
