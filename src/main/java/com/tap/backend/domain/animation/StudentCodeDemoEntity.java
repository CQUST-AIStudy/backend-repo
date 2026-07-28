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

/** 学生端「每题代码执行演示」缓存：按 (学生, 实验, 题号) 保存一次最新生成的演示分镜。 */
@Entity
@Table(name = "student_code_demo")
public class StudentCodeDemoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_profile_id", nullable = false)
    private Long studentProfileId;

    @Column(name = "offering_id", nullable = false)
    private Long offeringId;

    @Column(name = "problem_no", nullable = false, length = 128)
    private String problemNo;

    @Column(name = "source_code", columnDefinition = "LONGTEXT")
    private String sourceCode;

    @Column(name = "stdin_text", columnDefinition = "TEXT")
    private String stdinText;

    /** 单个 demonstration 对象的 JSON（含 frames 等），直接回传前端播放器。 */
    @Column(name = "frames_json", columnDefinition = "LONGTEXT")
    private String framesJson;

    /** PYTHON_TUTOR（真实执行）/ CONCEPT_STEPS（LLM 兜底） */
    @Column(name = "workflow", length = 32)
    private String workflow;

    @Column(name = "title", length = 512)
    private String title;

    @Column(name = "explanation", columnDefinition = "TEXT")
    private String explanation;

    @Column(name = "error_line", nullable = false)
    private int errorLine = 0;

    /** COMPLETED / FAILED */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "COMPLETED";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public Long getStudentProfileId() { return studentProfileId; }
    public void setStudentProfileId(Long studentProfileId) { this.studentProfileId = studentProfileId; }
    public Long getOfferingId() { return offeringId; }
    public void setOfferingId(Long offeringId) { this.offeringId = offeringId; }
    public String getProblemNo() { return problemNo; }
    public void setProblemNo(String problemNo) { this.problemNo = problemNo; }
    public String getSourceCode() { return sourceCode; }
    public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }
    public String getStdinText() { return stdinText; }
    public void setStdinText(String stdinText) { this.stdinText = stdinText; }
    public String getFramesJson() { return framesJson; }
    public void setFramesJson(String framesJson) { this.framesJson = framesJson; }
    public String getWorkflow() { return workflow; }
    public void setWorkflow(String workflow) { this.workflow = workflow; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
    public int getErrorLine() { return errorLine; }
    public void setErrorLine(int errorLine) { this.errorLine = errorLine; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
