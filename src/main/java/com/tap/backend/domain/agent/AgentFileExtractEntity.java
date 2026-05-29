package com.tap.backend.domain.agent;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "agent_file_extract")
public class AgentFileExtractEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_file_id", nullable = false, unique = true)
    private AgentJobFileEntity jobFile;

    @Column(name = "job_file_id", insertable = false, updatable = false)
    private Long jobFileId;

    @Column(name = "title_candidate", length = 512)
    private String titleCandidate;

    @Column(name = "headings_json", columnDefinition = "json")
    @JdbcTypeCode(SqlTypes.JSON)
    private String headingsJson;

    @Column(name = "abstract_snippet", columnDefinition = "text")
    private String abstractSnippet;

    @Column(name = "body_preview", columnDefinition = "text")
    private String bodyPreview;

    @Column(name = "metadata_json", columnDefinition = "json")
    @JdbcTypeCode(SqlTypes.JSON)
    private String metadataJson;

    @Column(name = "evidence_json", columnDefinition = "json")
    @JdbcTypeCode(SqlTypes.JSON)
    private String evidenceJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist void onCreate() { if (createdAt == null) createdAt = Instant.now(); }

    public Long getId() { return id; }
    public AgentJobFileEntity getJobFile() { return jobFile; }
    public void setJobFile(AgentJobFileEntity jf) { this.jobFile = jf; }
    public Long getJobFileId() { return jobFileId; }
    public String getTitleCandidate() { return titleCandidate; }
    public void setTitleCandidate(String t) { this.titleCandidate = t; }
    public String getHeadingsJson() { return headingsJson; }
    public void setHeadingsJson(String h) { this.headingsJson = h; }
    public String getAbstractSnippet() { return abstractSnippet; }
    public void setAbstractSnippet(String a) { this.abstractSnippet = a; }
    public String getBodyPreview() { return bodyPreview; }
    public void setBodyPreview(String b) { this.bodyPreview = b; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String m) { this.metadataJson = m; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String e) { this.evidenceJson = e; }
}
