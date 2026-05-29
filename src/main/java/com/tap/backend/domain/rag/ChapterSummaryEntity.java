package com.tap.backend.domain.rag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "chapter_summary")
public class ChapterSummaryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doc_id", nullable = false)
    private Long docId;

    @Column(name = "course_space_id", nullable = false)
    private Long courseSpaceId;

    @Column(name = "chapter_path", nullable = false, length = 512)
    private String chapterPath;

    @Column(name = "summary_text", nullable = false, columnDefinition = "text")
    private String summaryText;

    @Column(name = "level", nullable = false)
    private Integer level = 1;

    @Column(name = "parent_chapter_id")
    private Long parentChapterId;

    @Column(name = "milvus_id")
    private Long milvusId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getDocId() {
        return docId;
    }

    public void setDocId(Long docId) {
        this.docId = docId;
    }

    public Long getCourseSpaceId() {
        return courseSpaceId;
    }

    public void setCourseSpaceId(Long courseSpaceId) {
        this.courseSpaceId = courseSpaceId;
    }

    public String getChapterPath() {
        return chapterPath;
    }

    public void setChapterPath(String chapterPath) {
        this.chapterPath = chapterPath;
    }

    public String getSummaryText() {
        return summaryText;
    }

    public void setSummaryText(String summaryText) {
        this.summaryText = summaryText;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public Long getParentChapterId() {
        return parentChapterId;
    }

    public void setParentChapterId(Long parentChapterId) {
        this.parentChapterId = parentChapterId;
    }

    public Long getMilvusId() {
        return milvusId;
    }

    public void setMilvusId(Long milvusId) {
        this.milvusId = milvusId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
