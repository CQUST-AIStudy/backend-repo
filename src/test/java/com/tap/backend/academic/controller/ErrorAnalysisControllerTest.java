package com.tap.backend.academic.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tap.backend.academic.security.StudentSessionResolver;
import com.tap.backend.academic.service.ErrorAnalysisService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class ErrorAnalysisControllerTest {

    @Mock
    private StudentSessionResolver studentSessionResolver;
    @Mock
    private ErrorAnalysisService errorAnalysisService;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpSession session;

    @InjectMocks
    private ErrorAnalysisController controller;

    @BeforeEach
    void setUp() {
        when(studentSessionResolver.requireStudentId(request)).thenReturn("2026001");
        when(request.getSession()).thenReturn(session);
    }

    @Test
    void errorRequestWithClientSubmissionsStillUsesDatabaseAiPath() {
        Map<String, Object> analysis = Map.of(
                "generationMode", "AI_MODEL",
                "aiGenerated", true,
                "overallAssessment", "AI analysis");
        when(errorAnalysisService.analyzeErrorFromDb("2026001", "", 7, true))
                .thenReturn(analysis);

        Map<String, Object> payload = new HashMap<>();
        payload.put("experimentId", 7);
        payload.put("forceRefresh", true);
        payload.put("submissions", List.of(Map.of(
                "judgeStatus", "WRONG_ANSWER",
                "code", "client code must not select the legacy proxy")));

        ResponseEntity<Map<String, Object>> response = controller.analyzeError(payload, request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("success"));
        assertEquals(analysis, response.getBody().get("data"));
        verify(errorAnalysisService).analyzeErrorFromDb("2026001", "", 7, true);
        verify(errorAnalysisService, never()).proxyToMicroservice(eq("/analyze/error"), any());
    }
}
