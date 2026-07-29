package com.tap.backend.academic.controller;

import com.tap.backend.academic.entity.UserEntity;
import com.tap.backend.academic.security.LegacySessionAccessResolver;
import com.tap.backend.academic.security.StudentSessionResolver;
import com.tap.backend.academic.service.ProfileService;
import com.tap.backend.domain.classroom.TeachingClassEntity;
import com.tap.backend.service.TeachingClassService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private static final Logger logger = LoggerFactory.getLogger(ProfileController.class);

    @Autowired
    private ProfileService profileService;

    @Autowired
    private StudentSessionResolver studentSessionResolver;

    @Autowired
    private LegacySessionAccessResolver legacySessionAccessResolver;

    @Autowired
    private TeachingClassService teachingClassService;

    @Value("${tap.recommendation.base-url:http://127.0.0.1:8003}")
    private String recommendationBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

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
        UserEntity user = legacySessionAccessResolver.requireAuthenticated(request);
        String role = normalizeRole(user.getRole());

        if ("student".equals(role)) {
            try {
                String studentId = studentSessionResolver.requireStudentId(request);
                Map<String, Object> profile = profileService.getStudentProfile(studentId);
                // 补充基础用户信息
                profile.putIfAbsent("username", user.getUsername());
                profile.putIfAbsent("email", emptyIfNull(user.getEmail()));
                profile.putIfAbsent("phone", "");
                profile.putIfAbsent("usernum", user.getUsernum());
                profile.putIfAbsent("class", user.getClassname());
                return ResponseEntity.ok(profile);
            } catch (Exception e) {
                // 学生画像获取失败时，回退到基础信息
                return ResponseEntity.ok(profileService.getCurrentUserProfile(user));
            }
        }

        // 管理员 / 教师：返回基础个人信息
        return ResponseEntity.ok(profileService.getCurrentUserProfile(user));
    }

    @PutMapping("/me")
    public ResponseEntity<Map<String, Object>> updateMyProfile(
            @RequestBody Map<String, String> data,
            HttpServletRequest request) {
        UserEntity user = legacySessionAccessResolver.requireAuthenticated(request);
        Map<String, Object> result = profileService.updateCurrentUserProfile(user, data);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/class")
    public ResponseEntity<Map<String, Object>> getClassProfile(
            HttpServletRequest request,
            @RequestParam Long classId) {
        UserEntity user = legacySessionAccessResolver.requireTeacherOrAdmin(request);
        TeachingClassEntity teachingClass = teachingClassService.getClassForTeacher(classId, (long) user.getId());
        Map<String, Object> profile = profileService.getClassProfile(
                classId,
                teachingClass.getName(),
                teachingClass.getCourseName());
        return ResponseEntity.ok(profile);
    }

    @GetMapping("/skilltree")
    public ResponseEntity<Map<String, Object>> getSkillTree() {
        Map<String, Object> tree = profileService.getSkillTreeConfig();
        return ResponseEntity.ok(tree);
    }

    @GetMapping("/skill-states")
    public ResponseEntity<Map<String, Object>> getSkillStates(HttpServletRequest request) {
        String studentId = studentSessionResolver.requireStudentId(request);
        try {
            String url = recommendationBaseUrl.replaceAll("/+$", "") + "/ai/profile/" + studentId;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class).getBody();
            if (responseBody != null && responseBody.get("data") != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> skills = (List<Map<String, Object>>) responseBody.get("data");
                Map<String, Object> result = new java.util.LinkedHashMap<>();
                result.put("success", true);
                result.put("skills", skills);
                return ResponseEntity.ok(result);
            }
            return ResponseEntity.ok(Map.of("success", true, "skills", List.of()));
        } catch (Exception e) {
            logger.warn("Failed to fetch skill states from recommendation service for student {}: {}", studentId, e.getMessage());
            return ResponseEntity.ok(Map.of("success", true, "skills", List.of()));
        }
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

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "student";
        }
        return role.trim().toLowerCase();
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
