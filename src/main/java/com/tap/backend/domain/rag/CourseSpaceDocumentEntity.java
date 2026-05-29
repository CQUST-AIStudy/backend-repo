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
@Table(name = "course_space_document")
public class CourseSpaceDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_space_id", nullable = false)
    private CourseSpaceEntity courseSpace;

    @Column(name = "course_space_id", insertable = false, updatable = false)
    private Long courseSpaceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private DocumentEntity document;

    @Column(name = "document_id", insertable = false, updatable = false)
    private Long documentId;

    @Column(name = "doc_type", length = 32)
    private String docType;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = "PENDING";
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

    public DocumentEntity getDocument() {
        return document;
    }

    public void setDocument(DocumentEntity document) {
        this.document = document;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(int chunkCount) {
        this.chunkCount = chunkCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
