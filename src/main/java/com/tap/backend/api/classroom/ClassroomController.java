package com.tap.backend.api.classroom;

import com.tap.backend.domain.classroom.ClassStudentEntity;
import com.tap.backend.domain.classroom.TeachingClassEntity;
import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.repo.UserRepository;
import com.tap.backend.security.TeacherPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.TeachingClassService;
import com.tap.common.api.ApiResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/classes")
public class ClassroomController {

    private final TeachingClassService classService;
    private final UserRepository userRepo;
    private final TeacherPrincipalResolver teacherPrincipalResolver;

    public ClassroomController(
            TeachingClassService classService,
            UserRepository userRepo,
            TeacherPrincipalResolver teacherPrincipalResolver
    ) {
        this.classService = classService;
        this.userRepo = userRepo;
        this.teacherPrincipalResolver = teacherPrincipalResolver;
    }

    private UserEntity requireUser(UserPrincipal principal) {
        Long userId = teacherPrincipalResolver.requireTeacherId(principal);
        return userRepo.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("user not found"));
    }

    private Long optionalUserId(UserPrincipal principal) {
        if (principal == null) {
            return null;
        }
        return userRepo.findById(principal.userId())
                .orElseThrow(() -> new NoSuchElementException("user not found"))
                .getId();
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listClasses(@AuthenticationPrincipal UserPrincipal principal) {
        UserEntity user = requireUser(principal);
        List<TeachingClassEntity> classes = classService.listByTeacher(user.getId());
        List<Map<String, Object>> result = new ArrayList<>();
        for (TeachingClassEntity teachingClass : classes) {
            result.add(toMap(teachingClass));
        }
        return ApiResponse.of(result);
    }

    record CreateClassRequest(
            String name,
            String classCode,
            String joinPassword,
            String grade,
            String courseName,
            String description,
            String ptaKeyword,
            Boolean syncEnabled
    ) {}

    @PostMapping
    public ApiResponse<Map<String, Object>> createClass(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody CreateClassRequest req
    ) {
        UserEntity user = requireUser(principal);
        TeachingClassEntity teachingClass = classService.createClass(
                user,
                req.name(),
                req.classCode(),
                req.joinPassword(),
                req.grade(),
                req.courseName(),
                req.description(),
                req.ptaKeyword(),
                req.syncEnabled()
        );
        return ApiResponse.of(toMap(teachingClass));
    }

    record UpdateClassRequest(
            String name,
            String joinPassword,
            String grade,
            String courseName,
            String description,
            String ptaKeyword,
            Boolean syncEnabled
    ) {}

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> updateClass(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestBody UpdateClassRequest req
    ) {
        UserEntity user = requireUser(principal);
        TeachingClassEntity teachingClass = classService.updateClass(
                id,
                user.getId(),
                req.name(),
                req.joinPassword(),
                req.grade(),
                req.courseName(),
                req.description(),
                req.ptaKeyword(),
                req.syncEnabled()
        );
        return ApiResponse.of(toMap(teachingClass));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteClass(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id
    ) {
        UserEntity user = requireUser(principal);
        classService.deleteClass(id, user.getId());
        return ApiResponse.of(null);
    }

    @GetMapping("/{id}/students")
    public ApiResponse<List<Map<String, Object>>> listStudents(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id
    ) {
        UserEntity user = requireUser(principal);
        List<ClassStudentEntity> students = classService.listStudentsForTeacher(id, user.getId());
        List<Map<String, Object>> result = new ArrayList<>();
        for (ClassStudentEntity student : students) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", student.getId());
            row.put("studentName", student.getStudentName());
            row.put("studentNum", student.getStudentNum());
            row.put("userId", student.getUserId());
            row.put("joinedAt", student.getJoinedAt());
            result.add(row);
        }
        return ApiResponse.of(result);
    }

    record AddStudentRequest(String studentName, String studentNum) {}

    @PostMapping("/{id}/students")
    public ApiResponse<Map<String, Object>> addStudent(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestBody AddStudentRequest req
    ) {
        UserEntity user = requireUser(principal);
        ClassStudentEntity student = classService.addStudentForTeacher(
                id,
                user.getId(),
                req.studentName(),
                req.studentNum(),
                null
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", student.getId());
        result.put("studentName", student.getStudentName());
        result.put("studentNum", student.getStudentNum());
        result.put("joinedAt", student.getJoinedAt());
        return ApiResponse.of(result);
    }

    @DeleteMapping("/{classId}/students/{studentId}")
    public ApiResponse<Void> removeStudent(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long classId,
            @PathVariable Long studentId
    ) {
        UserEntity user = requireUser(principal);
        classService.removeStudentForTeacher(classId, studentId, user.getId());
        return ApiResponse.of(null);
    }

    record JoinClassRequest(String classCode, String password, String studentName, String studentNum) {}

    @PostMapping("/join")
    public ApiResponse<Map<String, Object>> joinClass(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody JoinClassRequest req
    ) {
        ClassStudentEntity student = classService.joinClass(
                req.classCode(),
                req.password(),
                req.studentName(),
                req.studentNum(),
                optionalUserId(principal)
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", student.getId());
        result.put("classId", student.getClassId());
        result.put("studentName", student.getStudentName());
        result.put("joinedAt", student.getJoinedAt());
        return ApiResponse.of(result);
    }

    private Map<String, Object> toMap(TeachingClassEntity teachingClass) {
        long studentCount = classService.countStudents(teachingClass.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", teachingClass.getId());
        result.put("name", teachingClass.getName());
        result.put("classCode", teachingClass.getClassCode());
        result.put("joinPassword", teachingClass.getJoinPassword());
        result.put("grade", teachingClass.getGrade());
        result.put("courseName", teachingClass.getCourseName());
        result.put("description", teachingClass.getDescription());
        result.put("studentCount", studentCount);
        result.put("ptaKeyword", teachingClass.getPtaKeyword());
        result.put("syncEnabled", teachingClass.getSyncEnabled());
        result.put("lastSyncAt", teachingClass.getLastSyncAt());
        result.put("syncStatus", teachingClass.getSyncStatus());
        result.put("createdAt", teachingClass.getCreatedAt());
        result.put("updatedAt", teachingClass.getUpdatedAt());
        return result;
    }
}
