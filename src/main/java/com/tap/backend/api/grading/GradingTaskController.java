package com.tap.backend.api.grading;

import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.domain.grading.GradingTaskEntity;
import com.tap.backend.domain.grading.GradingTaskStatus;
import com.tap.backend.domain.grading.ReportFileEntity;
import com.tap.backend.repo.ReportFileRepository;
import com.tap.backend.security.TeacherPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.AnnotatedStudentReportService;
import com.tap.backend.service.GradingSubmissionService;
import com.tap.backend.service.GradingTaskService;
import com.tap.backend.service.TeacherSignatureService;
import com.tap.common.api.ApiResponse;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/grading/tasks")
public class GradingTaskController {

    private final GradingTaskService taskService;
    private final GradingSubmissionService gradingSubmissionService;
    private final ReportFileRepository reportFileRepo;
    private final com.tap.backend.repo.GradingBatchRepository batchRepo;
    private final TeacherPrincipalResolver teacherPrincipalResolver;
    private final TeacherSignatureService signatureService;

    public GradingTaskController(
            GradingTaskService taskService,
            GradingSubmissionService gradingSubmissionService,
            ReportFileRepository reportFileRepo,
            com.tap.backend.repo.GradingBatchRepository batchRepo,
            TeacherPrincipalResolver teacherPrincipalResolver,
            TeacherSignatureService signatureService
    ) {
        this.taskService = taskService;
        this.gradingSubmissionService = gradingSubmissionService;
        this.reportFileRepo = reportFileRepo;
        this.batchRepo = batchRepo;
        this.teacherPrincipalResolver = teacherPrincipalResolver;
        this.signatureService = signatureService;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("files") MultipartFile[] files,
            @RequestParam("rubricId") Long rubricId,
            @RequestParam(value = "experimentId", required = false) Long experimentId,
            @RequestParam(value = "classId", required = false) Long classId,
            @RequestParam(value = "teacherSignature", required = false) String teacherSignature,
            @RequestParam(value = "scoreRangeMin", required = false) java.math.BigDecimal scoreRangeMin,
            @RequestParam(value = "scoreRangeMax", required = false) java.math.BigDecimal scoreRangeMax,
            @RequestParam(value = "batchId", required = false) Long batchId,
            @RequestParam(value = "batchName", required = false) String batchName
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        try {
            var result = taskService.createTask(
                    teacherId,
                    experimentId,
                    classId,
                    teacherSignature,
                    rubricId,
                    scoreRangeMin,
                    scoreRangeMax,
                    files,
                    batchId,
                    batchName
            );
            // Auto-save teacher signature for future use
            if (teacherSignature != null && !teacherSignature.isBlank()) {
                signatureService.ensureExists(teacherId, teacherSignature);
            }
            return ResponseEntity.ok(ApiResponse.of(result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        GradingTaskStatus statusEnum = status != null ? GradingTaskStatus.valueOf(status) : null;
        Page<GradingTaskEntity> tasks = taskService.getTaskList(
                teacherId,
                statusEnum,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        );

        List<Map<String, Object>> content = tasks.getContent().stream()
                .map(task -> new LinkedHashMap<String, Object>(toListDto(task)))
                .map(dto -> (Map<String, Object>) dto)
                .toList();
        attachBatchNames(content);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("totalElements", tasks.getTotalElements());
        result.put("totalPages", tasks.getTotalPages());
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        try {
            GradingTaskEntity task = taskService.getTaskDetail(id, teacherId);
            List<GradingSubmissionEntity> submissions = taskService.getTaskSubmissions(id, teacherId);
            Map<Long, ReportFileEntity> preferredReports = buildPreferredReportMap(id);

            if (backfillSubmissionArtifacts(submissions, preferredReports, teacherId)) {
                submissions = taskService.getTaskSubmissions(id, teacherId);
                preferredReports = buildPreferredReportMap(id);
            }

            final Map<Long, ReportFileEntity> reportMap = preferredReports;
            Map<String, Object> dto = new LinkedHashMap<>(toListDto(task));
            attachBatchNames(List.of(dto));
            dto.put("submissions", submissions.stream().map(submission -> toSubDto(submission, reportMap.get(submission.getId()))).toList());
            return ResponseEntity.ok(ApiResponse.of(dto));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<?> retry(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        try {
            taskService.retryFailed(id, teacherId);
            return ResponseEntity.ok(ApiResponse.of(Map.of("message", "Retry initiated")));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/{id}/signature")
    public ResponseEntity<?> updateTeacherSignature(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody TeacherSignatureRequest request
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        try {
            String signature = taskService.updateTeacherSignature(id, teacherId, request.teacherSignature());
            return ResponseEntity.ok(ApiResponse.of(Map.of("teacherSignature", signature)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/{id}/requeue-processing")
    public ResponseEntity<?> requeueProcessing(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        try {
            int count = taskService.forceRequeueProcessing(id, teacherId);
            return ResponseEntity.ok(ApiResponse.of(Map.of("message", "Requeued processing submissions", "count", count)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        try {
            taskService.deleteOwnedTask(id, teacherId);
            return ResponseEntity.ok(ApiResponse.of(Map.of("message", "Deleted successfully")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/{id}/export-excel")
    public ResponseEntity<?> exportExcel(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody ExcelExportRequest req
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        try {
            byte[] excel = taskService.exportExcel(id, teacherId, req.submissionIds(), req.includeComments());
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=grading-export-" + id + ".xlsx")
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .body(excel);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    public record ExcelExportRequest(List<Long> submissionIds, boolean includeComments) {}
    public record TeacherSignatureRequest(String teacherSignature) {}

    private void attachBatchNames(List<Map<String, Object>> taskDtos) {
        List<Long> batchIds = taskDtos.stream()
                .map(dto -> (Long) dto.get("batchId"))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (batchIds.isEmpty()) {
            return;
        }
        Map<Long, com.tap.backend.domain.grading.GradingBatchEntity> batchMap = new HashMap<>();
        batchRepo.findAllById(batchIds).forEach(batch -> batchMap.put(batch.getId(), batch));
        for (Map<String, Object> dto : taskDtos) {
            Long batchId = (Long) dto.get("batchId");
            var batch = batchId != null ? batchMap.get(batchId) : null;
            if (batch != null) {
                dto.put("batchName", batch.getName());
                dto.put("batchDisplayCode", batch.getDisplayCode());
            }
        }
    }

    private Map<String, Object> toListDto(GradingTaskEntity task) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("taskId", task.getId());
        dto.put("displayCode", task.getDisplayCode());
        dto.put("batchId", task.getBatchId());
        dto.put("status", task.getStatus().name());
        dto.put("totalCount", task.getTotalCount());
        dto.put("completedCount", task.getCompletedCount());
        dto.put("failedCount", task.getFailedCount());
        dto.put("rubricId", task.getRubricId());
        dto.put("experimentId", task.getExperimentId());
        dto.put("teacherSignature", task.getTeacherSignature());
        dto.put("createdAt", task.getCreatedAt().toString());
        return dto;
    }

    private Map<Long, ReportFileEntity> buildPreferredReportMap(Long taskId) {
        Map<Long, ReportFileEntity> result = new HashMap<>();
        for (ReportFileEntity reportFile : reportFileRepo.findAllByTaskId(taskId)) {
            if (reportFile.getSubmissionId() == null) {
                continue;
            }
            result.merge(reportFile.getSubmissionId(), reportFile, this::preferredReport);
        }
        return result;
    }

    private ReportFileEntity preferredReport(ReportFileEntity left, ReportFileEntity right) {
        return compareReports(left, right) >= 0 ? left : right;
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

    private boolean backfillSubmissionArtifacts(
            List<GradingSubmissionEntity> submissions,
            Map<Long, ReportFileEntity> preferredReports,
            Long teacherId
    ) {
        boolean changed = false;
        for (GradingSubmissionEntity submission : submissions) {
            if (submission.getTotalScore() == null) {
                continue;
            }
            ReportFileEntity preferredReport = preferredReports.get(submission.getId());
            boolean hasAnnotatedReport = preferredReport != null && reportPriority(preferredReport) >= 3;
            boolean missingReview = submission.getFinalReviewComment() == null || submission.getFinalReviewComment().isBlank();
            if (!missingReview && hasAnnotatedReport) {
                continue;
            }
            try {
                gradingSubmissionService.ensureReviewAndAnnotatedReport(submission.getId(), teacherId);
                changed = true;
            } catch (Exception ignored) {
            }
        }
        return changed;
    }

    private Map<String, Object> toSubDto(GradingSubmissionEntity submission, ReportFileEntity preferredReport) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("submissionId", submission.getId());
        dto.put("studentName", submission.getStudentName());
        dto.put("className", submission.getClassName());
        dto.put("studentNo", submission.getStudentNo());
        dto.put("status", submission.getStatus().name());
        dto.put("totalScore", submission.getTotalScore());
        dto.put("originalFilename", submission.getOriginalFilename());
        dto.put("finalReviewComment", submission.getFinalReviewComment());
        dto.put("hasDownloadableReport", preferredReport != null);
        dto.put("preferredReportFileType", preferredReport != null ? preferredReport.getFileType() : null);
        return dto;
    }
}
