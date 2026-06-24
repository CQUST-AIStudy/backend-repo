package com.tap.backend.service.grading.animation;

import java.util.List;
import java.util.Map;

/**
 * 题目上下文：关联的实验/作业信息。
 */
public record ProblemContext(
        Long experimentId,
        String experimentTitle,
        String experimentRequirements,
        List<Map<String, String>> testCases,
        String expectedOutput,
        String standardSolution
) {
    public ProblemContext {
        testCases = testCases == null ? List.of() : List.copyOf(testCases);
    }
}
