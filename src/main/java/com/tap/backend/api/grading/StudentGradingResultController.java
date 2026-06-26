package com.tap.backend.api.grading;

import com.tap.backend.security.StudentPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.StudentGradingResultService;
import com.tap.common.api.ApiResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/student/grading-results")
public class StudentGradingResultController {

    private final StudentPrincipalResolver studentPrincipalResolver;
    private final StudentGradingResultService resultService;

    public StudentGradingResultController(StudentPrincipalResolver studentPrincipalResolver,
                                          StudentGradingResultService resultService) {
        this.studentPrincipalResolver = studentPrincipalResolver;
        this.resultService = resultService;
    }

    @GetMapping("/experiments/{experimentId}")
    public ResponseEntity<?> experimentResult(
            @PathVariable Long experimentId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        String studentNo = studentPrincipalResolver.requireStudentId(principal);
        return ResponseEntity.ok(ApiResponse.of(resultService.findPublishedResult(studentNo, experimentId)));
    }

    @GetMapping("/submissions/{submissionId}/report")
    public ResponseEntity<?> downloadReport(
            @PathVariable Long submissionId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        String studentNo = studentPrincipalResolver.requireStudentId(principal);
        try {
            StudentGradingResultService.DownloadedReport report =
                    resultService.downloadPublishedReport(submissionId, studentNo);
            String encoded = URLEncoder.encode(report.filename(), StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .contentType(report.mediaType())
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                    .body(report.bytes());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
        }
    }
}
