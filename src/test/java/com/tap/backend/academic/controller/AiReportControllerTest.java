package com.tap.backend.academic.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

class AiReportControllerTest {

    @Test
    void exposesGenerateAndGetReportRoutes() {
        boolean post = Arrays.stream(ApiController.class.getDeclaredMethods())
                .anyMatch(method -> hasPost(method, "/api/experiments/{id}/report/generate"));
        boolean get = Arrays.stream(ApiController.class.getDeclaredMethods())
                .anyMatch(method -> hasGet(method, "/api/experiments/{id}/report"));
        assertTrue(post, "generate report route is missing");
        assertTrue(get, "get report route is missing");
    }

    private boolean hasPost(Method method, String path) {
        PostMapping mapping = method.getAnnotation(PostMapping.class);
        return mapping != null && Arrays.asList(mapping.value()).contains(path);
    }

    private boolean hasGet(Method method, String path) {
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        return mapping != null && Arrays.asList(mapping.value()).contains(path);
    }
}
