package com.tap.backend.domain.rag;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "qa_log")
public class QaLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", length = 64)
    private String studentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_space_id", nullable = false)
    private CourseSpaceEntity courseSpace;

    @Column(name = "course_space_id", insertable = false, updatable = false)
    private Long courseSpaceId;

    @Column(name = "query", nullable = false, columnDefinition = "text")
    private String query;

    @Column(name = "retrieved_chunk_ids", columnDefinition = "json")
    private String retrievedChunkIds;

    @Column(name = "top1_score")
    private Double top1Score;

    @Column(name = "answer_text", columnDefinition = "text")
    private String answerText;

    @Column(name = "citations_json", columnDefinition = "json")
    private String citationsJson;

    @Column(name = "mode", length = 8)
    private String mode;

    @Column(name = "coverage_score")
    private Double coverageScore;

    @Column(name = "used_web")
    private Boolean usedWeb;

    @Column(name = "feedback")
    @JdbcTypeCode(SqlTypes.TINYINT)
    private Integer feedback;

    @Column(name = "intent_type", length = 32)
    private String intentType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getStudentId() {
        return studentId;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
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

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getRetrievedChunkIds() {
        return retrievedChunkIds;
    }

    public void setRetrievedChunkIds(String retrievedChunkIds) {
        this.retrievedChunkIds = retrievedChunkIds;
    }

    public Double getTop1Score() {
        return top1Score;
    }

    public void setTop1Score(Double top1Score) {
        this.top1Score = top1Score;
    }

    public String getAnswerText() {
        return answerText;
    }

    public void setAnswerText(String answerText) {
        this.answerText = answerText;
    }

    public String getCitationsJson() {
        return citationsJson;
    }

    public void setCitationsJson(String citationsJson) {
        this.citationsJson = citationsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Double getCoverageScore() {
        return coverageScore;
    }

    public void setCoverageScore(Double coverageScore) {
        this.coverageScore = coverageScore;
    }

    public Boolean getUsedWeb() {
        return usedWeb;
    }

    public void setUsedWeb(Boolean usedWeb) {
        this.usedWeb = usedWeb;
    }

    public Integer getFeedback() {
        return feedback;
    }

    public void setFeedback(Integer feedback) {
        this.feedback = feedback;
    }

    public String getIntentType() {
        return intentType;
    }

    public void setIntentType(String intentType) {
        this.intentType = intentType;
    }
}
