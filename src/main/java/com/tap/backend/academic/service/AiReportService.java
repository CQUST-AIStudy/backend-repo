package com.tap.backend.academic.service;

import com.tap.backend.academic.dao.AiExperimentReportDao;
import com.tap.backend.academic.dao.StudentAiReportQueryDao;
import com.tap.backend.academic.entity.AiExperimentReport;
import com.tap.backend.academic.teacherexperiment.AiReportContext;
import com.tap.backend.academic.teacherexperiment.TeacherSubmissionProblemRow;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AiReportService {
    private static final Logger log = LoggerFactory.getLogger(AiReportService.class);
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
        } catch (AiReportException e) {
            log.error("AI report generation failed: studentNo={}, offeringId={}, errorCode={}",
                    studentNo, offeringId, e.getErrorCode(), e);
            if (e.isConfigMissing()) {
                return AiReportResult.failure("AI服务未配置，请管理员检查 OPENAI_API_KEY");
            }
            if (e.isTimeout()) {
                return AiReportResult.failure("AI服务响应超时，请稍后重试");
            }
            if (e.isAuthFailure()) {
                return AiReportResult.failure("AI服务鉴权失败，请管理员检查 API Key");
            }
            if (e.isRateLimited()) {
                return AiReportResult.failure("AI服务请求过于频繁，请稍后重试");
            }
            return AiReportResult.failure("AI上游服务暂时不可用，请稍后重试");
        } catch (Exception e) {
            log.error("AI report generation failed: studentNo={}, offeringId={}, errorType={}",
                    studentNo, offeringId, e.getClass().getSimpleName(), e);
            return AiReportResult.failure("AI报告生成失败，系统已记录错误，请联系管理员");
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
