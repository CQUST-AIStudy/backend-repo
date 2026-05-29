package com.tap.backend.academic.controller.Teacher;

import com.tap.backend.academic.dao.StudentDao;
import com.tap.backend.academic.entity.Student;
import com.tap.backend.academic.entity.teacher.Teacher;
import com.tap.backend.academic.security.TeacherSessionResolver;
import com.tap.backend.academic.service.teacherexperiment.TeacherExperimentQueryService;
import com.tap.backend.academic.teacherexperiment.TeacherExperimentListResult;
import com.tap.backend.academic.teacherexperiment.TeacherStudentExperimentResult;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teacher")
public class ExperimentController {

    @Autowired
    private TeacherExperimentQueryService teacherExperimentQueryService;

    @Autowired
    private StudentDao studentDao;

    @Autowired
    private TeacherSessionResolver teacherSessionResolver;

    @GetMapping("/experiments")
    public ResponseEntity<Map<String, Object>> getTeacherExperimentList(
            @RequestParam(value = "classId", required = false) Long classId,
            HttpServletRequest request
    ) {
        try {
            Teacher teacher = requireCurrentTeacher(request);
            TeacherExperimentListResult result = teacherExperimentQueryService
                    .getTeacherExperimentList(teacher.getTeacher_id(), classId);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("data", result.getExperiments());
            response.put("total", result.getExperiments().size());
            response.put("studentCount", result.getStudentCount());
            response.put("classId", classId);
            response.put("teacherInfo", teacher);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return error("failed to load teacher experiments: " + e.getMessage());
        }
    }

    @GetMapping("/allStudentExperiments")
    public ResponseEntity<Map<String, Object>> getAllStudentExperiments(
            @RequestParam(value = "classId", required = false) Long classId,
            HttpServletRequest request
    ) {
        Map<String, Object> response = new HashMap<>();
        try {
            Teacher teacher = requireCurrentTeacher(request);
            TeacherStudentExperimentResult result = teacherExperimentQueryService
                    .getAllStudentExperiments(teacher.getTeacher_id(), classId);
            if (!result.hasStudents()) {
                response.put("success", true);
                response.put("data", new ArrayList<>());
                response.put("message", "no students found");
                response.put("classId", classId);
                response.put("teacherInfo", teacher);
                return ResponseEntity.ok(response);
            }

            response.put("success", true);
            response.put("data", result.getData());
            response.put("total", result.getData().size());
            response.put("classId", classId);
            response.put("teacherInfo", teacher);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "failed to load student experiments: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    @GetMapping("/class")
    public ResponseEntity<Map<String, Object>> getClass(HttpServletRequest request) {
        try {
            Teacher teacher = requireCurrentTeacher(request);
            Map<String, Object> response = new HashMap<>();
            response.put("id", teacher.getClassroom());
            response.put("name", teacher.getClassroom());
            response.put("grade", "");
            response.put("studentCount", getStudentCount(teacher.getTeacher_id()));
            response.put("teacherId", teacher.getTeacher_id());
            response.put("teacherName", teacher.getTeacher_name());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return error("failed to load class info: " + e.getMessage());
        }
    }

    @GetMapping("/studentList")
    public ResponseEntity<Map<String, Object>> getStudentList(HttpServletRequest request) {
        try {
            Teacher teacher = requireCurrentTeacher(request);
            List<Student> students = sanitizeStudents(studentDao.getStudentsByTeacherId(teacher.getTeacher_id()));
            Map<String, Object> response = new HashMap<>();
            response.put("students", students == null ? new ArrayList<>() : students);
            response.put("teacherId", teacher.getTeacher_id());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return error("failed to load student list: " + e.getMessage());
        }
    }

    private Teacher requireCurrentTeacher(HttpServletRequest request) {
        return teacherSessionResolver.requireCurrentTeacher(request);
    }

    private int getStudentCount(Integer teacherId) {
        Integer studentCount = teacherId == null ? null : studentDao.getStudentCountByTeacherId(teacherId);
        return studentCount == null ? 0 : studentCount;
    }

    private ResponseEntity<Map<String, Object>> error(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "error");
        response.put("message", message);
        return ResponseEntity.badRequest().body(response);
    }

    private List<Student> sanitizeStudents(List<Student> students) {
        if (students == null) {
            return null;
        }
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
}
