package com.tap.backend.academic.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tap.backend.academic.entity.UserEntity;
import com.tap.backend.academic.security.LegacySessionAccessResolver;
import com.tap.backend.academic.service.ProfileService;
import com.tap.backend.domain.classroom.TeachingClassEntity;
import com.tap.backend.service.TeachingClassService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProfileControllerClassScopeTest {

    @Mock ProfileService profileService;
    @Mock LegacySessionAccessResolver legacySessionAccessResolver;
    @Mock TeachingClassService teachingClassService;
    @Mock HttpServletRequest request;

    private ProfileController controller;

    @BeforeEach
    void setUp() {
        controller = new ProfileController();
        ReflectionTestUtils.setField(controller, "profileService", profileService);
        ReflectionTestUtils.setField(controller, "legacySessionAccessResolver", legacySessionAccessResolver);
        ReflectionTestUtils.setField(controller, "teachingClassService", teachingClassService);
    }

    @Test
    void classProfileRequiresTeacherOwnedClassId() {
        UserEntity teacher = new UserEntity();
        teacher.setId(42);
        teacher.setRole("teacher");
        TeachingClassEntity teachingClass = new TeachingClassEntity();
        teachingClass.setId(7L);
        teachingClass.setName("\u8ba1\u79d125");
        teachingClass.setCourseName("\u6570\u636e\u7ed3\u6784");
        Map<String, Object> expected = Map.of("classId", 7L, "totalStudents", 35);

        when(legacySessionAccessResolver.requireTeacherOrAdmin(request)).thenReturn(teacher);
        when(teachingClassService.getClassForTeacher(7L, 42L)).thenReturn(teachingClass);
        when(profileService.getClassProfile(7L, "\u8ba1\u79d125", "\u6570\u636e\u7ed3\u6784"))
                .thenReturn(expected);

        var response = controller.getClassProfile(request, 7L);

        assertEquals(expected, response.getBody());
        verify(teachingClassService).getClassForTeacher(7L, 42L);
        verify(profileService).getClassProfile(7L, "\u8ba1\u79d125", "\u6570\u636e\u7ed3\u6784");
    }
}
