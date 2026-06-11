package com.tap.backend.domain.animation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** 动画讲解的单个分镜：一段独立可播放的 HTML 动画 + 可选旁白音频。 */
@Entity
@Table(name = "animation_frame")
public class AnimationFrameEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "explain_id", nullable = false)
    private Long explainId;

    @Column(name = "frame_index", nullable = false)
    private int frameIndex;

    @Column(name = "title", length = 256)
    private String title;

    @Column(name = "narration", columnDefinition = "TEXT")
    private String narration;

    @Column(name = "visual_hint", columnDefinition = "TEXT")
    private String visualHint;

    @Column(name = "html_object_key", length = 512)
    private String htmlObjectKey;

    @Column(name = "audio_object_key", length = 512)
    private String audioObjectKey;

    /** PENDING / COMPLETED / FAILED */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "PENDING";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public Long getExplainId() { return explainId; }
    public void setExplainId(Long explainId) { this.explainId = explainId; }
    public int getFrameIndex() { return frameIndex; }
    public void setFrameIndex(int frameIndex) { this.frameIndex = frameIndex; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getNarration() { return narration; }
    public void setNarration(String narration) { this.narration = narration; }
    public String getVisualHint() { return visualHint; }
    public void setVisualHint(String visualHint) { this.visualHint = visualHint; }
    public String getHtmlObjectKey() { return htmlObjectKey; }
    public void setHtmlObjectKey(String htmlObjectKey) { this.htmlObjectKey = htmlObjectKey; }
    public String getAudioObjectKey() { return audioObjectKey; }
    public void setAudioObjectKey(String audioObjectKey) { this.audioObjectKey = audioObjectKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
