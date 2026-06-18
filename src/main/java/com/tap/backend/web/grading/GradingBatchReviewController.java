package com.tap.backend.web.grading;

import com.tap.backend.dto.grading.AgentConfigDto;
import com.tap.backend.dto.grading.BatchReviewDto;
import com.tap.backend.security.TeacherPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.grading.GradingAgentConfigService;
import com.tap.backend.service.grading.GradingBatchReviewService;
import com.tap.common.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class GradingBatchReviewController {

    private final GradingBatchReviewService batchReviewService;
    private final GradingAgentConfigService configService;
    private final TeacherPrincipalResolver teacherPrincipalResolver;

    public GradingBatchReviewController(GradingBatchReviewService batchReviewService,
                                        GradingAgentConfigService configService,
                                        TeacherPrincipalResolver teacherPrincipalResolver) {
        this.batchReviewService = batchReviewService;
        this.configService = configService;
        this.teacherPrincipalResolver = teacherPrincipalResolver;
    }

    @GetMapping("/grading/tasks/{taskId}/batch-review")
    public ResponseEntity<ApiResponse<BatchReviewDto>> getBatchReview(
            @PathVariable Long taskId,
            @AuthenticationPrincipal UserPrincipal principal) {
        teacherPrincipalResolver.requireTeacherId(principal);
        return ResponseEntity.ok(ApiResponse.of(batchReviewService.getReview(taskId)));
    }

    @PostMapping("/grading/tasks/{taskId}/batch-review")
    public ResponseEntity<ApiResponse<BatchReviewDto>> triggerBatchReview(
            @PathVariable Long taskId,
            @AuthenticationPrincipal UserPrincipal principal) {
        teacherPrincipalResolver.requireTeacherId(principal);
        batchReviewService.triggerGeneration(taskId);
        return ResponseEntity.accepted().body(ApiResponse.of(batchReviewService.getReview(taskId)));
    }

    @GetMapping("/agent-configs/{code}")
    public ResponseEntity<ApiResponse<AgentConfigDto>> getAgentConfig(
            @PathVariable String code,
            @AuthenticationPrincipal UserPrincipal principal) {
        teacherPrincipalResolver.requireTeacherId(principal);
        return configService.findByCode(code)
                .map(dto -> ResponseEntity.ok(ApiResponse.of(dto)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/agent-configs/{code}")
    public ResponseEntity<ApiResponse<AgentConfigDto>> updateAgentConfig(
            @PathVariable String code,
            @RequestBody AgentConfigDto dto,
            @AuthenticationPrincipal UserPrincipal principal) {
        teacherPrincipalResolver.requireTeacherId(principal);
        return ResponseEntity.ok(ApiResponse.of(configService.update(code, dto)));
    }
}
