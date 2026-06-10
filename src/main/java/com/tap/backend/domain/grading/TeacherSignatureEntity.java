package com.tap.backend.domain.grading;

import com.tap.backend.domain.user.UserEntity;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "teacher_signature", uniqueConstraints = {
        @UniqueConstraint(name = "uk_teacher_signature", columnNames = {"teacher_id", "signature"})
})
public class TeacherSignatureEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "teacher_id", nullable = false)
    private UserEntity teacher;

    @Column(name = "teacher_id", insertable = false, updatable = false)
    private Long teacherId;

    @Column(name = "signature", nullable = false, length = 64)
    private String signature;

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
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
