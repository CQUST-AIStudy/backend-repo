package com.tap.backend.api.teaching;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class TeachingAdviceControllerTest {
    @Test
    void exposesDedicatedTeachingAdviceRoutes() {
        RequestMapping base = TeachingAdviceController.class.getAnnotation(RequestMapping.class);
        assertTrue(Arrays.asList(base.value()).contains("/api/teacher/teaching-advice"));

        assertTrue(hasGet("options", "/options"));
        assertTrue(hasGet("context", "/context"));
        assertTrue(hasPost("generate", "/reports"));
        assertTrue(hasGet("list", "/reports"));
        assertTrue(hasGet("get", "/reports/{reportId}"));
    }

    private boolean hasGet(String methodName, String path) {
        return Arrays.stream(TeachingAdviceController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .map(method -> method.getAnnotation(GetMapping.class))
                .filter(mapping -> mapping != null)
                .anyMatch(mapping -> Arrays.asList(mapping.value()).contains(path));
    }

    private boolean hasPost(String methodName, String path) {
        return Arrays.stream(TeachingAdviceController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(mapping -> mapping != null)
                .anyMatch(mapping -> Arrays.asList(mapping.value()).contains(path));
    }
}
