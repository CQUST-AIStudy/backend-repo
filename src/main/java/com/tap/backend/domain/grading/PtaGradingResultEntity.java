package com.tap.backend.domain.grading;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** PTA 批改结果：按 (offering, 学生) 存客观分 + AI 评语，可发布给学生。 */
@Entity
@Table(name = "pta_grading_result")
public class PtaGradingResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "offering_id", nullable = false)
    private Long offeringId;

    @Column(name = "problem_set_id")
    private String problemSetId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "student_no", nullable = false)
    private String studentNo;

    @Column(name = "student_name")
    private String studentName;

    @Column
    private BigDecimal score;

    @Column(name = "ac_rate")
    private BigDecimal acRate;

    @Column(name = "problem_count", nullable = false)
    private int problemCount;

    @Column(name = "accepted_count", nullable = false)
    private int acceptedCount;

    @Column(columnDefinition = "text")
    private String comment;

    @Column(name = "detail_json", columnDefinition = "longtext")
    private String detailJson;

    @Column(nullable = false)
    private String status = "COMPLETED";

    @Column(nullable = false)
    private boolean published;

    @Column(name = "published_at")
    private Instant publishedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getOfferingId() { return offeringId; }
    public void setOfferingId(Long offeringId) { this.offeringId = offeringId; }

    public String getProblemSetId() { return problemSetId; }
    public void setProblemSetId(String problemSetId) { this.problemSetId = problemSetId; }

    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }

    public String getStudentNo() { return studentNo; }
    public void setStudentNo(String studentNo) { this.studentNo = studentNo; }

    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }

    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }

    public BigDecimal getAcRate() { return acRate; }
    public void setAcRate(BigDecimal acRate) { this.acRate = acRate; }

    public int getProblemCount() { return problemCount; }
    public void setProblemCount(int problemCount) { this.problemCount = problemCount; }

    public int getAcceptedCount() { return acceptedCount; }
    public void setAcceptedCount(int acceptedCount) { this.acceptedCount = acceptedCount; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }

    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
