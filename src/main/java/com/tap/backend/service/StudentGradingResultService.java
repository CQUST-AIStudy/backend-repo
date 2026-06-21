package com.tap.backend.service;

import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.domain.grading.ReportFileEntity;
import com.tap.backend.infra.storage.ObjectStorageService;
import com.tap.backend.repo.GradingSubmissionRepository;
import com.tap.backend.repo.ReportFileRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudentGradingResultService {

    private final GradingSubmissionRepository submissionRepo;
    private final ReportFileRepository reportFileRepo;
    private final ObjectStorageService storageService;

    public StudentGradingResultService(GradingSubmissionRepository submissionRepo,
                                       ReportFileRepository reportFileRepo,
                                       ObjectStorageService storageService) {
        this.submissionRepo = submissionRepo;
        this.reportFileRepo = reportFileRepo;
        this.storageService = storageService;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findPublishedResult(String studentNo, Long experimentId) {
        List<GradingSubmissionEntity> matches =
                submissionRepo.findPublishedForStudentExperiment(studentNo, experimentId);
        if (matches.isEmpty()) {
            return Map.of("published", false);
        }
        GradingSubmissionEntity submission = matches.get(0);
        ReportFileEntity report = selectPreferredReport(submission.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("published", true);
        result.put("submissionId", submission.getId());
        result.put("score", submission.getTotalScore());
        result.put("finalReviewComment", submission.getFinalReviewComment());
        result.put("publishedAt", submission.getPublishedAt().toString());
        result.put("reportAvailable", report != null);
        result.put("reportFileType", report != null ? report.getFileType() : null);
        return result;
    }

    @Transactional(readOnly = true)
    public DownloadedReport downloadPublishedReport(Long submissionId, String studentNo) {
        GradingSubmissionEntity submission = submissionRepo
                .findByIdAndStudentNoAndPublishedAtIsNotNull(submissionId, studentNo)
                .orElseThrow(() -> new IllegalArgumentException("已发布报告不存在"));
        ReportFileEntity report = selectPreferredReport(submissionId);
        if (report == null) {
            throw new IllegalArgumentException("批注报告尚未生成");
        }
        String extension = AnnotatedStudentReportService.FILE_TYPE_ANNOTATED_DOCX.equals(report.getFileType())
                ? ".docx" : ".pdf";
        MediaType mediaType = extension.equals(".docx")
                ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                : MediaType.APPLICATION_PDF;
        String baseName = submission.getStudentName() == null || submission.getStudentName().isBlank()
                ? "grading-report" : submission.getStudentName() + "-批注报告";
        return new DownloadedReport(storageService.getBytes(report.getObjectKey()), mediaType, baseName + extension);
    }

    private ReportFileEntity selectPreferredReport(Long submissionId) {
        return reportFileRepo.findAllBySubmissionIdOrderByCreatedAtDesc(submissionId).stream()
                .max(Comparator.comparingInt(this::priority)
                        .thenComparing(ReportFileEntity::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
    }

    private int priority(ReportFileEntity report) {
        return switch (report.getFileType()) {
            case AnnotatedStudentReportService.FILE_TYPE_ANNOTATED_DOCX -> 4;
            case AnnotatedStudentReportService.FILE_TYPE_ANNOTATED_PDF -> 3;
            case "pdf" -> 2;
            default -> 1;
        };
    }

    public record DownloadedReport(byte[] bytes, MediaType mediaType, String filename) {}
}
