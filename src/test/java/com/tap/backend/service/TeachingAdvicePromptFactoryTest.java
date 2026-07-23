package com.tap.backend.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TeachingAdvicePromptFactoryTest {
    private final TeachingAdvicePromptFactory factory = new TeachingAdvicePromptFactory(new ObjectMapper());

    @Test
    void buildsDifferentFocusForEachScopeLevel() {
        Map<String, Object> context = Map.of(
                "scope", Map.of("className", "软件工程1班"),
                "metrics", Map.of("evidence", List.of(Map.of("evidenceId", "M01"))));

        String experiment = factory.build("EXPERIMENT", context);
        String classAdvice = factory.build("CLASS", context);
        String course = factory.build("COURSE", context);

        assertTrue(experiment.contains("同一实验在不同教学班"));
        assertTrue(classAdvice.contains("教学班历次实验趋势"));
        assertTrue(course.contains("同一课程多个教学班及历史学期"));
        assertTrue(experiment.contains("\"evidenceId\":\"M01\""));
        assertTrue(experiment.contains("只能依据下方数据快照"));
        assertTrue(experiment.contains("仅输出严格 JSON"));
    }

    @Test
    void rejectsUnsupportedScopeLevel() {
        assertThrows(IllegalArgumentException.class, () -> factory.build("STUDENT", Map.of()));
    }
}
