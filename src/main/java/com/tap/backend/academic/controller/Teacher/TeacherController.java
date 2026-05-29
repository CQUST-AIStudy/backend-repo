package com.tap.backend.academic.controller.Teacher;

import com.tap.backend.academic.dao.StudentDao;
import com.tap.backend.academic.entity.teacher.Teacher;
import com.tap.backend.academic.security.TeacherSessionResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teacher")
public class TeacherController {

    @Autowired
    private StudentDao studentDao;

    @Autowired
    private TeacherSessionResolver teacherSessionResolver;

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> getTeacherInfo(HttpServletRequest request) {
        try {
            Teacher teacher = requireCurrentTeacher(request);
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("data", teacher);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", "failed to load teacher info: " + e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    @GetMapping("/class-student-count/{teacherId}")
    public ResponseEntity<Map<String, Object>> getClassStudentCountByTeacherId(
            @PathVariable Integer teacherId,
            HttpServletRequest request
    ) {
        try {
            if (teacherId == null || teacherId <= 0) {
                return error("invalid teacher id");
            }

            Teacher teacher = requireTeacherAccess(teacherId, request);
            Integer studentCount = studentDao.getStudentCountByTeacherId(teacherId);
            if (studentCount == null) {
                studentCount = 0;
            }

            Map<String, Object> data = new HashMap<>();
            data.put("teacherId", teacherId);
            data.put("teacherName", teacher.getTeacher_name());
            data.put("classroom", teacher.getClassroom());
            data.put("studentCount", studentCount);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return error("failed to query class student count: " + e.getMessage());
        }
    }

    private Teacher requireCurrentTeacher(HttpServletRequest request) {
        return teacherSessionResolver.requireCurrentTeacher(request);
    }

    private Teacher requireTeacherAccess(Integer teacherId, HttpServletRequest request) {
        return teacherSessionResolver.requireTeacherAccess(teacherId, request);
    }

    private ResponseEntity<Map<String, Object>> error(String message) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("status", "error");
        errorResponse.put("message", message);
        return ResponseEntity.badRequest().body(errorResponse);
    }
}
