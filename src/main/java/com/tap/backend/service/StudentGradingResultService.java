package com.tap.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.domain.grading.ReportFileEntity;
import com.tap.backend.infra.storage.ObjectStorageService;
import com.tap.backend.repo.GradingSubmissionRepository;
import com.tap.backend.repo.ReportFileRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only access for students to their own published grading results.
 * Visibility is strictly controlled by {@code published_at} and a match between
 * the submission's {@code student_no} and the authenticated student's number.
 */
@Service
public class StudentGradingResultService {

    private final GradingSubmissionRepository submissionRepo;
    private final ReportFileRepository reportFileRepo;
    private final ObjectStorageService storageService;
    private final ObjectMapper objectMapper;

    public StudentGradingResultService(
            GradingSubmissionRepository submissionRepo,
            ReportFileRepository reportFileRepo,
            ObjectStorageService storageService,
            ObjectMapper objectMapper
    ) {
        this.submissionRepo = submissionRepo;
        this.reportFileRepo = reportFileRepo;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the current student's published grading result for the experiment,
     * or {@code {"published": false}} when nothing is published for them.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getPublishedResult(Long experimentId, String studentNo) {
        if (experimentId == null || studentNo == null || studentNo.isBlank()) {
            return Map.of("published", false);
        }
        List<GradingSubmissionEntity> published =
                submissionRepo.findPublishedByOfferingOrExperimentAndStudentNo(experimentId, studentNo);
        if (published.isEmpty()) {
            return Map.of("published", false);
        }
        GradingSubmissionEntity submission = published.get(0);
        ReportFileEntity report = selectPreferredReport(submission.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("published", true);
        result.put("submissionId", submission.getId());
        result.put("studentName", submission.getStudentName());
        result.put("score", submission.getTotalScore());
        result.put("finalReviewComment", submission.getFinalReviewComment());
        result.put("reportAvailable", report != null);
        result.put("reportFilename", resolveDownloadName(submission, report));
        result.put("publishedAt",
                submission.getPublishedAt() == null ? null : submission.getPublishedAt().toString());

        Object errorDemonstrations = parseErrorDemonstrations(submission.getErrorDemonstrationsJson());
        if (errorDemonstrations != null) {
            result.put("errorDemonstrations", errorDemonstrations);
        }
        return result;
    }

    /**
     * Serves the annotated report bytes for a published submission owned by the student.
     */
    @Transactional(readOnly = true)
    public ReportDownload getPublishedReport(Long submissionId, String studentNo) {
        GradingSubmissionEntity submission = submissionRepo.findById(submissionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "成绩不存在或未发布"));
        if (submission.getPublishedAt() == null
                || studentNo == null
                || !studentNo.equals(submission.getStudentNo())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "成绩不存在或未发布");
        }
        ReportFileEntity report = selectPreferredReport(submissionId);
        if (report == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批注报告尚未生成");
        }
        byte[] bytes = storageService.getBytes(report.getObjectKey());
        return new ReportDownload(bytes, resolveMediaType(report), resolveDownloadName(submission, report));
    }

    private ReportFileEntity selectPreferredReport(Long submissionId) {
        return reportFileRepo.findAllBySubmissionIdOrderByCreatedAtDesc(submissionId).stream()
                .max(this::compareReports)
                .orElse(null);
    }

    private int compareReports(ReportFileEntity left, ReportFileEntity right) {
        int byPriority = Integer.compare(reportPriority(left), reportPriority(right));
        if (byPriority != 0) {
            return byPriority;
        }
        int byCreatedAt = Comparator.nullsLast(Comparator.<java.time.Instant>naturalOrder())
                .compare(left == null ? null : left.getCreatedAt(),
                        right == null ? null : right.getCreatedAt());
        if (byCreatedAt != 0) {
            return byCreatedAt;
        }
        return Comparator.nullsLast(Comparator.<Long>naturalOrder())
                .compare(left == null ? null : left.getId(), right == null ? null : right.getId());
    }

    private int reportPriority(ReportFileEntity report) {
        if (report == null || report.getFileType() == null) {
            return 0;
        }
        return switch (report.getFileType()) {
            case AnnotatedStudentReportService.FILE_TYPE_ANNOTATED_DOCX -> 4;
            case AnnotatedStudentReportService.FILE_TYPE_ANNOTATED_PDF -> 3;
            case "pdf" -> 2;
            default -> 1;
        };
    }

    private MediaType resolveMediaType(ReportFileEntity report) {
        if (report == null || report.getFileType() == null) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        return switch (report.getFileType()) {
            case AnnotatedStudentReportService.FILE_TYPE_ANNOTATED_DOCX ->
                    MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case AnnotatedStudentReportService.FILE_TYPE_ANNOTATED_PDF, "pdf" -> MediaType.APPLICATION_PDF;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    private String resolveDownloadName(GradingSubmissionEntity submission, ReportFileEntity report) {
        String baseName = sanitize(
                submission.getStudentName() != null && !submission.getStudentName().isBlank()
                        ? submission.getStudentName()
                        : "submission_" + submission.getId());
        if (report == null || report.getFileType() == null) {
            return baseName + ".pdf";
        }
        return switch (report.getFileType()) {
            case AnnotatedStudentReportService.FILE_TYPE_ANNOTATED_DOCX -> baseName + "-教师批注报告.docx";
            case AnnotatedStudentReportService.FILE_TYPE_ANNOTATED_PDF -> baseName + "-教师批注报告.pdf";
            case "pdf" -> baseName + "-成绩报告.pdf";
            default -> baseName + ".bin";
        };
    }

    private String sanitize(String value) {
        if (value == null) {
            return "report";
        }
        String cleaned = value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "report" : cleaned;
    }

    private Object parseErrorDemonstrations(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    public record ReportDownload(byte[] bytes, MediaType mediaType, String filename) {}
}
