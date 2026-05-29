package com.tap.backend.domain.grading;

import com.tap.backend.domain.user.UserEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "grading_rubric")
public class GradingRubricEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "teacher_id", nullable = false)
    private UserEntity teacher;

    @Column(name = "teacher_id", insertable = false, updatable = false)
    private Long teacherId;

    @Column(name = "name", nullable = false, length = 256)
    private String name;

    @Column(name = "subject", length = 128)
    private String subject;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "custom_prompt", columnDefinition = "text")
    private String customPrompt;

    @OneToMany(mappedBy = "rubric", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<RubricDimensionEntity> dimensions = new ArrayList<>();

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

    // Getters and setters
    public Long getId() { return id; }
    public UserEntity getTeacher() { return teacher; }
    public void setTeacher(UserEntity teacher) { this.teacher = teacher; }
    public Long getTeacherId() { return teacherId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCustomPrompt() { return customPrompt; }
    public void setCustomPrompt(String customPrompt) { this.customPrompt = customPrompt; }
    public List<RubricDimensionEntity> getDimensions() { return dimensions; }
    public void setDimensions(List<RubricDimensionEntity> dimensions) { this.dimensions = dimensions; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
