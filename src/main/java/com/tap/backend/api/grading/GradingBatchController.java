package com.tap.backend.api.grading;

import com.tap.backend.domain.grading.GradingBatchEntity;
import com.tap.backend.domain.grading.GradingTaskEntity;
import com.tap.backend.domain.grading.GradingTaskStatus;
import com.tap.backend.repo.GradingBatchRepository;
import com.tap.backend.repo.GradingTaskRepository;
import com.tap.backend.security.TeacherPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.GradingBatchExportService;
import com.tap.common.api.ApiResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/grading")
public class GradingBatchController {

    private static final String EXCEL_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final GradingBatchRepository batchRepo;
    private final GradingTaskRepository taskRepo;
    private final GradingBatchExportService exportService;
    private final TeacherPrincipalResolver teacherPrincipalResolver;

    public GradingBatchController(GradingBatchRepository batchRepo,
                                  GradingTaskRepository taskRepo,
                                  GradingBatchExportService exportService,
                                  TeacherPrincipalResolver teacherPrincipalResolver) {
        this.batchRepo = batchRepo;
        this.taskRepo = taskRepo;
        this.exportService = exportService;
        this.teacherPrincipalResolver = teacherPrincipalResolver;
    }

    @GetMapping("/batches")
    public ResponseEntity<?> listBatches(@AuthenticationPrincipal UserPrincipal principal) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        List<GradingBatchEntity> batches = batchRepo.findAllByTeacherIdOrderByCreatedAtDesc(teacherId);

        Map<Long, List<GradingTaskEntity>> tasksByBatch = new HashMap<>();
        if (!batches.isEmpty()) {
            List<Long> batchIds = batches.stream().map(GradingBatchEntity::getId).toList();
            for (GradingTaskEntity task : taskRepo.findAllByBatchIdIn(batchIds)) {
                tasksByBatch.computeIfAbsent(task.getBatchId(), k -> new ArrayList<>()).add(task);
            }
        }

        List<Map<String, Object>> content = new ArrayList<>();
        for (GradingBatchEntity batch : batches) {
            List<GradingTaskEntity> tasks = tasksByBatch.getOrDefault(batch.getId(), List.of());
            long completedTasks = tasks.stream().filter(t -> t.getStatus() == GradingTaskStatus.COMPLETED).count();
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("batchId", batch.getId());
            dto.put("displayCode", batch.getDisplayCode());
            dto.put("name", batch.getName());
            dto.put("status", batch.getStatus());
            dto.put("taskCount", tasks.size());
            dto.put("completedTaskCount", completedTasks);
            dto.put("createdAt", batch.getCreatedAt() != null ? batch.getCreatedAt().toString() : null);
            content.add(dto);
        }
        return ResponseEntity.ok(ApiResponse.of(Map.of("content", content)));
    }

    @PostMapping("/batches/{id}/export-excel")
    public ResponseEntity<?> exportBatchExcel(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody(required = false) MergedExcelExportRequest req
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        boolean includeComments = req == null || req.includeComments() == null || req.includeComments();
        try {
            byte[] excel = exportService.exportBatchExcel(id, teacherId, includeComments);
            return excelResponse(excel, "grading-batch-" + id + "-export.xlsx");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/tasks/export-excel-merged")
    public ResponseEntity<?> exportSelectedTasksExcel(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody MergedExcelExportRequest req
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        boolean includeComments = req.includeComments() == null || req.includeComments();
        try {
            byte[] excel = exportService.exportSelectedTasksExcel(req.taskIds(), teacherId, includeComments);
            return excelResponse(excel, "grading-tasks-merged-export.xlsx");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    private ResponseEntity<byte[]> excelResponse(byte[] excel, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .header(HttpHeaders.CONTENT_TYPE, EXCEL_CONTENT_TYPE)
                .body(excel);
    }

    public record MergedExcelExportRequest(List<Long> taskIds, Boolean includeComments) {}
}
