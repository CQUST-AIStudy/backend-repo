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
import com.tap.backend.repo.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Autowired
    private UserRepository userRepository;

    @GetMapping("/experiments")
    public ResponseEntity<Map<String, Object>> getTeacherExperimentList(
            @RequestParam(value = "classId", required = false) Long classId,
            @RequestParam(value = "class", required = false) String classKeyword,
            @RequestParam(value = "classKeyword", required = false) String classKeywordAlias,
            @RequestParam(value = "keyword", required = false) String searchKeyword,
            @RequestParam(value = "scope", required = false) String scope,
            HttpServletRequest request
    ) {
        try {
            Teacher teacher = resolveCurrentTeacherOrNull(request);
            boolean adminAllScope = isAllScope(scope) && teacherSessionResolver.isCurrentAdmin(request);
            // 管理员没有Teacher记录时，返回全部实验（支持关键词搜索）
            if (teacher == null || adminAllScope) {
                String keyword = normalizeKeyword(classKeyword, classKeywordAlias);
                TeacherExperimentListResult result = teacherExperimentQueryService
                        .getTeacherExperimentListForAdmin(classId, keyword, "all");
                List<Map<String, Object>> mapped = new ArrayList<>();
                for (com.tap.backend.academic.entity.teacher.TeacherExperiment e : result.getExperiments()) {
                    if (searchKeyword != null && !searchKeyword.isBlank()
                            && (e.getName() == null || !e.getName().toLowerCase().contains(searchKeyword.trim().toLowerCase()))) {
                        continue;
                    }
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", e.getId());
                    item.put("experimentId", e.getId());
                    item.put("name", e.getName());
                    item.put("title", e.getName());
                    item.put("deadline", e.getDeadline());
                    item.put("submissionCount", e.getSubmissionCount() == null ? 0 : e.getSubmissionCount());
                    item.put("submitCount", e.getSubmissionCount() == null ? 0 : e.getSubmissionCount());
                    item.put("status", e.getStatus());
                    item.put("averageScore", e.getAverageScore());
                    mapped.add(item);
                }
                Map<String, Object> response = new HashMap<>();
                response.put("status", "success");
                response.put("data", mapped);
                response.put("total", mapped.size());
                response.put("studentCount", result.getStudentCount());
                response.put("classId", classId);
                response.put("class", keyword);
                return ResponseEntity.ok(response);
            }

            String keyword = resolveClassKeywordForQuery(teacher, classId, normalizeKeyword(classKeyword, classKeywordAlias));
            TeacherExperimentListResult result = teacherExperimentQueryService
                    .getTeacherExperimentList(teacher.getTeacher_id(), classId, keyword);

            // 关键词过滤（教师流）
            java.util.List<Object> teacherExps = new ArrayList<>(result.getExperiments());
            if (searchKeyword != null && !searchKeyword.isBlank()) {
                String kw = searchKeyword.trim().toLowerCase();
                teacherExps.removeIf(exp -> {
                    String name = null;
                    if (exp instanceof com.tap.backend.academic.entity.teacher.TeacherExperiment te) {
                        name = te.getName();
                    } else if (exp instanceof Map) {
                        Object n = ((Map<?,?>) exp).get("name");
                        name = n != null ? n.toString() : null;
                    }
                    return name == null || !name.toLowerCase().contains(kw);
                });
            }

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("data", teacherExps);
            response.put("total", teacherExps.size());
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
            Teacher teacher = resolveCurrentTeacherOrNull(servletRequest);
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
            if (teacher != null) {
                experiment.setClassName(resolveExperimentClassKeyword(teacher, request));
                experiment.setTeacherId(String.valueOf(teacher.getTeacher_id()));
            } else {
                // 管理员创建时，classId 是前端传来的
                String className = getTrimmedString(request, "class");
                if (className == null) className = getTrimmedString(request, "className");
                experiment.setClassName(className);
                // teacherId 使用请求中的或默认为 "admin"
                String teacherId = getTrimmedString(request, "teacherId");
                experiment.setTeacherId(teacherId != null ? teacherId : "admin");
            }
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
            if (teacher != null) {
                response.put("teacherInfo", teacher);
            }
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
            @RequestParam(value = "experimentId", required = false) Integer experimentId,
            @RequestParam(value = "scope", required = false) String scope,
            HttpServletRequest request
    ) {
        Map<String, Object> response = new HashMap<>();
        try {
            String keyword = normalizeKeyword(classKeyword, classKeywordAlias);
            Teacher teacher = teacherSessionResolver.requireCurrentTeacherOrAdminNull(request);
            boolean adminAllScope = isAllScope(scope) && teacherSessionResolver.isCurrentAdmin(request);

            // 管理员无 Teacher 记录：走全量分支（不绑定教师），支持 classId/classKeyword/experimentId 过滤
            if (teacher == null || adminAllScope) {
                TeacherStudentExperimentResult adminResult = teacherExperimentQueryService
                        .getAllStudentExperimentsForAdmin(classId, keyword, experimentId, "all");
                if (!adminResult.hasStudents()) {
                    response.put("success", true);
                    response.put("data", new ArrayList<>());
                    response.put("message", "no students found");
                    response.put("classId", classId);
                    response.put("class", keyword);
                    return ResponseEntity.ok(response);
                }
                response.put("success", true);
                response.put("data", adminResult.getData());
                response.put("total", adminResult.getData().size());
                response.put("classId", classId);
                response.put("class", keyword);
                return ResponseEntity.ok(response);
            }

            keyword = resolveClassKeywordForQuery(teacher, classId, keyword);
            TeacherStudentExperimentResult result = teacherExperimentQueryService
                    .getAllStudentExperiments(teacher.getTeacher_id(), classId, keyword, experimentId);
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

    /**
     * 尝试获取当前教师，管理员无Teacher记录时返回 null 而非抛异常
     */
    private Teacher resolveCurrentTeacherOrNull(HttpServletRequest request) {
        try {
            return teacherSessionResolver.requireCurrentTeacher(request);
        } catch (Exception e) {
            return null;
        }
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

    private boolean isAllScope(String scope) {
        return scope != null && "all".equalsIgnoreCase(scope.trim());
    }

    private String resolveClassKeywordForQuery(Teacher teacher, Long classId, String keyword) {
        if (teacher == null) {
            return keyword;
        }
        if (classId != null) {
            return teachingClassRepository.findById(classId)
                    .filter(teachingClass -> ownsTeachingClass(teacher, teachingClass))
                    .map(this::resolvePtaKeyword)
                    .orElse(keyword);
        }
        if (keyword == null || keyword.isBlank()) {
            return keyword;
        }
        String normalizedKeyword = removeKeywordWhitespace(keyword);
        Long tapTeacherId = resolveTapTeacherId(teacher);
        if (tapTeacherId == null) {
            return normalizedKeyword;
        }
        List<TeachingClassEntity> classes = teachingClassRepository.findAllByTeacherId(tapTeacherId);
        for (TeachingClassEntity teachingClass : classes) {
            String className = removeKeywordWhitespace(teachingClass.getName());
            String ptaKeyword = removeKeywordWhitespace(teachingClass.getPtaKeyword());
            if (normalizedKeyword.equals(className) || normalizedKeyword.equals(ptaKeyword)) {
                return resolvePtaKeyword(teachingClass);
            }
        }
        return normalizedKeyword;
    }

    private String resolveExperimentClassKeyword(Teacher teacher, Map<String, Object> request) {
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

        Set<Long> classIds = new LinkedHashSet<>();
        classIds.addAll(parseClassIds(request == null ? null : request.get("classes")));
        classIds.addAll(parseClassIds(request == null ? null : request.get("classIds")));
        Long singleClassId = parseLong(request == null ? null : request.get("classId"));
        if (singleClassId != null) {
            classIds.add(singleClassId);
        }

        Set<String> keywords = new LinkedHashSet<>();
        for (Long classId : classIds) {
            teachingClassRepository.findById(classId)
                    .filter(teachingClass -> ownsTeachingClass(teacher, teachingClass))
                    .map(this::resolvePtaKeyword)
                    .filter(keyword -> keyword != null && !keyword.isBlank())
                    .ifPresent(keywords::add);
        }
        return keywords.isEmpty() ? null : String.join(",", keywords);
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

    private List<Long> parseClassIds(Object rawClasses) {
        List<Long> classIds = new ArrayList<>();
        if (rawClasses instanceof List<?> classes) {
            for (Object item : classes) {
                Long classId = parseLong(item);
                if (classId != null) {
                    classIds.add(classId);
                }
            }
            return classIds;
        }
        Long classId = parseLong(rawClasses);
        if (classId != null) {
            classIds.add(classId);
        }
        return classIds;
    }

    private boolean ownsTeachingClass(Teacher teacher, TeachingClassEntity teachingClass) {
        Long tapTeacherId = resolveTapTeacherId(teacher);
        return teacher != null
                && teachingClass != null
                && tapTeacherId != null
                && tapTeacherId.equals(teachingClass.getTeacherId());
    }

    private Long resolveTapTeacherId(Teacher teacher) {
        if (teacher == null || teacher.getUsername() == null) {
            return null;
        }
        return userRepository.findByUsername(teacher.getUsername().trim())
                .map(com.tap.backend.domain.user.UserEntity::getId)
                .orElseGet(() -> teacher.getTeacher_id() == null ? null : Long.valueOf(teacher.getTeacher_id()));
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
