package com.tap.backend.academic.service.impl;

import com.tap.backend.academic.entity.LeetCodeProblem;
import com.tap.backend.academic.leetcode.execution.AiEvaluationResult;
import com.tap.backend.academic.leetcode.execution.CodeExecutionResult;
import com.tap.backend.academic.leetcode.execution.LeetCodeAiEvaluationService;
import com.tap.backend.academic.leetcode.execution.LeetCodeCodeExecutionEngine;
import com.tap.backend.academic.service.LeetCodeExecutionService;
import com.tap.backend.academic.service.LeetCodeProblemService;
import com.tap.backend.academic.service.StudentSkillProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class LeetCodeExecutionServiceImpl implements LeetCodeExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(LeetCodeExecutionServiceImpl.class);
    private static final int AI_ESTIMATED_TOTAL_CASES = 10;

    private final LeetCodeProblemService problemService;
    @SuppressWarnings("unused")
    private final StudentSkillProfileService skillProfileService;
    private final LeetCodeCodeExecutionEngine codeExecutionEngine;
    private final LeetCodeAiEvaluationService aiEvaluationService;

    public LeetCodeExecutionServiceImpl(
            LeetCodeProblemService problemService,
            StudentSkillProfileService skillProfileService,
            LeetCodeCodeExecutionEngine codeExecutionEngine,
            LeetCodeAiEvaluationService aiEvaluationService) {
        this.problemService = problemService;
        this.skillProfileService = skillProfileService;
        this.codeExecutionEngine = codeExecutionEngine;
        this.aiEvaluationService = aiEvaluationService;
    }

    @Override
    public Map<String, Object> runCode(Long problemId, String code, String language, String testInput) {
        Map<String, Object> result = new HashMap<>();
        try {
            LeetCodeProblem problem = problemService.findById(problemId);
            if (problem == null) {
                result.put("status", "error");
                result.put("output", "Problem not found");
                return result;
            }

            if (!codeExecutionEngine.supports(language)) {
                result.put("status", "error");
                result.put("output", "Unsupported language: " + language);
                return result;
            }

            CodeExecutionResult executionResult = codeExecutionEngine.execute(code, language, testInput);
            result.put("status", executionResult.success() ? "success" : "error");
            result.put("output", executionResult.output());
            result.put("error", executionResult.error());
            result.put("runtime", executionResult.runtime() + "ms");
            return result;
        } catch (Exception e) {
            logger.error("Run code failed", e);
            result.put("status", "error");
            result.put("output", "Run failed: " + e.getMessage());
            return result;
        }
    }

    @Override
    public Map<String, Object> submitSolution(Integer studentId, Long problemId, String code, String language) {
        Map<String, Object> result = new HashMap<>();
        try {
            String normalizedLanguage = codeExecutionEngine.normalizeLanguage(language);
            if (!codeExecutionEngine.supports(normalizedLanguage)) {
                result.put("accepted", false);
                result.put("status", "failed");
                result.put("message", "Unsupported language: " + language);
                return result;
            }
            if (code == null || code.trim().isEmpty()) {
                result.put("accepted", false);
                result.put("status", "failed");
                result.put("message", "Code cannot be empty");
                return result;
            }

            LeetCodeProblem problem = problemService.findById(problemId);
            if (problem == null) {
                result.put("accepted", false);
                result.put("status", "failed");
                result.put("message", "Problem not found");
                return result;
            }

            AiEvaluationResult evaluation = aiEvaluationService.evaluate(problem, code, normalizedLanguage);
            boolean accepted = evaluation.accepted();
            int score = clampScore(evaluation.score());
            int passedCases = (int) Math.round(clamp01(evaluation.estimatedPassRate()) * AI_ESTIMATED_TOTAL_CASES);

            if (!evaluation.unavailable()) {
                updateStudentSkillProfile(studentId, problem, accepted);
            }

            result.put("accepted", accepted);
            result.put("status", evaluation.unavailable() ? "unavailable" : (accepted ? "success" : "failed"));
            result.put("score", evaluation.unavailable() ? null : score);
            result.put("aiFeedback", evaluation.feedback());

            Map<String, Object> details = new HashMap<>();
            details.put("passedCases", evaluation.unavailable() ? 0 : passedCases);
            details.put("totalCases", evaluation.unavailable() ? 0 : AI_ESTIMATED_TOTAL_CASES);
            details.put("runtime", "AI static review");
            details.put("memory", "N/A");
            details.put("confidence", String.format(Locale.ROOT, "%.0f%%", clamp01(evaluation.confidence()) * 100));
            if (!evaluation.riskNotes().isEmpty()) {
                details.put("error", String.join("\n", evaluation.riskNotes()));
            }
            result.put("details", details);
            result.put("skillSuggestions", evaluation.skillSuggestions().isEmpty()
                    ? defaultSkillSuggestions(accepted)
                    : evaluation.skillSuggestions());
            return result;
        } catch (Exception e) {
            logger.error("Submit solution failed", e);
            result.put("accepted", false);
            result.put("status", "failed");
            result.put("message", "Submit failed: " + e.getMessage());
            return result;
        }
    }

    private List<String> defaultSkillSuggestions(boolean accepted) {
        if (accepted) {
            return new ArrayList<>(List.of("Algorithm optimization", "Code readability", "Boundary-case verification"));
        }
        return new ArrayList<>(List.of("Language fundamentals", "Boundary-case handling", "Complexity analysis"));
    }

    private int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private double clamp01(double value) {
        return Math.max(0d, Math.min(1d, value));
    }

    private void updateStudentSkillProfile(Integer studentId, LeetCodeProblem problem, boolean success) {
        logger.info("Update skill profile studentId={} problemId={} success={}",
                studentId,
                problem == null ? null : problem.getId(),
                success);
    }
}
