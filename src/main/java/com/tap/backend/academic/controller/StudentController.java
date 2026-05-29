package com.tap.backend.academic.controller;

import com.tap.backend.academic.entity.AIRemarks;
import com.tap.backend.academic.entity.Student;
import com.tap.backend.academic.entity.UserEntity;
import com.tap.backend.academic.security.LegacySessionAccessResolver;
import com.tap.backend.academic.security.TeacherSessionResolver;
import com.tap.backend.academic.service.AIRemarksService;
import com.tap.backend.academic.service.StudentService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class StudentController {

    private static final Logger logger = LoggerFactory.getLogger(StudentController.class);

    @Autowired
    private StudentService studentService;

    @Autowired
    private AIRemarksService aiRemarksService;

    @Autowired
    private LegacySessionAccessResolver legacySessionAccessResolver;

    @Autowired
    private TeacherSessionResolver teacherSessionResolver;

    @GetMapping("students/id/{username}")
    public ResponseEntity<Map<String, Object>> findStudentIdByUsername(
            @PathVariable String username,
            HttpServletRequest request
    ) {
        legacySessionAccessResolver.requireUsernameReadAccess(username, request);
        return findStudentIdByUsername(username);
    }

    public ResponseEntity<Map<String, Object>> findStudentIdByUsername(String username) {
        logger.info("query student id by username={}", username);
        Map<String, Object> response = new HashMap<>();

        try {
            Integer studentId = studentService.findStudentIdByUsername(username);
            if (studentId != null) {
                response.put("success", true);
                response.put("studentId", studentId);
                return ResponseEntity.ok(response);
            }

            response.put("success", false);
            response.put("message", "student id not found");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("query student id failed", e);
            response.put("success", false);
            response.put("message", "failed to query student id: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    @GetMapping("/students/username/{username}")
    public ResponseEntity<Map<String, Object>> findStudentByUsername(
            @PathVariable String username,
            HttpServletRequest request
    ) {
        legacySessionAccessResolver.requireUsernameReadAccess(username, request);
        return findStudentByUsername(username);
    }

    public ResponseEntity<Map<String, Object>> findStudentByUsername(String username) {
        logger.info("query student by username={}", username);
        Map<String, Object> response = new HashMap<>();

        try {
            Student student = studentService.findByUsername(username);
            if (student != null) {
                response.put("success", true);
                response.put("student", sanitizeStudent(student));
                return ResponseEntity.ok(response);
            }

            response.put("success", false);
            response.put("message", "student not found");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("query student by username failed", e);
            response.put("success", false);
            response.put("message", "failed to query student: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    @GetMapping("/students/{studentId}")
    public ResponseEntity<Map<String, Object>> findStudentById(
            @PathVariable int studentId,
            HttpServletRequest request
    ) {
        legacySessionAccessResolver.requireStudentReadAccess(String.valueOf(studentId), request);
        return findStudentById(studentId);
    }

    public ResponseEntity<Map<String, Object>> findStudentById(int studentId) {
        logger.info("query student by id={}", studentId);
        Map<String, Object> response = new HashMap<>();

        try {
            Student student = studentService.findByStudentId(studentId);
            if (student != null) {
                response.put("success", true);
                response.put("student", sanitizeStudent(student));
                return ResponseEntity.ok(response);
            }

            response.put("success", false);
            response.put("message", "student not found");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("query student by id failed", e);
            response.put("success", false);
            response.put("message", "failed to query student: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    @GetMapping("/students")
    public ResponseEntity<Map<String, Object>> getAllStudents(HttpServletRequest request) {
        UserEntity user = legacySessionAccessResolver.requireTeacherOrAdmin(request);
        if ("admin".equals(normalize(user.getRole()))) {
            return getAllStudents();
        }

        Map<String, Object> response = new HashMap<>();
        try {
            Integer teacherId = teacherSessionResolver.requireCurrentTeacher(request).getTeacher_id();
            List<Student> students = studentService.getStudentsByTeacherId(teacherId);
            if (students != null && !students.isEmpty()) {
                response.put("success", true);
                response.put("students", sanitizeStudents(students));
                return ResponseEntity.ok(response);
            }

            response.put("success", false);
            response.put("message", "students not found");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("query scoped students failed", e);
            response.put("success", false);
            response.put("message", "failed to query students: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    public ResponseEntity<Map<String, Object>> getAllStudents() {
        logger.info("query all students");
        Map<String, Object> response = new HashMap<>();

        try {
            List<Student> students = studentService.findAllStudents();
            if (students != null && !students.isEmpty()) {
                response.put("success", true);
                response.put("students", sanitizeStudents(students));
                return ResponseEntity.ok(response);
            }

            response.put("success", false);
            response.put("message", "students not found");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("query all students failed", e);
            response.put("success", false);
            response.put("message", "failed to query students: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    @GetMapping("/ai_remarks")
    public ResponseEntity<Map<String, Object>> getAIRemark(
            @RequestParam Integer studentId,
            @RequestParam int experimentId,
            HttpServletRequest request
    ) {
        legacySessionAccessResolver.requireStudentReadAccess(String.valueOf(studentId), request);
        return getAIRemark(studentId, experimentId);
    }

    public ResponseEntity<Map<String, Object>> getAIRemark(Integer studentId, int experimentId) {
        logger.info("query ai remark, studentId={}, experimentId={}", studentId, experimentId);
        Map<String, Object> response = new HashMap<>();

        try {
            AIRemarks aiRemarks = aiRemarksService.getAIRemarkByStudentAndExperiment(studentId, experimentId);
            if (aiRemarks != null) {
                response.put("success", true);
                response.put("data", aiRemarks);
                return ResponseEntity.ok(response);
            }

            response.put("success", false);
            response.put("message", "ai remark not found");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("query ai remark failed", e);
            response.put("success", false);
            response.put("message", "failed to query ai remark: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @PostMapping("/ai_remarks")
    public ResponseEntity<Map<String, Object>> saveOrUpdateAIRemark(
            @RequestBody AIRemarks aiRemarks,
            HttpServletRequest request
    ) {
        if (aiRemarks == null || aiRemarks.getStudentId() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "studentId is required"
            ));
        }
        legacySessionAccessResolver.requireTeacherOrAdmin(request);
        return saveOrUpdateAIRemark(aiRemarks);
    }

    public ResponseEntity<Map<String, Object>> saveOrUpdateAIRemark(AIRemarks aiRemarks) {
        logger.info("save ai remark: {}", aiRemarks);
        Map<String, Object> response = new HashMap<>();

        try {
            boolean result = aiRemarksService.saveOrUpdateAIRemark(aiRemarks);
            if (result) {
                response.put("success", true);
                response.put("message", "ai remark saved");
                return ResponseEntity.ok(response);
            }

            response.put("success", false);
            response.put("message", "ai remark save failed");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("save ai remark failed", e);
            response.put("success", false);
            response.put("message", "failed to save ai remark: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @DeleteMapping("/ai_remarks")
    public ResponseEntity<Map<String, Object>> deleteAIRemark(
            @RequestParam("studentId") Integer studentId,
            @RequestParam("experimentId") Integer experimentId,
            HttpServletRequest request
    ) {
        legacySessionAccessResolver.requireTeacherOrAdmin(request);
        return deleteAIRemark(studentId, experimentId);
    }

    public ResponseEntity<Map<String, Object>> deleteAIRemark(Integer studentId, Integer experimentId) {
        logger.info("delete ai remark, studentId={}, experimentId={}", studentId, experimentId);
        Map<String, Object> response = new HashMap<>();

        try {
            boolean result = aiRemarksService.deleteAIRemark(studentId, experimentId);
            if (result) {
                response.put("success", true);
                response.put("message", "ai remark deleted");
                return ResponseEntity.ok(response);
            }

            response.put("success", false);
            response.put("message", "ai remark delete failed");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("delete ai remark failed", e);
            response.put("success", false);
            response.put("message", "failed to delete ai remark: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    private List<Student> sanitizeStudents(List<Student> students) {
        List<Student> sanitized = new ArrayList<>();
        for (Student student : students) {
            sanitized.add(sanitizeStudent(student));
        }
        return sanitized;
    }

    private Student sanitizeStudent(Student student) {
        if (student == null) {
            return null;
        }
        Student sanitized = new Student();
        sanitized.setStudent_id(student.getStudent_id());
        sanitized.setUsername(student.getUsername());
        sanitized.setName(student.getName());
        sanitized.setClass_name(student.getClass_name());
        sanitized.setCreatedAt(student.getCreatedAt());
        return sanitized;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
