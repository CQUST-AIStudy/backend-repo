package com.tap.backend.api.student;

import com.tap.backend.security.StudentPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.NotificationService;
import com.tap.common.api.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Student-facing, read/ack access to their own in-app notifications. The current student is
 * resolved from the login session; no student id is accepted from the client.
 */
@RestController
@RequestMapping("/api/student/notifications")
public class StudentNotificationController {

    private final NotificationService notificationService;
    private final StudentPrincipalResolver studentPrincipalResolver;

    public StudentNotificationController(
            NotificationService notificationService,
            StudentPrincipalResolver studentPrincipalResolver
    ) {
        this.notificationService = notificationService;
        this.studentPrincipalResolver = studentPrincipalResolver;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        StudentPrincipalResolver.ResolvedStudent student = studentPrincipalResolver.requireStudent(principal);
        List<Map<String, Object>> items = notificationService.listForStudent(student.studentNum(), limit);
        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "items", items,
                "unreadCount", notificationService.unreadCount(student.studentNum())
        )));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<?> unreadCount(@AuthenticationPrincipal UserPrincipal principal) {
        StudentPrincipalResolver.ResolvedStudent student = studentPrincipalResolver.requireStudent(principal);
        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "unreadCount", notificationService.unreadCount(student.studentNum())
        )));
    }

    @PostMapping("/read")
    public ResponseEntity<?> markRead(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody(required = false) MarkReadRequest request
    ) {
        StudentPrincipalResolver.ResolvedStudent student = studentPrincipalResolver.requireStudent(principal);
        boolean all = request == null || request.all() || request.ids() == null || request.ids().isEmpty();
        int updated = all
                ? notificationService.markAllRead(student.studentNum())
                : notificationService.markRead(student.studentNum(), request.ids());
        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "updated", updated,
                "unreadCount", notificationService.unreadCount(student.studentNum())
        )));
    }

    public record MarkReadRequest(List<Long> ids, boolean all) {}
}
