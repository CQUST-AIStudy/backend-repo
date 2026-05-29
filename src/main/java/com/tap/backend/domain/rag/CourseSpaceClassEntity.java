package com.tap.backend.domain.rag;

import com.tap.backend.domain.classroom.TeachingClassEntity;
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
@Table(name = "course_space_class")
public class CourseSpaceClassEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_space_id", nullable = false)
    private CourseSpaceEntity courseSpace;

    @Column(name = "course_space_id", insertable = false, updatable = false)
    private Long courseSpaceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "class_id", nullable = false)
    private TeachingClassEntity teachingClass;

    @Column(name = "class_id", insertable = false, updatable = false)
    private Long classId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public CourseSpaceEntity getCourseSpace() {
        return courseSpace;
    }

    public void setCourseSpace(CourseSpaceEntity courseSpace) {
        this.courseSpace = courseSpace;
    }

    public Long getCourseSpaceId() {
        return courseSpaceId;
    }

    public TeachingClassEntity getTeachingClass() {
        return teachingClass;
    }

    public void setTeachingClass(TeachingClassEntity teachingClass) {
        this.teachingClass = teachingClass;
    }

    public Long getClassId() {
        return classId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
