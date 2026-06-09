package com.tap.backend.api.classroom;

import com.tap.backend.academic.security.LegacySessionAccessResolver;
import com.tap.backend.domain.classroom.ClassStudentEntity;
import com.tap.backend.domain.classroom.TeachingClassEntity;
import com.tap.backend.security.StudentPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.TeachingClassService;
import com.tap.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/student-classes")
public class StudentClassroomController {

    private final StudentPrincipalResolver studentPrincipalResolver;
    private final LegacySessionAccessResolver legacySessionAccessResolver;
    private final TeachingClassService teachingClassService;

    public StudentClassroomController(StudentPrincipalResolver studentPrincipalResolver,
                                      LegacySessionAccessResolver legacySessionAccessResolver,
                                      TeachingClassService teachingClassService) {
        this.studentPrincipalResolver = studentPrincipalResolver;
        this.legacySessionAccessResolver = legacySessionAccessResolver;
        this.teachingClassService = teachingClassService;
    }

    record JoinClassRequest(String classCode, String password) {}

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listJoinedClasses(@AuthenticationPrincipal UserPrincipal principal,
                                                                    HttpServletRequest request) {
        StudentPrincipalResolver.ResolvedStudent student = requireStudent(principal, request);
        List<TeachingClassEntity> classes = teachingClassService.listClassesByStudent(student.userId(), student.studentNum());
        return ApiResponse.of(classes.stream().map(this::toClassMap).toList());
    }

    @PostMapping("/join")
    public ApiResponse<Map<String, Object>> joinClass(@AuthenticationPrincipal UserPrincipal principal,
                                                      HttpServletRequest request,
                                                      @RequestBody JoinClassRequest joinClassRequest) {
        StudentPrincipalResolver.ResolvedStudent student = requireStudent(principal, request);
        ClassStudentEntity joined = teachingClassService.joinClass(
                joinClassRequest.classCode(),
                joinClassRequest.password(),
                student.displayName() == null ? student.username() : student.displayName(),
                student.studentNum(),
                student.userId());
        Long joinedClassId = resolveClassId(joined);
        TeachingClassEntity teachingClass = teachingClassService.listClassesByStudent(student.userId(), student.studentNum()).stream()
                .filter(item -> item.getId().equals(joinedClassId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("joined class not found"));
        return ApiResponse.of(toClassMap(teachingClass));
    }

    private StudentPrincipalResolver.ResolvedStudent requireStudent(UserPrincipal principal, HttpServletRequest request) {
        if (principal != null) {
            return studentPrincipalResolver.requireStudent(principal);
        }
        return studentPrincipalResolver.requireStudent(legacySessionAccessResolver.requireAuthenticated(request));
    }

    private Long resolveClassId(ClassStudentEntity student) {
        if (student.getClassId() != null) {
            return student.getClassId();
        }
        if (student.getTeachingClass() != null) {
            return student.getTeachingClass().getId();
        }
        return null;
    }

    private Map<String, Object> toClassMap(TeachingClassEntity teachingClass) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", teachingClass.getId());
        result.put("name", teachingClass.getName());
        result.put("classCode", teachingClass.getClassCode());
        result.put("grade", teachingClass.getGrade());
        result.put("courseName", teachingClass.getCourseName());
        result.put("description", teachingClass.getDescription());
        result.put("teacherId", teachingClass.getTeacherId());
        result.put("createdAt", teachingClass.getCreatedAt());
        result.put("updatedAt", teachingClass.getUpdatedAt());
        return result;
    }
}
