package com.tap.backend.api.teaching;

import com.tap.backend.security.TeacherPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.TeachingAdviceService;
import com.tap.common.api.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teacher/teaching-advice")
public class TeachingAdviceController {
    private final TeachingAdviceService adviceService;
    private final TeacherPrincipalResolver principalResolver;

    public TeachingAdviceController(
            TeachingAdviceService adviceService,
            TeacherPrincipalResolver principalResolver
    ) {
        this.adviceService = adviceService;
        this.principalResolver = principalResolver;
    }

    public record GenerateRequest(
            String scopeLevel,
            Long classId,
            Long experimentId,
            Boolean includeHistory
    ) {}

    @GetMapping("/options")
    public ApiResponse<Map<String, Object>> options(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.of(adviceService.options(principalResolver.requireTeacherId(principal)));
    }

    @GetMapping("/context")
    public ApiResponse<Map<String, Object>> context(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "CLASS") String scopeLevel,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Long experimentId,
            @RequestParam(defaultValue = "false") boolean includeHistory
    ) {
        return ApiResponse.of(adviceService.context(
                principalResolver.requireTeacherId(principal), scopeLevel, classId, experimentId, includeHistory));
    }

    @PostMapping("/reports")
    public ApiResponse<Map<String, Object>> generate(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody GenerateRequest request
    ) {
        return ApiResponse.of(adviceService.generate(
                principalResolver.requireTeacherId(principal),
                request == null ? null : request.scopeLevel(),
                request == null ? null : request.classId(),
                request == null ? null : request.experimentId(),
                request != null && Boolean.TRUE.equals(request.includeHistory())
        ));
    }

    @GetMapping("/reports")
    public ApiResponse<List<Map<String, Object>>> list(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.of(adviceService.listReports(principalResolver.requireTeacherId(principal)));
    }

    @GetMapping("/reports/{reportId}")
    public ApiResponse<Map<String, Object>> get(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long reportId
    ) {
        return ApiResponse.of(adviceService.getReport(principalResolver.requireTeacherId(principal), reportId));
    }
}
