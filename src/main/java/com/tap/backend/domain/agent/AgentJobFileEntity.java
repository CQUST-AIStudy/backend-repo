package com.tap.backend.domain.agent;

import com.tap.backend.domain.document.DocumentEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agent_job_file")
public class AgentJobFileEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private AgentJobEntity job;

    @Column(name = "job_id", insertable = false, updatable = false)
    private Long jobId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private DocumentEntity document;

    @Column(name = "document_id", insertable = false, updatable = false)
    private Long documentId;

    @Column(name = "object_key", nullable = false, columnDefinition = "text")
    private String objectKey;

    @Column(name = "filename", nullable = false, length = 512)
    private String filename;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "size_bytes")
    private long sizeBytes;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "ext", length = 32)
    private String ext;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "PENDING";

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist void onCreate() { if (createdAt == null) createdAt = Instant.now(); }

    // Getters & Setters
    public Long getId() { return id; }
    public AgentJobEntity getJob() { return job; }
    public void setJob(AgentJobEntity job) { this.job = job; }
    public Long getJobId() { return jobId; }
    public DocumentEntity getDocument() { return document; }
    public void setDocument(DocumentEntity doc) { this.document = doc; }
    public Long getDocumentId() { return documentId; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String k) { this.objectKey = k; }
    public String getFilename() { return filename; }
    public void setFilename(String f) { this.filename = f; }
    public String getContentType() { return contentType; }
    public void setContentType(String ct) { this.contentType = ct; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long s) { this.sizeBytes = s; }
    public String getSha256() { return sha256; }
    public void setSha256(String h) { this.sha256 = h; }
    public String getExt() { return ext; }
    public void setExt(String e) { this.ext = e; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String m) { this.errorMessage = m; }
}
