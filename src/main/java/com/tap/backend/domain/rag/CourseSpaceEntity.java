package com.tap.backend.domain.rag;

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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "course_space")
public class CourseSpaceEntity {

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

    @Column(name = "term", length = 32)
    private String term;

    @Column(name = "course_name", length = 128)
    private String courseName;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "default_mode", nullable = false, length = 8)
    private String defaultMode = "strict";

    @Column(name = "allow_web_search", nullable = false)
    private Boolean allowWebSearch = false;

    @Column(name = "require_citation", nullable = false)
    private Boolean requireCitation = true;

    @Column(name = "doc_visibility", nullable = false, length = 16)
    private String docVisibility = "private";

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public UserEntity getTeacher() {
        return teacher;
    }

    public void setTeacher(UserEntity teacher) {
        this.teacher = teacher;
    }

    public Long getTeacherId() {
        return teacherId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTerm() {
        return term;
    }

    public void setTerm(String term) {
        this.term = term;
    }

    public String getCourseName() {
        return courseName;
    }

    public void setCourseName(String courseName) {
        this.courseName = courseName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getDefaultMode() {
        return defaultMode;
    }

    public void setDefaultMode(String defaultMode) {
        this.defaultMode = defaultMode;
    }

    public Boolean getAllowWebSearch() {
        return allowWebSearch;
    }

    public void setAllowWebSearch(Boolean allowWebSearch) {
        this.allowWebSearch = allowWebSearch;
    }

    public Boolean getRequireCitation() {
        return requireCitation;
    }

    public void setRequireCitation(Boolean requireCitation) {
        this.requireCitation = requireCitation;
    }

    public String getDocVisibility() {
        return docVisibility;
    }

    public void setDocVisibility(String docVisibility) {
        this.docVisibility = docVisibility;
    }
}
