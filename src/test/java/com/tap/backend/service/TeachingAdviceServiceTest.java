package com.tap.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.ai.AiProvider;
import com.tap.backend.repo.TeachingAdviceReportRepository;
import com.tap.backend.repo.TeachingClassRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class TeachingAdviceServiceTest {
    @Mock TeachingClassRepository classRepository;
    @Mock TeachingAdviceReportRepository reportRepository;
    @Mock AiProvider aiProvider;

    private TeachingAdviceService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        service = new TeachingAdviceService(
                classRepository,
                reportRepository,
                new TeachingAdvicePromptFactory(objectMapper),
                aiProvider,
                objectMapper);
    }

    @Test
    void rejectsInvalidScopeLevelBeforeReadingData() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.context(10L, "STUDENT", 1L, null, false));

        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    void rejectsClassOwnedByAnotherTeacher() {
        com.tap.backend.domain.classroom.TeachingClassEntity otherTeacherClass =
                new com.tap.backend.domain.classroom.TeachingClassEntity();
        ReflectionTestUtils.setField(otherTeacherClass, "teacherId", 11L);
        when(classRepository.findById(3L)).thenReturn(java.util.Optional.of(otherTeacherClass));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.context(10L, "CLASS", 3L, null, false));

        assertEquals(404, error.getStatusCode().value());
    }

    @Test
    void rejectsAiAdviceThatReferencesUnknownEvidence() {
        String raw = """
                {
                  "summary":"测试",
                  "risks":[{"title":"风险","evidenceRefs":["M99"]}],
                  "actions":[],
                  "limitations":[]
                }
                """;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "parseAndValidateAdvice", raw, Set.of("M01")));

        assertTrue(error.getMessage().contains("unknown evidence"));
    }

    @Test
    void mockAdviceAlwaysUsesTheStructuredSchema() {
        Map<String, Object> metrics = Map.of(
                "evidence", List.of(Map.of("evidenceId", "M01")));

        JsonNode advice = ReflectionTestUtils.invokeMethod(service, "fallbackAdvice", "CLASS", metrics);

        assertTrue(advice.hasNonNull("summary"));
        assertTrue(advice.path("risks").isArray());
        assertTrue(advice.path("actions").isArray());
        assertTrue(advice.path("limitations").isArray());
        assertEquals("M01", advice.path("risks").get(0).path("evidenceRefs").get(0).asText());
        assertTrue(advice.path("actions").get(0).hasNonNull("successMetric"));
    }
}
