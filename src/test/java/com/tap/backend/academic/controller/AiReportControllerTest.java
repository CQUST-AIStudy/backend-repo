package com.tap.backend.academic.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test
    void passesAuthenticatedStudentAndOfferingIdToUnifiedReportService() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/tap/backend/academic/controller/ApiController.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("studentSessionResolver.requireStudentId(request)"));
        assertTrue(source.contains("aiReportService.generate("));
        assertTrue(source.contains("studentNo,"));
        assertTrue(source.contains("aiReportService.get(studentNo, id)"));
        assertTrue(source.contains("response.put(\"success\", result.success())"));
        assertTrue(source.contains("response.put(\"report\", result.report())"));
        assertTrue(source.contains("response.put(\"data\", result.data())"));
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
