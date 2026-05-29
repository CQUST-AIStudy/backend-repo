package com.tap.backend.academic.controller;

import com.tap.backend.academic.security.LegacySessionAccessResolver;
import com.tap.backend.service.AdminDashboardService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin-dashboard")
public class AdminDashboardController {

  private final AdminDashboardService adminDashboardService;
  private final LegacySessionAccessResolver legacySessionAccessResolver;

  public AdminDashboardController(
      AdminDashboardService adminDashboardService,
      LegacySessionAccessResolver legacySessionAccessResolver) {
    this.adminDashboardService = adminDashboardService;
    this.legacySessionAccessResolver = legacySessionAccessResolver;
  }

  record SyncRequest(String mode, Boolean force) {}

  @GetMapping("/overview")
  public ResponseEntity<Map<String, Object>> getOverview(HttpServletRequest request) {
    ResponseEntity<Map<String, Object>> authFailure = ensureAdmin(request);
    if (authFailure != null) {
      return authFailure;
    }
    return ResponseEntity.ok(adminDashboardService.getOverview());
  }

  @PostMapping("/classes/{classId}/sync")
  public ResponseEntity<Map<String, Object>> triggerClassSync(
      @PathVariable Long classId,
      @RequestBody(required = false) SyncRequest req,
      HttpServletRequest request) {
    ResponseEntity<Map<String, Object>> authFailure = ensureAdmin(request);
    if (authFailure != null) {
      return authFailure;
    }

    String mode = req == null ? "incremental" : req.mode();
    boolean force = req != null && Boolean.TRUE.equals(req.force());
    return ResponseEntity.ok(adminDashboardService.triggerClassSync(classId, mode, force));
  }

  private ResponseEntity<Map<String, Object>> ensureAdmin(HttpServletRequest request) {
    try {
      legacySessionAccessResolver.requireAdmin(request);
      return null;
    } catch (ResponseStatusException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        return error(HttpStatus.UNAUTHORIZED, "authentication required");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        return error(HttpStatus.FORBIDDEN, "admin role required");
      }
      return error(HttpStatus.BAD_REQUEST, e.getReason() == null ? "admin access failed" : e.getReason());
    }
  }

  private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", false);
    body.put("message", message);
    return ResponseEntity.status(status).body(body);
  }
}
