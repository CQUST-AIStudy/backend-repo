package com.tap.backend.api.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tap.backend.academic.entity.teacher.Teacher;
import com.tap.backend.academic.security.TeacherSessionResolver;
import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.repo.UserRepository;
import com.tap.backend.service.TeacherClassProfileService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class TeacherClassProfileControllerTest {

    @Mock TeacherSessionResolver teacherSessionResolver;
    @Mock UserRepository userRepository;
    @Mock TeacherClassProfileService profileService;
    @Mock HttpServletRequest request;

    private TeacherClassProfileController controller;

    @BeforeEach
    void setUp() {
        controller = new TeacherClassProfileController(teacherSessionResolver, userRepository, profileService);
    }

    @Test
    void usesAuthenticatedTapUserAndRequestedClassScope() {
        Teacher teacher = mock(Teacher.class);
        UserEntity teacherUser = mock(UserEntity.class);
        when(teacher.getUsername()).thenReturn("teacher01");
        when(teacherUser.getId()).thenReturn(42L);
        when(teacherSessionResolver.requireCurrentTeacher(request)).thenReturn(teacher);
        when(userRepository.findByUsername("teacher01")).thenReturn(Optional.of(teacherUser));
        when(profileService.getProfile(42L, 7L)).thenReturn(Map.of("totalStudents", 3));

        Map<String, Object> result = controller.getClassProfile(7L, request).data();

        assertEquals(3, result.get("totalStudents"));
        verify(profileService).getProfile(42L, 7L);
    }

    @Test
    void rejectsLegacyTeacherWithoutUnifiedAccount() {
        Teacher teacher = mock(Teacher.class);
        when(teacher.getUsername()).thenReturn("missing");
        when(teacherSessionResolver.requireCurrentTeacher(request)).thenReturn(teacher);
        when(userRepository.findByUsername("missing")).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> controller.getClassProfile(7L, request));

        assertEquals(403, error.getStatusCode().value());
    }
}
