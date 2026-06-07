package com.tap.backend.academic.controller;

import com.tap.backend.academic.security.LegacySessionAccessResolver;
import com.tap.backend.academic.service.SystemLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 系统日志控制器 — 供管理员使用
 * 前端路径: GET /api/system-logs, DELETE /api/system-logs, GET /api/system-logs/export
 */
@RestController
@RequestMapping("/api/system-logs")
public class SystemLogController {

    @Autowired
    private SystemLogService systemLogService;

    @Autowired
    private LegacySessionAccessResolver legacySessionAccessResolver;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getLogs(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "level", required = false) String level,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
            HttpServletRequest request) {

        legacySessionAccessResolver.requireAdmin(request);
        return ResponseEntity.ok(systemLogService.getLogPage(page, pageSize, keyword, level));
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Object>> clearLogs(HttpServletRequest request) {
        legacySessionAccessResolver.requireAdmin(request);
        systemLogService.clearAll();
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "logs cleared");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/export")
    public void exportLogs(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "level", required = false) String level,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        legacySessionAccessResolver.requireAdmin(request);
        systemLogService.exportCsv(response);
    }
}
