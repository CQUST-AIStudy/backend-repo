package com.tap.backend.academic.entity;

import java.util.Date;

/**
 * AI错误分析报告实体类，对应数据库 ai_error_analysis_report 表
 * 用于持久化 Python error-analysis 微服务返回的分析结果
 */
public class AiErrorAnalysisReport {
    private Long id;
    private String analysisId;
    private String studentNo;
    private Integer experimentId;
    private String experimentName;
    private String reportType;
    private String severity;
    private Boolean aiGenerated;
    private String overallAssessment;
    private String errorCategoriesJson;
    private String learningSuggestionsJson;
    private String weakPointsJson;
    private String studyPlanJson;
    private String recommendedProblemsJson;
    private String summaryMessage;
    private String warningType;
    private String warningMessage;
    private String teacherNote;
    private Boolean interventionTriggered;
    private String rawResponseJson;
    private Date createdAt;
    private Date updatedAt;

    public AiErrorAnalysisReport() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAnalysisId() { return analysisId; }
    public void setAnalysisId(String analysisId) { this.analysisId = analysisId; }

    public String getStudentNo() { return studentNo; }
    public void setStudentNo(String studentNo) { this.studentNo = studentNo; }

    public Integer getExperimentId() { return experimentId; }
    public void setExperimentId(Integer experimentId) { this.experimentId = experimentId; }

    public String getExperimentName() { return experimentName; }
    public void setExperimentName(String experimentName) { this.experimentName = experimentName; }

    public String getReportType() { return reportType; }
    public void setReportType(String reportType) { this.reportType = reportType; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public Boolean getAiGenerated() { return aiGenerated; }
    public void setAiGenerated(Boolean aiGenerated) { this.aiGenerated = aiGenerated; }

    public String getOverallAssessment() { return overallAssessment; }
    public void setOverallAssessment(String overallAssessment) { this.overallAssessment = overallAssessment; }

    public String getErrorCategoriesJson() { return errorCategoriesJson; }
    public void setErrorCategoriesJson(String errorCategoriesJson) { this.errorCategoriesJson = errorCategoriesJson; }

    public String getLearningSuggestionsJson() { return learningSuggestionsJson; }
    public void setLearningSuggestionsJson(String learningSuggestionsJson) { this.learningSuggestionsJson = learningSuggestionsJson; }

    public String getWeakPointsJson() { return weakPointsJson; }
    public void setWeakPointsJson(String weakPointsJson) { this.weakPointsJson = weakPointsJson; }

    public String getStudyPlanJson() { return studyPlanJson; }
    public void setStudyPlanJson(String studyPlanJson) { this.studyPlanJson = studyPlanJson; }

    public String getRecommendedProblemsJson() { return recommendedProblemsJson; }
    public void setRecommendedProblemsJson(String recommendedProblemsJson) { this.recommendedProblemsJson = recommendedProblemsJson; }

    public String getSummaryMessage() { return summaryMessage; }
    public void setSummaryMessage(String summaryMessage) { this.summaryMessage = summaryMessage; }

    public String getWarningType() { return warningType; }
    public void setWarningType(String warningType) { this.warningType = warningType; }

    public String getWarningMessage() { return warningMessage; }
    public void setWarningMessage(String warningMessage) { this.warningMessage = warningMessage; }

    public String getTeacherNote() { return teacherNote; }
    public void setTeacherNote(String teacherNote) { this.teacherNote = teacherNote; }

    public Boolean getInterventionTriggered() { return interventionTriggered; }
    public void setInterventionTriggered(Boolean interventionTriggered) { this.interventionTriggered = interventionTriggered; }

    public String getRawResponseJson() { return rawResponseJson; }
    public void setRawResponseJson(String rawResponseJson) { this.rawResponseJson = rawResponseJson; }

    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }

    public Date getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
