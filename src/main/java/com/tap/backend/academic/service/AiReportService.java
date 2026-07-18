package com.tap.backend.academic.service;

import com.tap.backend.academic.dao.AiExperimentReportDao;
import com.tap.backend.academic.dao.StudentAiReportQueryDao;
import com.tap.backend.academic.entity.AiExperimentReport;
import com.tap.backend.academic.teacherexperiment.AiReportContext;
import com.tap.backend.academic.teacherexperiment.TeacherSubmissionProblemRow;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AiReportService {
    private final StudentAiReportQueryDao queryDao;
    private final AiExperimentReportDao reportDao;
    private final AiReportGenerator generator;

    public AiReportService(
            StudentAiReportQueryDao queryDao,
            AiExperimentReportDao reportDao,
            AiReportGenerator generator) {
        this.queryDao = queryDao;
        this.reportDao = reportDao;
        this.generator = generator;
    }

    public AiReportResult generate(String studentNo, int offeringId, Map<String, Object> userData) {
        AiReportContext context = queryDao.findContext(studentNo, offeringId);
        if (context == null || context.getOfferingId() == null || context.getStudentProfileId() == null) {
            return AiReportResult.failure("实验不存在或无权访问");
        }

        Map<String, Object> safeUserData = userData == null ? Map.of() : userData;
        String code = resolveCode(queryDao.findProblemRows(studentNo, offeringId), safeUserData);
        if (code.isBlank()) {
            return AiReportResult.failure("该实验暂无代码提交，无法生成报告");
        }

        try {
            String report = generator.generate(context, code, safeUserData);
            if (report == null || report.isBlank()) {
                return AiReportResult.failure("AI报告生成失败，请稍后重试");
            }
            int affectedRows = reportDao.upsert(
                    context.getOfferingId(), context.getStudentProfileId(), report);
            if (affectedRows <= 0) {
                return AiReportResult.failure("报告保存失败");
            }
            return success(report, studentNo, safeUserData);
        } catch (Exception e) {
            return AiReportResult.failure("AI报告生成失败，请稍后重试");
        }
    }

    private String resolveCode(
            List<TeacherSubmissionProblemRow> problemRows,
            Map<String, Object> userData) {
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
        if (!code.isEmpty()) {
            return code.toString();
        }
        Object requestCode = userData.get("code");
        return requestCode == null ? "" : requestCode.toString().trim();
    }

    public AiReportResult get(String studentNo, int offeringId) {
        AiReportContext context = queryDao.findContext(studentNo, offeringId);
        if (context == null || context.getOfferingId() == null || context.getStudentProfileId() == null) {
            return AiReportResult.failure("实验不存在或无权访问");
        }
        AiExperimentReport saved = reportDao.findByOfferingAndStudent(
                context.getOfferingId(), context.getStudentProfileId());
        if (saved == null || saved.getReportMd() == null || saved.getReportMd().isBlank()) {
            return AiReportResult.failure("尚未生成报告");
        }
        return success(saved.getReportMd(), studentNo, Map.of());
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
