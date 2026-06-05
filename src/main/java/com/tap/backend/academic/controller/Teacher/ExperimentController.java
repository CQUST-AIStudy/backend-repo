package com.tap.backend.academic.controller.Teacher;

import com.tap.backend.academic.dao.StudentDao;
import com.tap.backend.academic.entity.Experiment;
import com.tap.backend.academic.entity.Student;
import com.tap.backend.academic.entity.teacher.Teacher;
import com.tap.backend.academic.security.TeacherSessionResolver;
import com.tap.backend.academic.service.ExperimentService;
import com.tap.backend.academic.service.teacherexperiment.TeacherExperimentQueryService;
import com.tap.backend.academic.teacherexperiment.TeacherExperimentListResult;
import com.tap.backend.academic.teacherexperiment.TeacherStudentExperimentResult;
import com.tap.backend.domain.classroom.TeachingClassEntity;
import com.tap.backend.repo.TeachingClassRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teacher")
public class ExperimentController {

    @Autowired
    private TeacherExperimentQueryService teacherExperimentQueryService;

    @Autowired
    private ExperimentService experimentService;

    @Autowired
    private StudentDao studentDao;

    @Autowired
    private TeacherSessionResolver teacherSessionResolver;

    @Autowired
    private TeachingClassRepository teachingClassRepository;

    @GetMapping("/experiments")
    public ResponseEntity<Map<String, Object>> getTeacherExperimentList(
            @RequestParam(value = "classId", required = false) Long classId,
            @RequestParam(value = "class", required = false) String classKeyword,
            @RequestParam(value = "classKeyword", required = false) String classKeywordAlias,
            HttpServletRequest request
    ) {
        try {
            Teacher teacher = requireCurrentTeacher(request);
            String keyword = resolveClassKeywordForQuery(teacher, classId, normalizeKeyword(classKeyword, classKeywordAlias));
            TeacherExperimentListResult result = teacherExperimentQueryService
                    .getTeacherExperimentList(teacher.getTeacher_id(), classId, keyword);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("data", result.getExperiments());
            response.put("total", result.getExperiments().size());
            response.put("studentCount", result.getStudentCount());
            response.put("classId", classId);
            response.put("class", keyword);
            response.put("teacherInfo", teacher);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return error("failed to load teacher experiments: " + e.getMessage());
        }
    }

    @PostMapping("/experiments")
    public ResponseEntity<Map<String, Object>> createTeacherExperiment(
            @RequestBody Map<String, Object> request,
            HttpServletRequest servletRequest
    ) {
        Map<String, Object> response = new HashMap<>();
        try {
            Teacher teacher = requireCurrentTeacher(servletRequest);
            String name = getTrimmedString(request, "name");
            if (name == null) {
                response.put("success", false);
                response.put("message", "experiment name is required");
                return ResponseEntity.badRequest().body(response);
            }

            Experiment experiment = new Experiment();
            experiment.setName(name);
            experiment.setDeadline(getTrimmedString(request, "deadline"));
            experiment.setDescribe(getTrimmedString(request, "description"));
            experiment.setRequirements(joinRequirements(request == null ? null : request.get("requirements")));
            experiment.setClassName(resolveExperimentClassKeyword(request));
            experiment.setTopic_sum(0);
            experiment.setNum(nextExperimentNum());

            if (!experimentService.saveExperiment(experiment)) {
                response.put("success", false);
                response.put("message", "failed to create experiment");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }

            response.put("success", true);
            response.put("id", experiment.getExperiment_id());
            response.put("data", experiment);
            response.put("teacherInfo", teacher);
            response.put("message", "experiment created successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "failed to create experiment: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/allStudentExperiments")
    public ResponseEntity<Map<String, Object>> getAllStudentExperiments(
            @RequestParam(value = "classId", required = false) Long classId,
            @RequestParam(value = "class", required = false) String classKeyword,
            @RequestParam(value = "classKeyword", required = false) String classKeywordAlias,
            HttpServletRequest request
    ) {
        Map<String, Object> response = new HashMap<>();
        try {
            Teacher teacher = requireCurrentTeacher(request);
            String keyword = resolveClassKeywordForQuery(teacher, classId, normalizeKeyword(classKeyword, classKeywordAlias));
            TeacherStudentExperimentResult result = teacherExperimentQueryService
                    .getAllStudentExperiments(teacher.getTeacher_id(), classId, keyword);
            if (!result.hasStudents()) {
                response.put("success", true);
                response.put("data", new ArrayList<>());
                response.put("message", "no students found");
                response.put("classId", classId);
                response.put("class", keyword);
                response.put("teacherInfo", teacher);
                return ResponseEntity.ok(response);
            }

            response.put("success", true);
            response.put("data", result.getData());
            response.put("total", result.getData().size());
            response.put("classId", classId);
            response.put("class", keyword);
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

    private int nextExperimentNum() {
        List<Experiment> experiments = experimentService.findAllExperiments();
        int maxNum = 0;
        if (experiments != null) {
            for (Experiment experiment : experiments) {
                if (experiment != null && experiment.getNum() > maxNum) {
                    maxNum = experiment.getNum();
                }
            }
        }
        return maxNum + 1;
    }

    private String getTrimmedString(Map<String, Object> request, String key) {
        if (request == null || request.get(key) == null) {
            return null;
        }
        String value = String.valueOf(request.get(key)).trim();
        return value.isEmpty() ? null : value;
    }

    private String normalizeKeyword(String value, String fallback) {
        String normalized = value == null ? null : value.trim();
        if (normalized != null && !normalized.isEmpty()) {
            return removeKeywordWhitespace(normalized);
        }
        normalized = fallback == null ? null : fallback.trim();
        return normalized == null || normalized.isEmpty() ? null : removeKeywordWhitespace(normalized);
    }

    private String removeKeywordWhitespace(String value) {
        return value == null ? null : value.replaceAll("[\\s\\u3000]+", "");
    }

    private String resolveClassKeywordForQuery(Teacher teacher, Long classId, String keyword) {
        if (teacher == null) {
            return keyword;
        }
        if (classId != null) {
            return teachingClassRepository.findById(classId)
                    .filter(teachingClass -> Long.valueOf(teacher.getTeacher_id()).equals(teachingClass.getTeacherId()))
                    .map(this::resolvePtaKeyword)
                    .orElse(keyword);
        }
        if (keyword == null || keyword.isBlank()) {
            return keyword;
        }
        String normalizedKeyword = removeKeywordWhitespace(keyword);
        List<TeachingClassEntity> classes = teachingClassRepository.findAllByTeacherId(Long.valueOf(teacher.getTeacher_id()));
        for (TeachingClassEntity teachingClass : classes) {
            String className = removeKeywordWhitespace(teachingClass.getName());
            String ptaKeyword = removeKeywordWhitespace(teachingClass.getPtaKeyword());
            if (normalizedKeyword.equals(className) || normalizedKeyword.equals(ptaKeyword)) {
                return resolvePtaKeyword(teachingClass);
            }
        }
        return normalizedKeyword;
    }

    private String resolveExperimentClassKeyword(Map<String, Object> request) {
        String direct = getTrimmedString(request, "class");
        if (direct != null) {
            return normalizeKeyword(direct, null);
        }
        direct = getTrimmedString(request, "className");
        if (direct != null) {
            return normalizeKeyword(direct, null);
        }
        direct = getTrimmedString(request, "ptaKeyword");
        if (direct != null) {
            return normalizeKeyword(direct, null);
        }

        Long classId = firstClassId(request == null ? null : request.get("classes"));
        if (classId == null) {
            classId = firstClassId(request == null ? null : request.get("classIds"));
        }
        if (classId == null) {
            classId = parseLong(request == null ? null : request.get("classId"));
        }
        if (classId == null) {
            return null;
        }
        return teachingClassRepository.findById(classId)
                .map(this::resolvePtaKeyword)
                .orElse(null);
    }

    private String resolvePtaKeyword(TeachingClassEntity teachingClass) {
        String keyword = teachingClass.getPtaKeyword();
        if (keyword != null && !keyword.isBlank()) {
            return normalizeKeyword(keyword, null);
        }
        String name = teachingClass.getName();
        return normalizeKeyword(name, null);
    }

    private Long firstClassId(Object rawClasses) {
        if (!(rawClasses instanceof List<?> classes) || classes.isEmpty()) {
            return null;
        }
        for (Object item : classes) {
            Long classId = parseLong(item);
            if (classId != null) {
                return classId;
            }
        }
        return null;
    }

    private Long parseLong(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(rawValue).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String joinRequirements(Object rawRequirements) {
        if (!(rawRequirements instanceof List<?> requirements)) {
            return null;
        }
        List<String> normalized = new ArrayList<>();
        for (Object requirement : requirements) {
            if (requirement == null) {
                continue;
            }
            String value = String.valueOf(requirement).trim();
            if (!value.isEmpty()) {
                normalized.add(value);
            }
        }
        return normalized.isEmpty() ? null : String.join("\n", normalized);
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
