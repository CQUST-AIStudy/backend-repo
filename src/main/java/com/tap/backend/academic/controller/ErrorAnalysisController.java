package com.tap.backend.academic.controller;

import com.tap.backend.academic.security.StudentSessionResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * Proxies requests to the error-analysis microservice (port 8002).
 * All endpoints require student authentication.
 */
@RestController
@RequestMapping("/api/analysis")
public class ErrorAnalysisController {

    private static final Logger logger = LoggerFactory.getLogger(ErrorAnalysisController.class);

    @Autowired
    private StudentSessionResolver studentSessionResolver;

    @Value("${tap.error-analysis.base-url:http://127.0.0.1:8002}")
    private String errorAnalysisBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        try {
            String url = errorAnalysisBaseUrl.replaceAll("/+$", "") + "/health";
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("status", response.getBody());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.warn("Error analysis service health check failed: {}", e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "error analysis service unavailable");
            return ResponseEntity.ok(result);
        }
    }

    @PostMapping("/error")
    public ResponseEntity<Map<String, Object>> analyzeError(
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request) {
        try {
            String studentId = studentSessionResolver.requireStudentId(request);
            // Inject authenticated student ID
            payload.put("studentId", studentId);

            String url = errorAnalysisBaseUrl.replaceAll("/+$", "") + "/analyze/error";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = restTemplate.postForEntity(url, entity, Map.class).getBody();

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            if (responseBody != null && responseBody.get("data") != null) {
                result.put("data", responseBody.get("data"));
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Error analysis proxy failed", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "error analysis failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    @PostMapping("/learning")
    public ResponseEntity<Map<String, Object>> learningSuggest(
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request) {
        try {
            String studentId = studentSessionResolver.requireStudentId(request);
            payload.put("studentId", studentId);

            String url = errorAnalysisBaseUrl.replaceAll("/+$", "") + "/analyze/learning";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = restTemplate.postForEntity(url, entity, Map.class).getBody();

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            if (responseBody != null && responseBody.get("data") != null) {
                result.put("data", responseBody.get("data"));
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Learning suggestion proxy failed", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "learning suggestion failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    @PostMapping("/warning")
    public ResponseEntity<Map<String, Object>> warningAnalyze(
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request) {
        try {
            String studentId = studentSessionResolver.requireStudentId(request);
            payload.put("studentId", studentId);

            String url = errorAnalysisBaseUrl.replaceAll("/+$", "") + "/analyze/warning";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = restTemplate.postForEntity(url, entity, Map.class).getBody();

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            if (responseBody != null && responseBody.get("data") != null) {
                result.put("data", responseBody.get("data"));
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Warning analysis proxy failed", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "warning analysis failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }
}
