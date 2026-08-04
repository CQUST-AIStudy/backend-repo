package com.tap.backend.academic.controller;

import com.tap.backend.academic.security.StudentSessionResolver;
import com.tap.backend.academic.service.ErrorAnalysisService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 错误分析控制器
 *
 * 两条链路：
 * A. 同步查询 —— GET  /api/analysis/report/{experimentId} → 读 MySQL/Redis 中已存储的报告
 * B. 异步触发 —— POST /api/analysis/trigger/{experimentId} → 后台跑 AI 管线，写 MySQL + Redis
 * C. 直接调用 —— POST /api/analysis/error|learning|warning → 透传或服务端构建（兼容旧模式）
 */
@RestController
@RequestMapping("/api/analysis")
public class ErrorAnalysisController {

    private static final Logger logger = LoggerFactory.getLogger(ErrorAnalysisController.class);

    @Autowired
    private StudentSessionResolver studentSessionResolver;

    @Autowired
    private ErrorAnalysisService errorAnalysisService;

    // ==================== 查询已存储的报告 ====================

    /**
     * GET /api/analysis/report/{experimentId}
     * 获取该学生在此实验的所有 AI 分析报告（从 MySQL/Redis 读取）
     */
    @GetMapping("/report/{experimentId}")
    public ResponseEntity<Map<String, Object>> getStoredReport(
            @PathVariable int experimentId,
            HttpServletRequest request) {
        try {
            String studentId = studentSessionResolver.requireStudentId(request);
            Map<String, Object> reports = errorAnalysisService.getStoredReportsAsMap(studentId, experimentId);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", reports);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Failed to get stored reports: experimentId={}", experimentId, e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * GET /api/analysis/status/{experimentId}
     * 检查是否有已存储的分析报告（轻量级，前端轮询用）
     */
    @GetMapping("/status/{experimentId}")
    public ResponseEntity<Map<String, Object>> checkStatus(
            @PathVariable int experimentId,
            HttpServletRequest request) {
        try {
            String studentId = studentSessionResolver.requireStudentId(request);
            Map<String, Object> reports = errorAnalysisService.getStoredReportsAsMap(studentId, experimentId);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            // 检查是否有 ERROR 类型报告
            boolean hasAnalysis = reports.containsKey("error") || reports.containsKey("learning");
            result.put("ready", hasAnalysis);
            result.put("totalReports", reports.getOrDefault("totalReports", 0));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    // ==================== 异步触发管线 ====================

    /**
     * POST /api/analysis/trigger/{experimentId}
     * 手动触发 AI 分析管线（后台异步执行，立即返回）
     */
    @PostMapping("/trigger/{experimentId}")
    public ResponseEntity<Map<String, Object>> triggerAnalysis(
            @PathVariable int experimentId,
            HttpServletRequest request) {
        try {
            String studentId = studentSessionResolver.requireStudentId(request);
            String studentName = resolveStudentName(request);

            // 异步触发（不阻塞请求线程）
            errorAnalysisService.triggerAnalysisPipeline(studentId, studentName, experimentId);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "AI analysis pipeline triggered. Check GET /api/analysis/status/" + experimentId);
            result.put("experimentId", experimentId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Failed to trigger analysis: experimentId={}", experimentId, e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    // ==================== 健康检查 ====================

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> status = errorAnalysisService.proxyToMicroservice("/health", new HashMap<>());
            result.put("success", true);
            result.put("status", status);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.warn("Error analysis service health check failed: {}", e.getMessage());
            result.put("success", false);
            result.put("message", "error analysis service unavailable");
            return ResponseEntity.ok(result);
        }
    }

    // ==================== 直接调用（兼容旧模式） ====================

    @PostMapping("/error")
    public ResponseEntity<Map<String, Object>> analyzeError(
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request) {
        return proxyOrBuild(payload, request, "error");
    }

    @PostMapping("/learning")
    public ResponseEntity<Map<String, Object>> learningSuggest(
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request) {
        return proxyOrBuild(payload, request, "learning");
    }

    /**
     * 画像页专用学习建议：数据库优先读取，未命中或 forceRefresh 才生成并落库。
     * 与通用 /learning（实验级透传）隔离，避免互相污染缓存。
     */
    @PostMapping("/learning/profile")
    public ResponseEntity<Map<String, Object>> learningSuggestProfile(
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request) {
        try {
            String studentId = studentSessionResolver.requireStudentId(request);
            boolean forceRefresh = Boolean.TRUE.equals(payload.get("forceRefresh"));
            Map<String, Object> responseBody = errorAnalysisService.learningSuggestCached(studentId, payload, forceRefresh);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            if (responseBody != null && responseBody.get("data") != null) {
                result.put("data", responseBody.get("data"));
            } else if (responseBody != null) {
                result.put("data", responseBody);
            } else {
                result.put("data", null);
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("learningSuggestProfile failed: {}", e.getMessage(), e);
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "学习建议生成失败，请稍后重试");
            return ResponseEntity.internalServerError().body(err);
        }
    }

    @PostMapping("/warning")
    public ResponseEntity<Map<String, Object>> warningAnalyze(
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request) {
        return proxyOrBuild(payload, request, "warning");
    }

    private ResponseEntity<Map<String, Object>> proxyOrBuild(
            Map<String, Object> payload,
            HttpServletRequest request,
            String type) {
        try {
            String studentId = studentSessionResolver.requireStudentId(request);
            String studentName = resolveStudentName(request);

            Integer experimentId = getExperimentId(payload);
            Map<String, Object> responseBody;
            boolean forceRefresh = Boolean.TRUE.equals(payload.get("forceRefresh"));

            if ("error".equals(type) && experimentId != null) {
                responseBody = errorAnalysisService.analyzeErrorFromDb(
                        studentId, studentName, experimentId, forceRefresh);
                return successResponse(responseBody);
            }

            // 判断模式
            boolean isProxyMode = switch (type) {
                case "error" -> payload.containsKey("submissions") && payload.get("submissions") instanceof java.util.List;
                case "learning" -> payload.containsKey("errorHistory") && payload.get("errorHistory") instanceof java.util.List;
                case "warning" -> payload.containsKey("totalSubmissions") && payload.get("totalSubmissions") instanceof Number;
                default -> false;
            };

            if (isProxyMode) {
                payload.put("studentId", studentId);
                // 过滤 errorHistory 中 count <= 0 的无效条目
                if ("learning".equals(type)) {
                    sanitizeErrorHistory(payload);
                    if (!hasValidErrorHistory(payload)) {
                        Map<String, Object> err = new HashMap<>();
                        err.put("success", false);
                        err.put("message", "errorHistory must contain at least one record with count >= 1");
                        return ResponseEntity.badRequest().body(err);
                    }
                }
                responseBody = errorAnalysisService.proxyToMicroservice("/analyze/" + type, payload);
            } else if (experimentId != null) {
                responseBody = switch (type) {
                    case "learning" -> errorAnalysisService.learningSuggestFromDb(studentId, studentName, experimentId);
                    case "warning" -> errorAnalysisService.warningAnalyzeFromDb(studentId, studentName, experimentId, forceRefresh);
                    default -> null;
                };
            } else {
                Map<String, Object> err = new HashMap<>();
                err.put("success", false);
                err.put("message", "experimentId is required when payload data not provided");
                return ResponseEntity.badRequest().body(err);
            }

            return successResponse(responseBody);
        } catch (Exception e) {
            logger.error("Analysis proxy failed: type={}", type, e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", type + " analysis failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    private ResponseEntity<Map<String, Object>> successResponse(Map<String, Object> responseBody) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        if (responseBody != null && responseBody.get("data") != null) {
            result.put("data", responseBody.get("data"));
        } else if (responseBody != null) {
            result.put("data", responseBody);
        }
        return ResponseEntity.ok(result);
    }

    private Integer getExperimentId(Map<String, Object> payload) {
        if (payload == null) return null;
        Object raw = payload.get("experimentId");
        if (raw == null) return null;
        if (raw instanceof Number) return ((Number) raw).intValue();
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String resolveStudentName(HttpServletRequest request) {
        try {
            Object name = request.getSession().getAttribute("studentName");
            if (name != null) return name.toString();
            name = request.getSession().getAttribute("name");
            if (name != null) return name.toString();
        } catch (Exception ignored) {}
        return "";
    }

    /** 过滤 errorHistory 中 count <= 0 的无效条目 */
    @SuppressWarnings("unchecked")
    private void sanitizeErrorHistory(Map<String, Object> payload) {
        Object raw = payload.get("errorHistory");
        if (!(raw instanceof List)) return;
        List<Map<String, Object>> list = (List<Map<String, Object>>) raw;
        list.removeIf(item -> {
            Object countObj = item.get("count");
            if (!(countObj instanceof Number)) return true;
            return ((Number) countObj).intValue() <= 0;
        });
    }

    private boolean hasValidErrorHistory(Map<String, Object> payload) {
        Object raw = payload.get("errorHistory");
        if (!(raw instanceof List)) return false;
        return !((List<?>) raw).isEmpty();
    }
}
