package com.tap.backend.api.student;

import com.tap.backend.security.StudentPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.StudentGradingResultService;
import com.tap.common.api.ApiResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Student-facing, read-only access to published grading results and reports.
 * The current student is resolved from the login session; no student id is accepted
 * from the client. Visibility is enforced by the service using {@code published_at}.
 */
@RestController
@RequestMapping("/api/student/grading-results")
public class StudentGradingResultController {

    private final StudentGradingResultService gradingResultService;
    private final StudentPrincipalResolver studentPrincipalResolver;

    public StudentGradingResultController(
            StudentGradingResultService gradingResultService,
            StudentPrincipalResolver studentPrincipalResolver
    ) {
        this.gradingResultService = gradingResultService;
        this.studentPrincipalResolver = studentPrincipalResolver;
    }

    @GetMapping("/experiments/{experimentId}")
    public ResponseEntity<?> getPublishedResult(
            @PathVariable Long experimentId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        StudentPrincipalResolver.ResolvedStudent student = studentPrincipalResolver.requireStudent(principal);
        Map<String, Object> result = gradingResultService.getPublishedResult(experimentId, student.studentNum());
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @GetMapping("/submissions/{submissionId}/report")
    public ResponseEntity<?> downloadReport(
            @PathVariable Long submissionId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        StudentPrincipalResolver.ResolvedStudent student = studentPrincipalResolver.requireStudent(principal);
        StudentGradingResultService.ReportDownload download =
                gradingResultService.getPublishedReport(submissionId, student.studentNum());
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(download.mediaType())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(download.bytes());
    }
}
