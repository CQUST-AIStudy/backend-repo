package com.tap.backend.domain.notification;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Student in-app notification. Recipients are addressed by their {@code student_no}, matching the
 * identity used by the published-grade read path. Currently only {@code GRADE_PUBLISHED} is produced.
 */
@Entity
@Table(name = "notification")
public class NotificationEntity {

    public static final String TYPE_GRADE_PUBLISHED = "GRADE_PUBLISHED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_student_no", nullable = false, length = 64)
    private String recipientStudentNo;

    @Column(name = "type", nullable = false, length = 32)
    private String type;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "content", columnDefinition = "text")
    private String content;

    @Column(name = "link_experiment_id")
    private Long linkExperimentId;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRecipientStudentNo() {
        return recipientStudentNo;
    }

    public void setRecipientStudentNo(String recipientStudentNo) {
        this.recipientStudentNo = recipientStudentNo;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getLinkExperimentId() {
        return linkExperimentId;
    }

    public void setLinkExperimentId(Long linkExperimentId) {
        this.linkExperimentId = linkExperimentId;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
