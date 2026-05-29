package com.tap.backend.api.auth;

import com.tap.backend.security.TeacherPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.TeacherPtaCredentialService;
import com.tap.common.api.ApiResponse;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teachers/me/pta-credentials")
public class TeacherPtaCredentialController {
  private final TeacherPtaCredentialService teacherPtaCredentialService;
  private final TeacherPrincipalResolver teacherPrincipalResolver;

  public TeacherPtaCredentialController(
      TeacherPtaCredentialService teacherPtaCredentialService,
      TeacherPrincipalResolver teacherPrincipalResolver) {
    this.teacherPtaCredentialService = teacherPtaCredentialService;
    this.teacherPrincipalResolver = teacherPrincipalResolver;
  }

  public record SaveCredentialRequest(String ptaUsername, String ptaPassword) {}

  @GetMapping
  public ApiResponse<Map<String, Object>> getCurrentTeacherCredentials(
      @AuthenticationPrincipal UserPrincipal principal) {
    long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
    return ApiResponse.of(teacherPtaCredentialService.getCredentialSummary(teacherId));
  }

  @PutMapping
  public ApiResponse<Map<String, Object>> saveCurrentTeacherCredentials(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestBody SaveCredentialRequest request) {
    long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
    return ApiResponse.of(teacherPtaCredentialService.saveCredentials(
        teacherId,
        request.ptaUsername(),
        request.ptaPassword()));
  }

  @DeleteMapping
  public ApiResponse<Map<String, Object>> clearCurrentTeacherCredentials(
      @AuthenticationPrincipal UserPrincipal principal) {
    long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
    return ApiResponse.of(teacherPtaCredentialService.clearCredentials(teacherId));
  }
}
