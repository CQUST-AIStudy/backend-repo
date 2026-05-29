package com.tap.backend.domain.classroom;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "class_student")
public class ClassStudentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "class_id", nullable = false)
    private TeachingClassEntity teachingClass;

    @Column(name = "class_id", insertable = false, updatable = false)
    private Long classId;

    @Column(name = "student_name", nullable = false, length = 64)
    private String studentName;

    @Column(name = "student_num", length = 32)
    private String studentNum;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @PrePersist
    void onCreate() {
        if (joinedAt == null) joinedAt = Instant.now();
    }

    // --- getters & setters ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public TeachingClassEntity getTeachingClass() { return teachingClass; }
    public void setTeachingClass(TeachingClassEntity teachingClass) { this.teachingClass = teachingClass; }

    public Long getClassId() { return classId; }

    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }

    public String getStudentNum() { return studentNum; }
    public void setStudentNum(String studentNum) { this.studentNum = studentNum; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Instant getJoinedAt() { return joinedAt; }
}
