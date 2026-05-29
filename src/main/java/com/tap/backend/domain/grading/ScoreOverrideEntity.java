package com.tap.backend.domain.grading;

import com.tap.backend.domain.user.UserEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "score_override")
public class ScoreOverrideEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "score_item_id", nullable = false)
    private ScoreItemEntity scoreItem;

    @Column(name = "score_item_id", insertable = false, updatable = false)
    private Long scoreItemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "teacher_id", nullable = false)
    private UserEntity teacher;

    @Column(name = "teacher_id", insertable = false, updatable = false)
    private Long teacherId;

    @Column(name = "old_score", precision = 5, scale = 1)
    private BigDecimal oldScore;

    @Column(name = "new_score", nullable = false, precision = 5, scale = 1)
    private BigDecimal newScore;

    @Column(name = "old_comment", columnDefinition = "text")
    private String oldComment;

    @Column(name = "new_comment", columnDefinition = "text")
    private String newComment;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = Instant.now(); }

    public Long getId() { return id; }
    public ScoreItemEntity getScoreItem() { return scoreItem; }
    public void setScoreItem(ScoreItemEntity scoreItem) { this.scoreItem = scoreItem; }
    public Long getScoreItemId() { return scoreItemId; }
    public UserEntity getTeacher() { return teacher; }
    public void setTeacher(UserEntity teacher) { this.teacher = teacher; }
    public Long getTeacherId() { return teacherId; }
    public BigDecimal getOldScore() { return oldScore; }
    public void setOldScore(BigDecimal oldScore) { this.oldScore = oldScore; }
    public BigDecimal getNewScore() { return newScore; }
    public void setNewScore(BigDecimal newScore) { this.newScore = newScore; }
    public String getOldComment() { return oldComment; }
    public void setOldComment(String oldComment) { this.oldComment = oldComment; }
    public String getNewComment() { return newComment; }
    public void setNewComment(String newComment) { this.newComment = newComment; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getCreatedAt() { return createdAt; }
}
