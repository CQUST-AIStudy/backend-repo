package com.tap.backend.academic.controller;

import com.tap.backend.academic.entity.LeetCodeProblem;
import com.tap.backend.academic.leetcode.execution.LeetCodeSubmissionFacade;
import com.tap.backend.academic.security.StudentSessionResolver;
import com.tap.backend.academic.service.LeetCodeExecutionService;
import com.tap.backend.academic.service.LeetCodeProblemService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/leetcode")
public class LeetCodeController {

    private final LeetCodeProblemService problemService;
    private final LeetCodeExecutionService executionService;
    private final LeetCodeSubmissionFacade submissionFacade;
    private final StudentSessionResolver studentSessionResolver;

    public LeetCodeController(
            LeetCodeProblemService problemService,
            LeetCodeExecutionService executionService,
            LeetCodeSubmissionFacade submissionFacade,
            StudentSessionResolver studentSessionResolver) {
        this.problemService = problemService;
        this.executionService = executionService;
        this.submissionFacade = submissionFacade;
        this.studentSessionResolver = studentSessionResolver;
    }

    @GetMapping("/problem/{problemId}")
    public ResponseEntity<Map<String, Object>> getProblem(@PathVariable Long problemId) {
        Map<String, Object> response = new HashMap<>();
        try {
            LeetCodeProblem problem = problemService.findById(problemId);
            if (problem == null) {
                response.put("success", false);
                response.put("message", "problem not found");
                return ResponseEntity.ok(response);
            }

            Map<String, Object> problemData = new HashMap<>();
            problemData.put("id", problem.getId());
            problemData.put("problemCode", problem.getProblemCode());
            problemData.put("title", problem.getTitleMain());
            problemData.put("titleAlt", problem.getTitleAlt());
            problemData.put("difficulty", problem.getDifficulty());
            problemData.put("problemText", problem.getProblemText());
            problemData.put("solutionText", problem.getSolutionText());
            problemData.put("estimatedMinutes", problem.getEstimatedMinutes());
            problemData.put("sampleTestCases", generateSampleTestCases());

            response.put("success", true);
            response.put("data", problemData);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "failed to load problem: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runCode(
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest) {
        Map<String, Object> response = new HashMap<>();
        try {
            Integer studentId = getCurrentStudentId(httpRequest);
            if (studentId == null) {
                response.put("success", false);
                response.put("message", "authentication required");
                return ResponseEntity.ok(response);
            }

            Long problemId = requireLong(request, "problemId");
            String code = requireText(request, "code");
            String language = requireText(request, "language");
            String testInput = optionalText(request, "testInput");

            Map<String, Object> result = executionService.runCode(problemId, code, language, testInput);
            response.put("success", true);
            response.put("data", result);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "failed to run code: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submitSolution(
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest) {
        Map<String, Object> response = new HashMap<>();
        try {
            String studentNo = resolveStudentNo(httpRequest);
            Integer studentId = parseInteger(studentNo);
            if (studentId == null) {
                response.put("success", false);
                response.put("message", "authentication required");
                return ResponseEntity.ok(response);
            }

            Long problemId = requireLong(request, "problemId");
            String code = requireText(request, "code");
            String language = requireText(request, "language");
            String recommendationRequestId = optionalText(request, "recommendationRequestId");
            String recommendationSessionId = optionalText(request, "recommendationSessionId");

            Map<String, Object> result = submissionFacade.submitSolution(
                    studentId,
                    studentNo,
                    problemId,
                    code,
                    language,
                    recommendationRequestId,
                    recommendationSessionId
            );

            response.put("success", true);
            response.put("data", result);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "failed to submit solution: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    @GetMapping("/test/problems")
    public ResponseEntity<Map<String, Object>> getAllProblems() {
        Map<String, Object> response = new HashMap<>();
        try {
            List<LeetCodeProblem> problems = problemService.findAll();
            response.put("success", true);
            response.put("count", problems.size());
            response.put("data", problems);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "failed to load problem list: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    private Integer getCurrentStudentId(HttpServletRequest request) {
        try {
            return parseInteger(studentSessionResolver.requireStudentId(request));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Returns the canonical student_no string for the current session.
     * Used both as the integer studentId (parsed) and as the wrong-question
     * notebook studentNo (raw string).
     */
    private String resolveStudentNo(HttpServletRequest request) {
        try {
            return studentSessionResolver.requireStudentId(request);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Long requireLong(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        try {
            return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(String.valueOf(value).trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(key + " is invalid");
        }
    }

    private String requireText(Map<String, Object> request, String key) {
        String value = optionalText(request, key);
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private String optionalText(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof Number number) {
                int parsed = number.intValue();
                return parsed > 0 ? parsed : null;
            }
            int parsed = Integer.parseInt(String.valueOf(value).trim());
            return parsed > 0 ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String[] generateSampleTestCases() {
        return new String[]{"sample input 1", "sample input 2"};
    }
}
