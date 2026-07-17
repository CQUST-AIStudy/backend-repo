package com.tap.backend.academic.service;

import com.tap.backend.academic.dao.SubmissionDao;
import com.tap.backend.academic.entity.Experiment;
import com.tap.backend.academic.entity.Submission;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AiReportService {
    private final SubmissionDao submissionDao;
    private final ExperimentService experimentService;
    private final AiReportGenerator generator;

    public AiReportService(
            SubmissionDao submissionDao,
            ExperimentService experimentService,
            AiReportGenerator generator) {
        this.submissionDao = submissionDao;
        this.experimentService = experimentService;
        this.generator = generator;
    }

    public AiReportResult generate(String studentNo, int experimentId, Map<String, Object> userData) {
        Experiment experiment = experimentService.findExperimentById(experimentId);
        if (experiment == null) return AiReportResult.failure("实验不存在");
        Submission submission = submissionDao.findByUsernameAndExperimentId(studentNo, experimentId);
        if (submission == null || submission.getCode() == null || submission.getCode().isBlank()) {
            return AiReportResult.failure("该实验暂无代码提交，无法生成报告");
        }
        try {
            Map<String, Object> safeUserData = userData == null ? Map.of() : userData;
            String report = generator.generate(experiment, submission, safeUserData);
            if (report == null || report.isBlank()) return AiReportResult.failure("AI报告生成失败，请稍后重试");
            if (submissionDao.updateReport(submission.getSubmission_id(), report) != 1) {
                return AiReportResult.failure("报告保存失败");
            }
            return success(report, studentNo, safeUserData);
        } catch (Exception e) {
            return AiReportResult.failure("AI报告生成失败，请稍后重试");
        }
    }

    public AiReportResult get(String studentNo, int experimentId) {
        if (experimentService.findExperimentById(experimentId) == null) return AiReportResult.failure("实验不存在");
        Submission submission = submissionDao.findByUsernameAndExperimentId(studentNo, experimentId);
        if (submission == null || submission.getReport() == null || submission.getReport().isBlank()) {
            return AiReportResult.failure("尚未生成报告");
        }
        return success(submission.getReport(), studentNo, Map.of());
    }

    private AiReportResult success(String report, String studentNo, Map<String, Object> userData) {
        Map<String, Object> data = new HashMap<>();
        data.put("report", report);
        data.put("studentId", studentNo);
        for (String key : new String[]{"studentName", "className", "labName", "labTime"}) {
            Object value = userData.get(key);
            if (value != null) data.put(key, value);
        }
        return new AiReportResult(true, "AI报告生成成功", report, data);
    }
}
