package com.tap.backend.academic.controller;

import com.tap.backend.academic.security.LegacySessionAccessResolver;
import com.tap.backend.academic.security.StudentSessionResolver;
import com.tap.backend.academic.service.ProfileService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    @Autowired
    private ProfileService profileService;

    @Autowired
    private StudentSessionResolver studentSessionResolver;

    @Autowired
    private LegacySessionAccessResolver legacySessionAccessResolver;

    @GetMapping("/student/{studentId}")
    public ResponseEntity<Map<String, Object>> getStudentProfile(
            @PathVariable String studentId,
            HttpServletRequest request
    ) {
        String authorizedStudentId = legacySessionAccessResolver.requireStudentReadAccess(studentId, request);
        Map<String, Object> profile = profileService.getStudentProfile(authorizedStudentId);
        return ResponseEntity.ok(profile);
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getMyProfile(HttpServletRequest request) {
        String studentId = studentSessionResolver.requireStudentId(request);
        Map<String, Object> profile = profileService.getStudentProfile(studentId);
        return ResponseEntity.ok(profile);
    }

    @GetMapping("/class")
    public ResponseEntity<Map<String, Object>> getClassProfile(
            HttpServletRequest request,
            @RequestParam(required = false) String className) {
        String scopedClassName = normalizeClassName(className);
        if (scopedClassName == null) {
            try {
                scopedClassName = normalizeClassName(studentSessionResolver.requireStudent(request).getClassname());
            } catch (RuntimeException ignored) {
            }
        }
        Map<String, Object> profile = profileService.getClassProfile(scopedClassName);
        return ResponseEntity.ok(profile);
    }

    @GetMapping("/skilltree")
    public ResponseEntity<Map<String, Object>> getSkillTree() {
        Map<String, Object> tree = profileService.getSkillTreeConfig();
        return ResponseEntity.ok(tree);
    }

    @PostMapping("/feedback/refresh/{studentId}")
    public ResponseEntity<Map<String, Object>> refreshFeedback(
            @PathVariable String studentId,
            HttpServletRequest request
    ) {
        String authorizedStudentId = legacySessionAccessResolver.requireStudentReadAccess(studentId, request);
        Map<String, Object> result = profileService.refreshFeedback(authorizedStudentId);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/feedback/refresh/me")
    public ResponseEntity<Map<String, Object>> refreshMyFeedback(HttpServletRequest request) {
        String studentId = studentSessionResolver.requireStudentId(request);
        Map<String, Object> result = profileService.refreshFeedback(studentId);
        return ResponseEntity.ok(result);
    }

    private String normalizeClassName(String className) {
        if (className == null) {
            return null;
        }
        String trimmed = className.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
