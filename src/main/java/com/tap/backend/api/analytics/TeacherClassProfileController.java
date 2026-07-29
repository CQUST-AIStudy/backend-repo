package com.tap.backend.api.analytics;

import com.tap.backend.academic.entity.teacher.Teacher;
import com.tap.backend.academic.security.TeacherSessionResolver;
import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.repo.UserRepository;
import com.tap.backend.service.TeacherClassProfileService;
import com.tap.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/teacher/classes")
public class TeacherClassProfileController {

    private final TeacherSessionResolver teacherSessionResolver;
    private final UserRepository userRepository;
    private final TeacherClassProfileService profileService;

    public TeacherClassProfileController(
            TeacherSessionResolver teacherSessionResolver,
            UserRepository userRepository,
            TeacherClassProfileService profileService) {
        this.teacherSessionResolver = teacherSessionResolver;
        this.userRepository = userRepository;
        this.profileService = profileService;
    }

    @GetMapping("/{classId}/profile")
    public ApiResponse<Map<String, Object>> getClassProfile(
            @PathVariable Long classId,
            HttpServletRequest request) {
        Teacher teacher = teacherSessionResolver.requireCurrentTeacher(request);
        UserEntity teacherUser = userRepository.findByUsername(teacher.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "teacher account not found"));
        return ApiResponse.of(profileService.getProfile(teacherUser.getId(), classId));
    }
}
