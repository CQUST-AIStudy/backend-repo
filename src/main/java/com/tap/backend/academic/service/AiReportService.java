package com.tap.backend.academic.service;

import com.tap.backend.academic.dao.SubmissionDao;
import com.tap.backend.academic.dao.teacherexperiment.TeacherExperimentQueryDao;
import com.tap.backend.academic.entity.Experiment;
import com.tap.backend.academic.entity.Submission;
import com.tap.backend.academic.teacherexperiment.TeacherSubmissionProblemRow;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AiReportService {
    private final SubmissionDao submissionDao;
    private final ExperimentService experimentService;
    private final AiReportGenerator generator;
    private final TeacherExperimentQueryDao teacherExperimentQueryDao;

    public AiReportService(
            SubmissionDao submissionDao,
            ExperimentService experimentService,
            AiReportGenerator generator,
            TeacherExperimentQueryDao teacherExperimentQueryDao) {
        this.submissionDao = submissionDao;
        this.experimentService = experimentService;
        this.generator = generator;
        this.teacherExperimentQueryDao = teacherExperimentQueryDao;
    }

    public AiReportResult generate(String studentNo, int experimentId, Map<String, Object> userData) {
        Experiment experiment = experimentService.findExperimentById(experimentId);
        if (experiment == null) return AiReportResult.failure("实验不存在");
        Submission submission = submissionDao.findByUsernameAndExperimentId(studentNo, experimentId);
        String code = resolveCode(studentNo, experimentId, submission);
        if (code.isBlank()) {
            return AiReportResult.failure("该实验暂无代码提交，无法生成报告");
        }
        try {
            Map<String, Object> safeUserData = userData == null ? Map.of() : userData;
            Submission reportSubmission = submission == null
                    ? new Submission(0, studentNo, experimentId, code, null, new Date())
                    : submission;
            reportSubmission.setCode(code);
            String report = generator.generate(experiment, reportSubmission, safeUserData);
            if (report == null || report.isBlank()) return AiReportResult.failure("AI报告生成失败，请稍后重试");
            int affectedRows;
            if (submission == null) {
                reportSubmission.setReport(report);
                affectedRows = submissionDao.saveSubmission(reportSubmission);
            } else {
                affectedRows = submissionDao.updateReport(submission.getSubmission_id(), report);
            }
            if (affectedRows != 1) {
                return AiReportResult.failure("报告保存失败");
            }
            return success(report, studentNo, safeUserData);
        } catch (Exception e) {
            return AiReportResult.failure("AI报告生成失败，请稍后重试");
        }
    }

    private String resolveCode(String studentNo, int experimentId, Submission legacySubmission) {
        List<TeacherSubmissionProblemRow> problemRows =
                teacherExperimentQueryDao.findSubmissionProblemRows(studentNo, experimentId);
        StringBuilder code = new StringBuilder();
        int displayIndex = 1;
        if (problemRows != null) {
            for (TeacherSubmissionProblemRow row : problemRows) {
                if (row == null || row.getCode() == null || row.getCode().isBlank()) continue;
                if (!code.isEmpty()) code.append("\n\n");
                code.append("第").append(displayIndex++).append("题如下：\n");
                if (row.getProblemTitle() != null && !row.getProblemTitle().isBlank()) {
                    code.append("// ").append(row.getProblemTitle()).append("\n");
                }
                code.append(row.getCode().trim());
            }
        }
        if (!code.isEmpty()) return code.toString();
        return legacySubmission != null && legacySubmission.getCode() != null
                ? legacySubmission.getCode().trim()
                : "";
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
