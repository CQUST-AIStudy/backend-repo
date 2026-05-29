package com.tap.backend.api.classroom;

import com.tap.backend.security.TeacherPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.PtaCookieService;
import com.tap.common.api.ApiResponse;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pta-cookie")
public class PtaCookieController {

    private final PtaCookieService ptaCookieService;
    private final TeacherPrincipalResolver teacherPrincipalResolver;

    public PtaCookieController(
            PtaCookieService ptaCookieService,
            TeacherPrincipalResolver teacherPrincipalResolver
    ) {
        this.ptaCookieService = ptaCookieService;
        this.teacherPrincipalResolver = teacherPrincipalResolver;
    }

    record StatusReport(String status, String error) {}

    @PutMapping("/status")
    public ApiResponse<Void> reportStatus(@RequestBody StatusReport req) {
        ptaCookieService.reportStatus(req.status(), req.error());
        return ApiResponse.of(null);
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> getStatus(@AuthenticationPrincipal UserPrincipal principal) {
        teacherPrincipalResolver.requireTeacherId(principal);
        return ApiResponse.of(ptaCookieService.getStatusSnapshot());
    }

    record CookieSubmit(String cookies) {}

    @PostMapping("/update")
    public ApiResponse<Map<String, Object>> submitCookie(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody CookieSubmit req
    ) {
        teacherPrincipalResolver.requireTeacherId(principal);
        return ApiResponse.of(ptaCookieService.submitCookie(req.cookies()));
    }
}
