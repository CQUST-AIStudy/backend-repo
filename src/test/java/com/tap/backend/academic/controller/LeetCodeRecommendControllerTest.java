package com.tap.backend.academic.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tap.backend.academic.security.LegacySessionAccessResolver;
import com.tap.backend.academic.security.StudentSessionResolver;
import com.tap.backend.academic.service.LeetCodeRecommendationService;
import com.tap.backend.academic.service.LeetCodeSyncService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class LeetCodeRecommendControllerTest {

    @Mock LeetCodeRecommendationService recommendationService;
    @Mock LeetCodeSyncService syncService;
    @Mock StudentSessionResolver studentSessionResolver;
    @Mock LegacySessionAccessResolver legacySessionAccessResolver;
    @Mock HttpServletRequest request;

    private LeetCodeRecommendController controller;

    @BeforeEach
    void setUp() {
        controller = new LeetCodeRecommendController(
                recommendationService,
                syncService,
                studentSessionResolver,
                legacySessionAccessResolver
        );
    }

    @Test
    void syncRecommendationUsesStudentProfileIdInsteadOfStudentNumber() {
        when(studentSessionResolver.requireStudentProfileId(request)).thenReturn(37);
        when(recommendationService.generateRecommendationSync(37, 5, "class:8")).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response =
                controller.generateRecommendationSync(5, 8L, null, request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(0, response.getBody().get("itemCount"));
        verify(studentSessionResolver).requireActiveClassMembership(37, 8L);
        verify(recommendationService).generateRecommendationSync(37, 5, "class:8");
    }
}
