package com.tap.backend.academic.controller;

import com.tap.backend.academic.entity.StudentCode;
import com.tap.backend.academic.security.LegacySessionAccessResolver;
import com.tap.backend.academic.service.StudentCodeService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class StudentCodeController {

    private static final Logger logger = LoggerFactory.getLogger(StudentCodeController.class);

    @Autowired
    private StudentCodeService studentCodeService;

    @Autowired
    private LegacySessionAccessResolver legacySessionAccessResolver;

    @GetMapping("/student/code/{studentId}")
    public ResponseEntity<Map<String, Object>> getStudentCodes(
            @PathVariable int studentId,
            HttpServletRequest request
    ) {
        legacySessionAccessResolver.requireStudentReadAccess(String.valueOf(studentId), request);
        return getStudentCodes(studentId);
    }

    public ResponseEntity<Map<String, Object>> getStudentCodes(int studentId) {
        logger.info("query student code list, studentId={}", studentId);
        Map<String, Object> response = new HashMap<>();

        try {
            List<StudentCode> codeList = studentCodeService.findCodeByStudentId(studentId);
            if (codeList != null && !codeList.isEmpty()) {
                response.put("success", true);
                response.put("codeList", codeList);
                return ResponseEntity.ok(response);
            }

            response.put("success", false);
            response.put("message", "student code not found");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("query student codes failed", e);
            response.put("success", false);
            response.put("message", "failed to query student codes: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    @GetMapping("/student/code/{studentId}/{experimentId}")
    public ResponseEntity<Map<String, Object>> getStudentExperimentCode(
            @PathVariable int studentId,
            @PathVariable int experimentId,
            HttpServletRequest request
    ) {
        requireStudentCodeReadAccess(studentId, request);
        return getStudentExperimentCode(studentId, experimentId);
    }

    private void requireStudentCodeReadAccess(int studentId, HttpServletRequest request) {
        try {
            legacySessionAccessResolver.requireTeacherOrAdmin(request);
        } catch (Exception teacherAccessDenied) {
            legacySessionAccessResolver.requireStudentReadAccess(String.valueOf(studentId), request);
        }
    }

    public ResponseEntity<Map<String, Object>> getStudentExperimentCode(int studentId, int experimentId) {
        logger.info("query student experiment code, studentId={}, experimentId={}", studentId, experimentId);
        Map<String, Object> response = new HashMap<>();

        try {
            StudentCode code = studentCodeService.findCodeByStudentIdAndExperimentId(studentId, experimentId);
            if (code != null) {
                response.put("success", true);
                response.put("code", code);
                return ResponseEntity.ok(response);
            }

            response.put("success", false);
            response.put("message", "student experiment code not found");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("query student experiment code failed", e);
            response.put("success", false);
            response.put("message", "failed to query student experiment code: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }
}
