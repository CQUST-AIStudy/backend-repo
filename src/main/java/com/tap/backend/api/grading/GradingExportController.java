package com.tap.backend.api.grading;

import com.tap.backend.domain.grading.EvidenceBlockEntity;
import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.domain.grading.GradingTaskEntity;
import com.tap.backend.domain.grading.ReportFileEntity;
import com.tap.backend.infra.storage.ObjectStorageService;
import com.tap.backend.repo.EvidenceBlockRepository;
import com.tap.backend.repo.GradingSubmissionRepository;
import com.tap.backend.repo.GradingTaskRepository;
import com.tap.backend.repo.ReportFileRepository;
import com.tap.backend.security.TeacherPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.AnnotatedStudentReportService;
import com.tap.backend.service.GradingSubmissionService;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/grading")
public class GradingExportController {

    private final GradingTaskRepository taskRepo;
    private final GradingSubmissionRepository submissionRepo;
    private final ReportFileRepository reportFileRepo;
    private final EvidenceBlockRepository evidenceBlockRepo;
    private final ObjectStorageService storageService;
    private final TeacherPrincipalResolver teacherPrincipalResolver;
    private final GradingSubmissionService gradingSubmissionService;

    @Value("${tap.grading.export.max-concurrency:6}")
    private int exportMaxConcurrency;

    @Value("${tap.grading.export.submit-interval-ms:30}")
    private long exportSubmitIntervalMs;

    public GradingExportController(
            GradingTaskRepository taskRepo,
            GradingSubmissionRepository submissionRepo,
            ReportFileRepository reportFileRepo,
            EvidenceBlockRepository evidenceBlockRepo,
            ObjectStorageService storageService,
            TeacherPrincipalResolver teacherPrincipalResolver,
            GradingSubmissionService gradingSubmissionService
    ) {
        this.taskRepo = taskRepo;
        this.submissionRepo = submissionRepo;
        this.reportFileRepo = reportFileRepo;
        this.evidenceBlockRepo = evidenceBlockRepo;
        this.storageService = storageService;
        this.teacherPrincipalResolver = teacherPrincipalResolver;
        this.gradingSubmissionService = gradingSubmissionService;
    }

    @GetMapping("/reports/{id}")
    public ResponseEntity<?> downloadReport(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        GradingSubmissionEntity submission = submissionRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found"));
        ReportFileEntity report = selectPreferredReport(reportFileRepo.findAllBySubmissionIdOrderByCreatedAtDesc(id));

        // If no report exists at all but the submission has been scored,
        // auto-generate the annotated report on the fly so the download button
        // always works for scored submissions.
        if (report == null && submission.getTotalScore() != null) {
            try {
                gradingSubmissionService.ensureReviewAndAnnotatedReport(id, teacherId);
                report = selectPreferredReport(
                        reportFileRepo.findAllBySubmissionIdOrderByCreatedAtDesc(id));
            } catch (Exception e) {
                // Fall through to the null check below
            }
        }

        // If only the simple text PDF exists (no annotated red-pen report), auto-generate it now.
        // This ensures the "下载批注报告" button always delivers the annotated version.
        if (report != null && reportPriority(report) < 3
                && submission.getTotalScore() != null) {
            try {
                gradingSubmissionService.ensureReviewAndAnnotatedReport(id, teacherId);
                report = selectPreferredReport(
                        reportFileRepo.findAllBySubmissionIdOrderByCreatedAtDesc(id));
            } catch (Exception e) {
                // Fall through: serve whatever report is available
            }
        }

        if (report == null) {
            return ResponseEntity.status(404).body(Map.of("message", "Report not yet generated"));
        }
        requireOwnedTask(report.getTaskId(), teacherId);
        byte[] bytes = storageService.getBytes(report.getObjectKey());
        return ResponseEntity.ok()
                .contentType(resolveMediaType(report))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + resolveDownloadName(submission, report))
                .body(bytes);
    }

    @GetMapping("/evidence/{evidenceId}/image")
    public ResponseEntity<?> downloadEvidenceImage(
            @PathVariable String evidenceId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        EvidenceBlockEntity evidence = evidenceBlockRepo.findByEvidenceId(evidenceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evidence not found"));
        if (evidence.getImageKey() == null || evidence.getImageKey().isBlank()) {
            return ResponseEntity.status(404).body(Map.of("message", "Evidence has no image"));
        }
        GradingSubmissionEntity submission = evidence.getSubmission();
        if (submission == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found");
        }
        requireOwnedTask(submission.getTaskId(), teacherId);
        byte[] bytes = storageService.getBytes(evidence.getImageKey());
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + evidenceId + ".png")
                .body(bytes);
    }

    @PostMapping("/tasks/{id}/export")
    public ResponseEntity<?> exportBatch(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        GradingTaskEntity task = requireOwnedTask(id, teacherId);

        List<GradingSubmissionEntity> submissions = submissionRepo.findAllByTaskId(id);
        if (submissions.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("message", "No submissions found"));
        }

        List<String> generationErrors = ensureAnnotatedReports(submissions, teacherId);

        Map<Long, ReportFileEntity> reportBySubId = reportFileRepo.findAllByTaskId(id).stream()
                .filter(report -> report.getSubmissionId() != null)
                .collect(Collectors.toMap(
                        ReportFileEntity::getSubmissionId,
                        report -> report,
                        this::preferredReport
                ));

        int concurrency = Math.max(1, Math.min(exportMaxConcurrency, submissions.size()));
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CompletionService<ExportPayload> completionService = new ExecutorCompletionService<>(pool);
        Map<Long, ExportPayload> payloadBySubmissionId = new ConcurrentHashMap<>();

        try {
            int submitted = 0;
            for (GradingSubmissionEntity submission : submissions) {
                completionService.submit(() -> loadExportPayload(submission, reportBySubId.get(submission.getId())));
                submitted++;
                if (exportSubmitIntervalMs > 0) {
                    Thread.sleep(exportSubmitIntervalMs);
                }
            }

            for (int i = 0; i < submitted; i++) {
                ExportPayload payload = completionService.take().get();
                payloadBySubmissionId.put(payload.submissionId(), payload);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ZipOutputStream zos = new ZipOutputStream(baos);
            int exportedCount = 0;
            List<String> missingSubmissions = new ArrayList<>();
            Set<String> usedNames = new HashSet<>();

            for (GradingSubmissionEntity submission : submissions) {
                ExportPayload payload = payloadBySubmissionId.get(submission.getId());
                String baseName = sanitizeFileName(
                        submission.getStudentName() != null && !submission.getStudentName().isBlank()
                                ? submission.getStudentName()
                                : "submission_" + submission.getId()
                );
                if (payload != null && payload.bytes() != null && payload.bytes().length > 0) {
                    String filename = uniqueFileName(
                            payload.filename() == null ? baseName + ".pdf" : payload.filename(),
                            usedNames
                    );
                    zos.putNextEntry(new ZipEntry(filename));
                    zos.write(payload.bytes());
                    zos.closeEntry();
                    exportedCount++;
                } else {
                    String reason = payload == null || payload.reason() == null ? "unknown" : payload.reason();
                    missingSubmissions.add(baseName + " (submissionId=" + submission.getId() + ", reason=" + reason + ")");
                }
            }

            if (!missingSubmissions.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("These submissions have no exportable annotated report, score report, or original file:\n");
                for (String item : missingSubmissions) {
                    sb.append("- ").append(item).append('\n');
                }
                if (!generationErrors.isEmpty()) {
                    sb.append('\n').append("Annotated report generation errors:\n");
                    for (String item : generationErrors) {
                        sb.append("- ").append(item).append('\n');
                    }
                }
                zos.putNextEntry(new ZipEntry("README-missing-reports.txt"));
                zos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            } else if (!generationErrors.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("Some annotated reports failed to refresh before export:\n");
                for (String item : generationErrors) {
                    sb.append("- ").append(item).append('\n');
                }
                zos.putNextEntry(new ZipEntry("README-generation-warnings.txt"));
                zos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            zos.close();

            if (exportedCount == 0) {
                return ResponseEntity.status(409).body(Map.of(
                        "message", "No report files are available yet. Please wait for report generation to finish."
                ));
            }

            byte[] zipBytes = baos.toByteArray();
            String zipKey = "grading/exports/task-" + id + ".zip";
            storageService.putBytes(zipKey, zipBytes, "application/zip");

            ReportFileEntity reportFile = new ReportFileEntity();
            reportFile.setTask(task);
            reportFile.setFileType("zip");
            reportFile.setObjectKey(zipKey);
            reportFileRepo.save(reportFile);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=grading-export.zip")
                    .body(zipBytes);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("message", "Export failed: " + e.getMessage()));
        } finally {
            pool.shutdownNow();
        }
    }

    private ExportPayload loadExportPayload(GradingSubmissionEntity submission, ReportFileEntity reportFile) {
        String baseName = sanitizeFileName(
                submission.getStudentName() != null && !submission.getStudentName().isBlank()
                        ? submission.getStudentName()
                        : "submission_" + submission.getId()
        );
        try {
            if (reportFile != null && reportFile.getObjectKey() != null && !reportFile.getObjectKey().isBlank()) {
                byte[] reportBytes = storageService.getBytes(reportFile.getObjectKey());
                if (reportBytes != null && reportBytes.length > 0) {
                    return new ExportPayload(
                            submission.getId(),
                            resolveDownloadName(submission, reportFile),
                            reportBytes,
                            reportFile.getFileType()
                    );
                }
            }
        } catch (Exception ignored) {
        }

        try {
            if (submission.getPdfObjectKey() != null && !submission.getPdfObjectKey().isBlank()) {
                byte[] originBytes = storageService.getBytes(submission.getPdfObjectKey());
                if (originBytes != null && originBytes.length > 0) {
                    return new ExportPayload(
                            submission.getId(),
                            baseName + "-original" + resolveOriginalExtension(submission),
                            originBytes,
                            "original"
                    );
                }
            }
        } catch (Exception ignored) {
        }
        return new ExportPayload(submission.getId(), null, null, "missing");
    }

    private GradingTaskEntity requireOwnedTask(Long taskId, Long teacherId) {
        GradingTaskEntity task = taskRepo.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
        if (!Objects.equals(task.getTeacherId(), teacherId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
        return task;
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_");
    }

    private String uniqueFileName(String original, Set<String> usedNames) {
        if (usedNames.add(original)) {
            return original;
        }
        int dot = original.lastIndexOf('.');
        String base = dot >= 0 ? original.substring(0, dot) : original;
        String ext = dot >= 0 ? original.substring(dot) : "";
        int idx = 2;
        while (true) {
            String candidate = base + "-" + idx + ext;
            if (usedNames.add(candidate)) {
                return candidate;
            }
            idx++;
        }
    }

    private ReportFileEntity preferredReport(ReportFileEntity left, ReportFileEntity right) {
        return compareReports(left, right) >= 0 ? left : right;
    }

    private ReportFileEntity selectPreferredReport(List<ReportFileEntity> reports) {
        return reports.stream()
                .max(this::compareReports)
                .orElse(null);
    }

    private int compareReports(ReportFileEntity left, ReportFileEntity right) {
        int byPriority = Integer.compare(reportPriority(left), reportPriority(right));
        if (byPriority != 0) {
            return byPriority;
        }
        int byCreatedAt = Comparator.nullsLast(java.util.Comparator.<java.time.Instant>naturalOrder())
                .compare(left == null ? null : left.getCreatedAt(),
                        right == null ? null : right.getCreatedAt());
        if (byCreatedAt != 0) {
            return byCreatedAt;
        }
        return Comparator.nullsLast(java.util.Comparator.<Long>naturalOrder())
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
        if (report == null) {
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
        String originalFilename = sanitizeOriginalFilename(submission);
        String baseName = sanitizeFileName(
                submission.getStudentName() != null && !submission.getStudentName().isBlank()
                        ? submission.getStudentName()
                        : "submission_" + submission.getId()
        );
        if (report == null || report.getFileType() == null) {
            return baseName + ".bin";
        }
        return switch (report.getFileType()) {
            case AnnotatedStudentReportService.FILE_TYPE_ANNOTATED_DOCX ->
                    normalizeDownloadName(originalFilename, baseName, ".docx");
            case AnnotatedStudentReportService.FILE_TYPE_ANNOTATED_PDF ->
                    normalizeDownloadName(originalFilename, baseName, ".pdf");
            case "pdf" -> baseName + "-score-report.pdf";
            default -> baseName + ".bin";
        };
    }

    private String sanitizeOriginalFilename(GradingSubmissionEntity submission) {
        if (submission == null || submission.getOriginalFilename() == null || submission.getOriginalFilename().isBlank()) {
            return null;
        }
        return sanitizeFileName(submission.getOriginalFilename());
    }

    private String resolveOriginalExtension(GradingSubmissionEntity submission) {
        String filename = submission.getOriginalFilename();
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0) {
                return filename.substring(dot);
            }
        }
        return ".pdf";
    }

    /**
     * Batch-generate annotated reports for all scored submissions in a task.
     * This triggers the red-pen annotation rendering for each submission that
     * has scores but no annotated report yet.
     */
    @PostMapping("/tasks/{id}/generate-annotated-reports")
    public ResponseEntity<?> batchGenerateAnnotatedReports(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return doBatchGenerateAnnotatedReports(id, principal);
    }

    @GetMapping("/tasks/{id}/generate-annotated-reports")
    public ResponseEntity<?> batchGenerateAnnotatedReportsCompat(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return doBatchGenerateAnnotatedReports(id, principal);
    }

    private ResponseEntity<?> doBatchGenerateAnnotatedReports(
            Long id,
            UserPrincipal principal
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        requireOwnedTask(id, teacherId);

        List<GradingSubmissionEntity> submissions = submissionRepo.findAllByTaskId(id);
        if (submissions.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("message", "No submissions found"));
        }

        int generated = 0;
        int refreshed = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        for (GradingSubmissionEntity submission : submissions) {
            try {
                if (submission.getTotalScore() == null) {
                    skipped++;
                    continue;
                }

                boolean alreadyHasAnnotated = hasAnnotatedReport(submission.getId());
                gradingSubmissionService.refreshReviewAndAnnotatedReport(submission.getId(), teacherId);
                if (alreadyHasAnnotated) {
                    refreshed++;
                } else {
                    generated++;
                }
            } catch (Exception e) {
                String name = submission.getStudentName() != null ? submission.getStudentName() : "id=" + submission.getId();
                errors.add(name + ": " + e.getMessage());
            }
        }

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("total", submissions.size());
        result.put("generated", generated);
        result.put("refreshed", refreshed);
        result.put("skipped", skipped);
        result.put("errors", errors);
        return ResponseEntity.ok(result);
    }

    private List<String> ensureAnnotatedReports(List<GradingSubmissionEntity> submissions, Long teacherId) {
        // 导出只补齐缺失的批注报告，不再每次导出都重新生成（旧实现每次导出都
        // 调 refreshReviewAndAnnotatedReport 重跑 AI 评语+批注渲染，多份报告时
        // 轻松超过网关 60s 超时导致导出 504「用不了」）。
        List<String> errors = new ArrayList<>();
        for (GradingSubmissionEntity submission : submissions) {
            if (submission.getTotalScore() == null) {
                continue;
            }
            if (hasAnnotatedReport(submission.getId())) {
                continue;
            }
            try {
                gradingSubmissionService.ensureReviewAndAnnotatedReport(submission.getId(), teacherId);
            } catch (Exception e) {
                String name = submission.getStudentName() != null ? submission.getStudentName() : "id=" + submission.getId();
                errors.add(name + ": " + e.getMessage());
            }
        }
        return errors;
    }

    private boolean hasAnnotatedReport(Long submissionId) {
        return reportFileRepo.findAllBySubmissionIdAndFileTypeOrderByCreatedAtDesc(
                submissionId,
                AnnotatedStudentReportService.FILE_TYPE_ANNOTATED_DOCX
        ).stream().findFirst().isPresent() || reportFileRepo.findAllBySubmissionIdAndFileTypeOrderByCreatedAtDesc(
                submissionId,
                AnnotatedStudentReportService.FILE_TYPE_ANNOTATED_PDF
        ).stream().findFirst().isPresent();
    }
    private String normalizeDownloadName(String originalFilename, String baseName, String requiredExtension) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return baseName + requiredExtension;
        }
        String lower = originalFilename.toLowerCase();
        if (lower.endsWith(requiredExtension)) {
            return originalFilename;
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot >= 0) {
            return originalFilename.substring(0, dot) + requiredExtension;
        }
        return originalFilename + requiredExtension;
    }

    private record ExportPayload(Long submissionId, String filename, byte[] bytes, String reason) {}
}
