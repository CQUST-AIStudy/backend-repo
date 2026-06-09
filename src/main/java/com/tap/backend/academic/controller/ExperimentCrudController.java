package com.tap.backend.academic.controller;

import com.tap.backend.academic.entity.Experiment;
import com.tap.backend.academic.security.LegacySessionAccessResolver;
import com.tap.backend.academic.service.ExperimentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 实验 CRUD 控制器（供管理员/教师使用）
 * 前端路径: PUT /api/experiments/{id}, DELETE /api/experiments/{id}
 */
@RestController
@RequestMapping("/api/experiments")
public class ExperimentCrudController {

    @Autowired
    private ExperimentService experimentService;

    @Autowired
    private LegacySessionAccessResolver legacySessionAccessResolver;

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateExperiment(
            @PathVariable int id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {

        legacySessionAccessResolver.requireTeacherOrAdmin(request);

        Experiment experiment = experimentService.updateExperimentFromMap(id, body);
        if (experiment == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "experiment not found");
            return ResponseEntity.status(404).body(err);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "experiment updated successfully");
        response.put("data", experiment);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteExperiment(
            @PathVariable int id,
            HttpServletRequest request) {

        legacySessionAccessResolver.requireTeacherOrAdmin(request);

        Experiment experiment = experimentService.findExperimentById(id);
        if (experiment == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "experiment not found");
            return ResponseEntity.status(404).body(err);
        }

        experimentService.deleteExperiment(id);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "experiment deleted successfully");
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/experiments/batch-delete — 批量删除实验
     */
    @PostMapping("/batch-delete")
    public ResponseEntity<Map<String, Object>> batchDeleteExperiments(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {

        legacySessionAccessResolver.requireTeacherOrAdmin(request);

        @SuppressWarnings("unchecked")
        java.util.List<Integer> ids = (java.util.List<Integer>) body.get("ids");
        if (ids == null || ids.isEmpty()) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "ids is required");
            return ResponseEntity.badRequest().body(err);
        }

        int deleted = 0;
        for (Integer id : ids) {
            if (id != null && experimentService.findExperimentById(id) != null) {
                experimentService.deleteExperiment(id);
                deleted++;
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "deleted " + deleted + " experiments");
        response.put("deleted", deleted);
        return ResponseEntity.ok(response);
    }
}
