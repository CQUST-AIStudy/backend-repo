package com.tap.backend.domain.animation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

/** AI 助教「代码演示（手动输入）」历史记录。 */
@Entity
@Table(name = "student_code_playground")
public class StudentCodePlaygroundEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_no", nullable = false)
    private String studentNo;

    @Column
    private String title;

    @Column(name = "problem_md", columnDefinition = "text")
    private String problemMd;

    @Column(name = "source_code", columnDefinition = "longtext")
    private String sourceCode;

    @Column(name = "stdin_text", columnDefinition = "text")
    private String stdinText;

    @Column
    private String workflow;

    @Column(name = "frames_json", columnDefinition = "longtext")
    private String framesJson;

    @Column(columnDefinition = "text")
    private String explanation;

    @Column(name = "error_line", nullable = false)
    private int errorLine;

    @Column(nullable = false)
    private String status = "COMPLETED";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getStudentNo() { return studentNo; }
    public void setStudentNo(String studentNo) { this.studentNo = studentNo; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getProblemMd() { return problemMd; }
    public void setProblemMd(String problemMd) { this.problemMd = problemMd; }

    public String getSourceCode() { return sourceCode; }
    public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }

    public String getStdinText() { return stdinText; }
    public void setStdinText(String stdinText) { this.stdinText = stdinText; }

    public String getWorkflow() { return workflow; }
    public void setWorkflow(String workflow) { this.workflow = workflow; }

    public String getFramesJson() { return framesJson; }
    public void setFramesJson(String framesJson) { this.framesJson = framesJson; }

    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }

    public int getErrorLine() { return errorLine; }
    public void setErrorLine(int errorLine) { this.errorLine = errorLine; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
