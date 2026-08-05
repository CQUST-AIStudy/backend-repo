package com.tap.backend.academic.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

import com.tap.backend.academic.entity.LeetCodeProblem;
import com.tap.backend.academic.leetcode.execution.LeetCodeSubmissionFacade;
import com.tap.backend.academic.security.StudentSessionResolver;
import com.tap.backend.academic.service.LeetCodeExecutionService;
import com.tap.backend.academic.service.LeetCodeProblemService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class LeetCodeControllerTest {

    @Mock LeetCodeProblemService problemService;
    @Mock LeetCodeExecutionService executionService;
    @Mock LeetCodeSubmissionFacade submissionFacade;
    @Mock StudentSessionResolver studentSessionResolver;

    private LeetCodeController controller;

    @BeforeEach
    void setUp() {
        controller = new LeetCodeController(
                problemService, executionService, submissionFacade, studentSessionResolver);
    }

    @Test
    void problemWithoutPersistedSamplesDoesNotReturnFabricatedInputs() {
        LeetCodeProblem problem = new LeetCodeProblem();
        problem.setId(7L);
        problem.setTitleMain("测试题目");
        when(problemService.findById(7L)).thenReturn(problem);

        ResponseEntity<Map<String, Object>> response = controller.getProblem(7L);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertEquals(List.of(), data.get("sampleTestCases"));
        assertFalse((Boolean) data.get("samplesAvailable"));
    }
}
