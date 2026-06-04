package com.tap.backend.api.classroom;

import com.tap.backend.academic.entity.UserEntity;
import com.tap.backend.academic.security.StudentSessionResolver;
import com.tap.backend.domain.classroom.ClassStudentEntity;
import com.tap.backend.domain.classroom.TeachingClassEntity;
import com.tap.backend.service.TeachingClassService;
import com.tap.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/student-classes")
public class StudentClassroomController {

    private final StudentSessionResolver studentSessionResolver;
    private final TeachingClassService teachingClassService;

    public StudentClassroomController(StudentSessionResolver studentSessionResolver,
                                      TeachingClassService teachingClassService) {
        this.studentSessionResolver = studentSessionResolver;
        this.teachingClassService = teachingClassService;
    }

    record JoinClassRequest(String classCode, String password) {}

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listJoinedClasses(HttpServletRequest request) {
        String studentNum = studentSessionResolver.requireStudentId(request);
        List<TeachingClassEntity> classes = teachingClassService.listClassesByStudentNum(studentNum);
        return ApiResponse.of(classes.stream().map(this::toClassMap).toList());
    }

    @PostMapping("/join")
    public ApiResponse<Map<String, Object>> joinClass(HttpServletRequest request,
                                                      @RequestBody JoinClassRequest joinClassRequest) {
        UserEntity student = studentSessionResolver.requireStudent(request);
        ClassStudentEntity joined = teachingClassService.joinClass(
                joinClassRequest.classCode(),
                joinClassRequest.password(),
                student.getUsername(),
                student.getUsernum(),
                null);
        TeachingClassEntity teachingClass = teachingClassService.listClassesByStudentNum(student.getUsernum()).stream()
                .filter(item -> item.getId().equals(joined.getClassId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("joined class not found"));
        return ApiResponse.of(toClassMap(teachingClass));
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
