package com.tap.backend.api.rag;

import com.tap.backend.security.PrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.CourseSpaceService;
import com.tap.backend.rag.RagAnalyticsService;
import com.tap.common.api.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/course-spaces/{id}/analytics")
public class RagAnalyticsController {

    private final RagAnalyticsService analyticsService;
    private final CourseSpaceService courseSpaceService;
    private final PrincipalResolver principalResolver;

    public RagAnalyticsController(RagAnalyticsService analyticsService,
                                 CourseSpaceService courseSpaceService,
                                 PrincipalResolver principalResolver) {
        this.analyticsService = analyticsService;
        this.courseSpaceService = courseSpaceService;
        this.principalResolver = principalResolver;
    }

    @GetMapping("/hot-questions")
    public ApiResponse<List<RagAnalyticsService.QuestionRank>> hotQuestions(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long courseSpaceId,
            @RequestParam(defaultValue = "20") int top) {
        var resolved = principalResolver.resolve(principal);
        courseSpaceService.requireOwnedSpace(courseSpaceId, resolved.userId());
        return ApiResponse.of(analyticsService.getHotQuestions(courseSpaceId, top));
    }

    @GetMapping("/hit-rate")
    public ApiResponse<Map<String, Object>> hitRate(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long courseSpaceId,
            @RequestParam(defaultValue = "0.4") double threshold) {
        var resolved = principalResolver.resolve(principal);
        courseSpaceService.requireOwnedSpace(courseSpaceId, resolved.userId());
        return ApiResponse.of(Map.of("hitRate", analyticsService.getHitRate(courseSpaceId, threshold)));
    }

    @GetMapping("/citation-coverage")
    public ApiResponse<Map<String, Long>> citationCoverage(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long courseSpaceId) {
        var resolved = principalResolver.resolve(principal);
        courseSpaceService.requireOwnedSpace(courseSpaceId, resolved.userId());
        return ApiResponse.of(analyticsService.getCitationCoverage(courseSpaceId));
    }

    @GetMapping("/web-trigger-rate")
    public ApiResponse<Map<String, Object>> webTriggerRate(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long courseSpaceId) {
        var resolved = principalResolver.resolve(principal);
        courseSpaceService.requireOwnedSpace(courseSpaceId, resolved.userId());
        return ApiResponse.of(Map.of("webTriggerRate", analyticsService.getWebTriggerRate(courseSpaceId)));
    }

    @GetMapping("/feedback-stats")
    public ApiResponse<RagAnalyticsService.FeedbackStats> feedbackStats(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long courseSpaceId) {
        var resolved = principalResolver.resolve(principal);
        courseSpaceService.requireOwnedSpace(courseSpaceId, resolved.userId());
        return ApiResponse.of(analyticsService.getFeedbackStats(courseSpaceId));
    }

    @GetMapping("/resource-gaps")
    public ApiResponse<List<RagAnalyticsService.GapAlert>> resourceGaps(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long courseSpaceId,
            @RequestParam(defaultValue = "0.4") double coverageThreshold,
            @RequestParam(defaultValue = "3") int minFrequency) {
        var resolved = principalResolver.resolve(principal);
        courseSpaceService.requireOwnedSpace(courseSpaceId, resolved.userId());
        return ApiResponse.of(analyticsService.getResourceGaps(courseSpaceId, coverageThreshold, minFrequency));
    }
}
