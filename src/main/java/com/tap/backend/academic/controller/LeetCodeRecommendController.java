package com.tap.backend.academic.controller;

import com.tap.backend.academic.entity.LeetCodeRecommendItem;
import com.tap.backend.academic.entity.LeetCodeRecommendRequest;
import com.tap.backend.academic.entity.UserEntity;
import com.tap.backend.academic.security.LegacySessionAccessResolver;
import com.tap.backend.academic.security.StudentSessionResolver;
import com.tap.backend.academic.service.LeetCodeRecommendationService;
import com.tap.backend.academic.service.LeetCodeSyncService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/recommendations/leetcode")
public class LeetCodeRecommendController {

    private static final Logger logger = LoggerFactory.getLogger(LeetCodeRecommendController.class);

    private final LeetCodeRecommendationService recommendationService;
    private final LeetCodeSyncService syncService;
    private final StudentSessionResolver studentSessionResolver;
    private final LegacySessionAccessResolver legacySessionAccessResolver;

    public LeetCodeRecommendController(
            @Qualifier("intelligentRecommendationService") LeetCodeRecommendationService recommendationService,
            LeetCodeSyncService syncService,
            StudentSessionResolver studentSessionResolver,
            LegacySessionAccessResolver legacySessionAccessResolver) {
        this.recommendationService = recommendationService;
        this.syncService = syncService;
        this.studentSessionResolver = studentSessionResolver;
        this.legacySessionAccessResolver = legacySessionAccessResolver;
    }

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateRecommendation(
            @RequestParam(defaultValue = "20") Integer limit,
            @RequestParam(defaultValue = "default") String scene,
            @RequestParam(required = false) Integer studentId,
            HttpServletRequest request) {
        try {
            Integer currentStudentId = requireCurrentStudentId(studentId, request);
            int validatedLimit = validateLimit(limit);
            String validatedScene = normalizeScene(scene);
            logger.info("Generate recommendation request studentId={} limit={} scene={}",
                    currentStudentId, validatedLimit, validatedScene);

            String requestId = recommendationService.generateRecommendation(currentStudentId, validatedLimit, validatedScene);
            Map<String, Object> response = successBody();
            response.put("requestId", requestId);
            response.put("status", "pending");
            response.put("message", "recommendation request accepted");
            return ResponseEntity.ok(response);
        } catch (ResponseStatusException e) {
            return error(e.getStatusCode(), e.getReason());
        } catch (Exception e) {
            logger.error("Failed to generate recommendation", e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "failed to generate recommendation: " + e.getMessage());
        }
    }

    @GetMapping("/result/{requestId}")
    public ResponseEntity<Map<String, Object>> getRecommendationResult(
            @PathVariable String requestId,
            HttpServletRequest request) {
        try {
            String validatedRequestId = requireText(requestId, "requestId");
            LeetCodeRecommendRequest recommendationRequest = recommendationService.getRecommendationResult(validatedRequestId);
            if (recommendationRequest == null) {
                return error(HttpStatus.NOT_FOUND, "recommendation request not found");
            }

            authorizeRecommendationAccess(recommendationRequest.getStudentId(), request);

            Map<String, Object> response = successBody();
            response.put("requestId", recommendationRequest.getRequestId());
            response.put("status", recommendationRequest.getStatus());
            response.put("studentId", recommendationRequest.getStudentId());
            response.put("scene", recommendationRequest.getScene());
            response.put("requestLimit", recommendationRequest.getRequestLimit());
            response.put("createdAt", recommendationRequest.getCreatedAt());
            response.put("finishedAt", recommendationRequest.getFinishedAt());

            if (recommendationRequest.isCompleted()) {
                List<LeetCodeRecommendItem> items = recommendationService.getRecommendationItems(validatedRequestId);
                response.put("items", items);
                response.put("itemCount", items.size());
            } else if (recommendationRequest.isFailed()) {
                response.put("errorMessage", recommendationRequest.getErrorMessage());
            }

            return ResponseEntity.ok(response);
        } catch (ResponseStatusException e) {
            return error(e.getStatusCode(), e.getReason());
        } catch (Exception e) {
            logger.error("Failed to load recommendation result", e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "failed to load recommendation result: " + e.getMessage());
        }
    }

    @GetMapping("/sync")
    public ResponseEntity<Map<String, Object>> generateRecommendationSync(
            @RequestParam(defaultValue = "20") Integer limit,
            @RequestParam(required = false) Integer studentId,
            HttpServletRequest request) {
        try {
            Integer currentStudentId = requireCurrentStudentId(studentId, request);
            int validatedLimit = validateLimit(limit);
            logger.info("Generate sync recommendation request studentId={} limit={}", currentStudentId, validatedLimit);

            List<LeetCodeRecommendItem> items = recommendationService.generateRecommendationSync(currentStudentId, validatedLimit);
            Map<String, Object> response = successBody();
            response.put("items", items);
            response.put("itemCount", items.size());
            response.put("message", "recommendation generated");
            return ResponseEntity.ok(response);
        } catch (ResponseStatusException e) {
            return error(e.getStatusCode(), e.getReason());
        } catch (Exception e) {
            logger.error("Failed to generate sync recommendation", e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "failed to generate recommendation: " + e.getMessage());
        }
    }

    @PostMapping("/feedback")
    public ResponseEntity<Map<String, Object>> recordFeedback(
            @RequestParam String requestId,
            @RequestParam(required = false) Integer studentId,
            @RequestParam Long problemId,
            @RequestParam String action,
            @RequestParam(required = false) String sessionId,
            HttpServletRequest request) {
        try {
            Integer currentStudentId = requireCurrentStudentId(studentId, request);
            String validatedRequestId = requireText(requestId, "requestId");
            Long validatedProblemId = requirePositive(problemId, "problemId");
            String validatedAction = requireText(action, "action");
            String normalizedSessionId = normalizeOptional(sessionId);
            logger.info("Record recommendation feedback requestId={} studentId={} problemId={} action={}",
                    validatedRequestId, currentStudentId, validatedProblemId, validatedAction);

            boolean success = recommendationService.recordFeedback(
                    validatedRequestId,
                    currentStudentId,
                    validatedProblemId,
                    validatedAction,
                    normalizedSessionId
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", success);
            response.put("message", success ? "feedback recorded" : "feedback rejected");
            return ResponseEntity.ok(response);
        } catch (ResponseStatusException e) {
            return error(e.getStatusCode(), e.getReason());
        } catch (Exception e) {
            logger.error("Failed to record recommendation feedback", e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "failed to record feedback: " + e.getMessage());
        }
    }

    @PostMapping("/admin/sync")
    public ResponseEntity<Map<String, Object>> syncLeetCodeData(HttpServletRequest request) {
        try {
            requireAdmin(request);
            logger.info("Start LeetCode dataset sync");
            String jsonFilePath = "datasets/leetcode/solutions_cleaned.json";
            int syncCount = syncService.syncProblemsFromJson(jsonFilePath);

            Map<String, Object> response = successBody();
            response.put("syncCount", syncCount);
            response.put("message", "dataset sync completed");
            response.put("stats", syncService.getSyncStats());
            return ResponseEntity.ok(response);
        } catch (ResponseStatusException e) {
            return error(e.getStatusCode(), e.getReason());
        } catch (Exception e) {
            logger.error("Failed to sync LeetCode dataset", e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "failed to sync dataset: " + e.getMessage());
        }
    }

    @GetMapping("/admin/stats")
    public ResponseEntity<Map<String, Object>> getSyncStats(HttpServletRequest request) {
        try {
            requireAdmin(request);
            Map<String, Object> response = successBody();
            response.put("stats", syncService.getSyncStats());
            return ResponseEntity.ok(response);
        } catch (ResponseStatusException e) {
            return error(e.getStatusCode(), e.getReason());
        } catch (Exception e) {
            logger.error("Failed to load sync stats", e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "failed to load sync stats: " + e.getMessage());
        }
    }

    private Integer requireCurrentStudentId(Integer requestedStudentId, HttpServletRequest request) {
        String sessionStudentId = studentSessionResolver.requireStudentId(request);
        Integer currentStudentId = parseStudentId(sessionStudentId);
        if (requestedStudentId != null && !requestedStudentId.equals(currentStudentId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
        return currentStudentId;
    }

    private void authorizeRecommendationAccess(Integer recommendationStudentId, HttpServletRequest request) {
        UserEntity user = legacySessionAccessResolver.requireAuthenticated(request);
        String role = normalizeOptional(user.getRole());
        if ("admin".equals(role)) {
            return;
        }
        Integer currentStudentId = requireCurrentStudentId(null, request);
        if (recommendationStudentId == null || !recommendationStudentId.equals(currentStudentId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }

    private void requireAdmin(HttpServletRequest request) {
        legacySessionAccessResolver.requireAdmin(request);
    }

    private int validateLimit(Integer limit) {
        if (limit == null) {
            return 20;
        }
        if (limit < 1 || limit > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 50");
        }
        return limit;
    }

    private Long requirePositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is invalid");
        }
        return value;
    }

    private String normalizeScene(String scene) {
        String normalized = normalizeOptional(scene);
        return normalized == null ? "default" : normalized;
    }

    private String requireText(String value, String fieldName) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
        return normalized;
    }

    private Integer parseStudentId(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid student id");
        }
    }

    private Map<String, Object> successBody() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        return response;
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatusCode status, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        return ResponseEntity.status(status).body(response);
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
