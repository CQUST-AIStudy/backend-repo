package com.tap.backend.api.classroom;

import com.tap.backend.security.TeacherPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.PtaSyncService;
import com.tap.common.api.ApiResponse;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/classes/{classId}/pta-sync")
public class PtaSyncController {

    private final PtaSyncService syncService;
    private final TeacherPrincipalResolver teacherPrincipalResolver;

    public PtaSyncController(
            PtaSyncService syncService,
            TeacherPrincipalResolver teacherPrincipalResolver
    ) {
        this.syncService = syncService;
        this.teacherPrincipalResolver = teacherPrincipalResolver;
    }

    record SyncConfigRequest(
            String ptaKeyword,
            Boolean syncEnabled,
            String ptaProblemSetId,
            String ptaProblemSetName,
            String ptaGroupId,
            String ptaGroupName
    ) {}

    @PutMapping
    public ApiResponse<Map<String, Object>> updateConfig(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long classId,
            @RequestBody SyncConfigRequest req
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        return ApiResponse.of(syncService.updateSyncConfig(
                classId,
                teacherId,
                req.ptaKeyword(),
                req.syncEnabled(),
                req.ptaProblemSetId(),
                req.ptaProblemSetName(),
                req.ptaGroupId(),
                req.ptaGroupName()));
    }

    record TriggerRequest(
            String ptaUsername,
            String ptaPassword,
            String ptaKeyword,
            String ptaGroupId,
            String ptaGroupName,
            String mode,
            Boolean force,
            Boolean bypassCooldown,
            Boolean dryRun
    ) {}

    record ConnectionTestRequest(
            String ptaUsername,
            String ptaPassword,
            String ptaKeyword,
            String ptaProblemSetId,
            String ptaGroupId,
            String ptaGroupName
    ) {}

    @PostMapping("/trigger")
    public ApiResponse<Map<String, Object>> trigger(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long classId,
            @RequestBody(required = false) TriggerRequest req
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        return ApiResponse.of(syncService.triggerSync(
                classId,
                teacherId,
                req == null ? null : req.ptaUsername(),
                req == null ? null : req.ptaPassword(),
                req == null ? null : req.ptaKeyword(),
                req == null ? null : req.ptaGroupId(),
                req == null ? null : req.ptaGroupName(),
                req == null ? null : req.mode(),
                req == null ? null : req.force(),
                req == null ? null : req.bypassCooldown(),
                req == null ? null : req.dryRun()));
    }

    @PostMapping("/test")
    public ApiResponse<Map<String, Object>> testConnection(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long classId,
            @RequestBody(required = false) ConnectionTestRequest req
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        return ApiResponse.of(syncService.testConnection(
                classId,
                teacherId,
                req == null ? null : req.ptaUsername(),
                req == null ? null : req.ptaPassword(),
                req == null ? null : req.ptaKeyword(),
                req == null ? null : req.ptaProblemSetId(),
                req == null ? null : req.ptaGroupId(),
                req == null ? null : req.ptaGroupName()));
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long classId
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        return ApiResponse.of(syncService.getSyncStatus(classId, teacherId));
    }

    @GetMapping("/snapshot")
    public ApiResponse<Map<String, Object>> snapshot(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long classId
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        return ApiResponse.of(syncService.getSnapshotIntegrity(classId, teacherId));
    }

    @PostMapping("/import-students")
    public ApiResponse<Map<String, Object>> importStudents(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long classId
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        return ApiResponse.of(syncService.importStudents(classId, teacherId));
    }

    record CallbackRequest(String status, String taskId) {}

    @PutMapping("/callback")
    public ApiResponse<Void> callback(@PathVariable Long classId, @RequestBody CallbackRequest req) {
        syncService.updateSyncResult(classId, req.status(), req.taskId());
        return ApiResponse.of(null);
    }
}
