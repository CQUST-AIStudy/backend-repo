package com.tap.backend.domain.rag;

import com.tap.backend.domain.document.DocumentEntity;
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
@Table(name = "doc_chunk")
public class DocChunkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private DocumentEntity document;

    @Column(name = "document_id", insertable = false, updatable = false)
    private Long documentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_space_id", nullable = false)
    private CourseSpaceEntity courseSpace;

    @Column(name = "course_space_id", insertable = false, updatable = false)
    private Long courseSpaceId;

    @Column(name = "chunk_type", nullable = false, length = 8)
    private String chunkType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private DocChunkEntity parent;

    @Column(name = "parent_id", insertable = false, updatable = false)
    private Long parentId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "chapter_path", length = 512)
    private String chapterPath;

    @Column(name = "page_range", length = 64)
    private String pageRange;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

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

    public DocumentEntity getDocument() {
        return document;
    }

    public void setDocument(DocumentEntity document) {
        this.document = document;
    }

    public Long getDocumentId() {
        return documentId;
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

    public String getChunkType() {
        return chunkType;
    }

    public void setChunkType(String chunkType) {
        this.chunkType = chunkType;
    }

    public DocChunkEntity getParent() {
        return parent;
    }

    public void setParent(DocChunkEntity parent) {
        this.parent = parent;
    }

    public Long getParentId() {
        return parentId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getChapterPath() {
        return chapterPath;
    }

    public void setChapterPath(String chapterPath) {
        this.chapterPath = chapterPath;
    }

    public String getPageRange() {
        return pageRange;
    }

    public void setPageRange(String pageRange) {
        this.pageRange = pageRange;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(int tokenCount) {
        this.tokenCount = tokenCount;
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
