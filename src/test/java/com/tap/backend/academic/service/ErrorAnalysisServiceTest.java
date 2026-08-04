package com.tap.backend.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.tap.backend.academic.dao.AiErrorAnalysisReportDao;
import com.tap.backend.academic.dao.teacherexperiment.TeacherExperimentQueryDao;
import com.tap.backend.academic.entity.Experiment;
import com.tap.backend.academic.entity.StudentSubmissionAttempt;
import com.tap.backend.ai.AiProvider;
import com.tap.backend.email.EmailService;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ErrorAnalysisServiceTest {

    @Mock
    private TeacherExperimentQueryDao queryDao;
    @Mock
    private AiErrorAnalysisReportDao reportDao;
    @Mock
    private ExperimentService experimentService;
    @Mock
    private EmailService emailService;
    @Mock
    private AiProvider aiProvider;

    private ErrorAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new ErrorAnalysisService();
        ReflectionTestUtils.setField(service, "teacherExperimentQueryDao", queryDao);
        ReflectionTestUtils.setField(service, "reportDao", reportDao);
        ReflectionTestUtils.setField(service, "experimentService", experimentService);
        ReflectionTestUtils.setField(service, "emailService", emailService);
        ReflectionTestUtils.setField(service, "errorAnalysisBaseUrl", "http://127.0.0.1:1");

        Experiment experiment = new Experiment();
        experiment.setName("链表实验");
        lenient().when(experimentService.findExperimentById(7)).thenReturn(experiment);
    }

    @Test
    void unavailableMicroserviceUsesRealJudgeRecordsForRuleFallback() {
        StudentSubmissionAttempt compileError = attempt(
                "COMPILE_ERROR", "反转链表", "int main() {", 1000L);
        StudentSubmissionAttempt wrongAnswer = attempt(
                "WRONG_ANSWER", "反转链表", "int main() { return 1; }", 2000L);
        when(queryDao.findSubmissionAttemptsForErrorAnalysis("2026001", 7))
                .thenReturn(List.of(compileError, wrongAnswer));

        Map<String, Object> result = service.analyzeErrorFromDb("2026001", "张三", 7, true);

        assertNotNull(result);
        assertEquals("RULE_FALLBACK", result.get("generationMode"));
        assertFalse((Boolean) result.get("aiGenerated"));
        assertEquals("system", result.get("provider"));
        assertEquals("AI_SERVICE_UNAVAILABLE", result.get("fallbackReason"));
        assertEquals("WRONG_ANSWER", result.get("latestJudgeStatus"));
        assertEquals("int main() { return 1; }", result.get("latestCode"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> categories =
                (List<Map<String, Object>>) result.get("errorCategories");
        assertEquals(2, categories.size());
        assertTrue(categories.stream().anyMatch(item -> "COMPILE_ERROR".equals(item.get("type"))));
        assertTrue(categories.stream().anyMatch(item -> "WRONG_ANSWER".equals(item.get("type"))));
        verify(reportDao).save(any());
    }

    @Test
    void unavailableMicroserviceUsesBackendAiProviderBeforeRuleFallback() {
        StudentSubmissionAttempt wrongAnswer = attempt(
                "WRONG_ANSWER", "反转链表", "int main() { return 1; }", 2000L);
        when(queryDao.findSubmissionAttemptsForErrorAnalysis("2026001", 7))
                .thenReturn(List.of(wrongAnswer));
        when(aiProvider.name()).thenReturn("openai");
        when(aiProvider.model()).thenReturn("deepseek-chat");
        when(aiProvider.chat(any(), any())).thenReturn("""
                {
                  "severity": "MEDIUM",
                  "overallAssessment": "代码可以编译运行，但真实判题结果为 WRONG_ANSWER，需要先核对边界条件和输出格式。",
                  "errorCategories": [
                    {
                      "type": "WRONG_ANSWER",
                      "count": 1,
                      "isSystemic": false,
                      "rootCause": "判题结果显示答案错误，当前证据只能定位到输出不符合期望。",
                      "specificIssues": ["反转链表"],
                      "suggestions": ["补充空链表、单节点和多节点样例后重新提交"]
                    }
                  ],
                  "learningSuggestions": [
                    {
                      "priority": "MEDIUM",
                      "topic": "边界条件",
                      "reason": "真实提交记录中出现 WRONG_ANSWER",
                      "suggestedResources": "先用最小样例手工推演输出"
                    }
                  ],
                  "problemAnalyses": [
                    {
                      "problemId": 11,
                      "problemTitle": "反转链表",
                      "severity": "MEDIUM",
                      "overallAssessment": "本题真实判题结果为 WRONG_ANSWER，需要核对边界条件。",
                      "errorCategories": [
                        {
                          "type": "WRONG_ANSWER",
                          "count": 1,
                          "isSystemic": false,
                          "rootCause": "本题输出不符合判题预期。",
                          "specificIssues": ["反转链表"],
                          "suggestions": ["补充最小样例后重试"]
                        }
                      ],
                      "learningSuggestions": []
                    }
                  ]
                }
                """);
        ReflectionTestUtils.setField(service, "aiProvider", aiProvider);

        Map<String, Object> result = service.analyzeErrorFromDb("2026001", "张三", 7, true);

        assertNotNull(result);
        assertEquals("AI_MODEL", result.get("generationMode"));
        assertTrue((Boolean) result.get("aiGenerated"));
        assertEquals("openai", result.get("provider"));
        assertEquals("deepseek-chat", result.get("model"));
        assertEquals("WRONG_ANSWER", result.get("latestJudgeStatus"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> categories =
                (List<Map<String, Object>>) result.get("errorCategories");
        assertEquals(1, categories.size());
        assertEquals("WRONG_ANSWER", categories.get(0).get("type"));
        verify(aiProvider).chat(any(), any());
        verify(reportDao).save(any());
    }

    @Test
    void backendAiPromptUsesCompactRepresentativeEvidence() {
        List<StudentSubmissionAttempt> attempts = new java.util.ArrayList<>();
        attempts.add(attempt("ACCEPTED", "已通过题", "AC_CODE_SHOULD_NOT_BE_SENT", 1000L));
        for (int i = 0; i < 10; i++) {
            attempts.add(attempt("WRONG_ANSWER", "第" + i + "题", "x".repeat(1500), 2000L + i));
        }
        when(queryDao.findSubmissionAttemptsForErrorAnalysis("2026001", 7)).thenReturn(attempts);
        when(aiProvider.name()).thenReturn("openai");
        when(aiProvider.model()).thenReturn("deepseek-chat");
        when(aiProvider.chat(any(), any())).thenReturn("""
                {
                  "severity": "MEDIUM",
                  "overallAssessment": "根据代表性错误提交分析，主要问题是 WRONG_ANSWER。",
                  "errorCategories": [
                    {
                      "type": "WRONG_ANSWER",
                      "count": 10,
                      "isSystemic": true,
                      "rootCause": "真实判题结果多次为 WRONG_ANSWER。",
                      "specificIssues": ["多题答案错误"],
                      "suggestions": ["按题目最小样例逐题核对输出"]
                    }
                  ],
                  "learningSuggestions": [],
                  "problemAnalyses": [
                    {
                      "problemId": 11,
                      "problemTitle": "第0题",
                      "severity": "MEDIUM",
                      "overallAssessment": "代表性错误提交为 WRONG_ANSWER。",
                      "errorCategories": [
                        {
                          "type": "WRONG_ANSWER",
                          "count": 10,
                          "isSystemic": true,
                          "rootCause": "真实判题结果多次为 WRONG_ANSWER。",
                          "specificIssues": ["按题核对输出"],
                          "suggestions": ["使用最小样例逐步检查"]
                        }
                      ],
                      "learningSuggestions": []
                    }
                  ]
                }
                """);
        ReflectionTestUtils.setField(service, "aiProvider", aiProvider);

        service.analyzeErrorFromDb("2026001", "张三", 7, true);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(aiProvider).chat(prompt.capture(), any());
        String sent = prompt.getValue();
        assertFalse(sent.contains("AC_CODE_SHOULD_NOT_BE_SENT"));
        assertTrue(sent.contains("selectedErrorAttempts"));
        assertTrue(sent.contains("\"problemId\":11"));
        assertTrue(sent.contains("truncated for AI prompt"));
        assertTrue(sent.length() < 15000);
    }

    @Test
    void unavailableMicroserviceKeepsAllAcceptedResultAsJudgeResult() {
        StudentSubmissionAttempt accepted = attempt(
                "ACCEPTED", "反转链表", "int main() { return 0; }", 1000L);
        when(queryDao.findSubmissionAttemptsForErrorAnalysis("2026001", 7))
                .thenReturn(List.of(accepted));

        Map<String, Object> result = service.analyzeErrorFromDb("2026001", "张三", 7, true);

        assertNotNull(result);
        assertEquals("JUDGE_RESULT", result.get("generationMode"));
        assertFalse((Boolean) result.get("aiGenerated"));
        assertEquals("JUDGE_RESULT_ONLY", result.get("fallbackReason"));
        assertTrue(((List<?>) result.get("errorCategories")).isEmpty());
    }

    @Test
    void ruleFallbackKeepsEachProblemAnalysisSeparatedByProblemId() {
        StudentSubmissionAttempt wrongAnswer = attempt(
                "WRONG_ANSWER", "判断是否构成三角形", "int main() { return 1; }", 1000L);
        wrongAnswer.setProblemId(11L);
        StudentSubmissionAttempt compileError = attempt(
                "COMPILE_ERROR", "计算机演示", "int main( {", 2000L);
        compileError.setProblemId(22L);
        when(queryDao.findSubmissionAttemptsForErrorAnalysis("2026001", 7))
                .thenReturn(List.of(wrongAnswer, compileError));

        Map<String, Object> result = service.analyzeErrorFromDb("2026001", "张三", 7, true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> analyses =
                (List<Map<String, Object>>) result.get("problemAnalyses");
        assertEquals(2, analyses.size());
        Map<String, Object> triangle = analyses.stream()
                .filter(item -> Long.valueOf(11L).equals(item.get("problemId")))
                .findFirst().orElseThrow();
        Map<String, Object> demo = analyses.stream()
                .filter(item -> Long.valueOf(22L).equals(item.get("problemId")))
                .findFirst().orElseThrow();
        assertEquals("判断是否构成三角形", triangle.get("problemTitle"));
        assertEquals("WRONG_ANSWER", triangle.get("latestJudgeStatus"));
        assertEquals("WRONG_ANSWER", firstCategoryType(triangle));
        assertEquals("计算机演示", demo.get("problemTitle"));
        assertEquals("COMPILE_ERROR", demo.get("latestJudgeStatus"));
        assertEquals("COMPILE_ERROR", firstCategoryType(demo));
    }

    @Test
    void unavailableWarningMicroserviceDoesNotFailErrorAnalysisFlow() {
        StudentSubmissionAttempt wrongAnswer = attempt(
                "WRONG_ANSWER", "反转链表", "int main() { return 1; }", 2000L);
        wrongAnswer.setProblemId(11L);
        when(queryDao.findSubmissionAttemptsForErrorAnalysis("2026001", 7))
                .thenReturn(List.of(wrongAnswer));

        Map<String, Object> result = service.warningAnalyzeFromDb(
                "2026001", "张三", 7, true);

        assertNotNull(result);
        assertEquals(false, result.get("triggered"));
        assertEquals(1, result.get("totalErrors"));
        assertEquals(1, result.get("problemCount"));
    }

    private StudentSubmissionAttempt attempt(
            String status,
            String title,
            String code,
            long submittedAtMillis) {
        StudentSubmissionAttempt attempt = new StudentSubmissionAttempt();
        attempt.setProblemId(11L);
        attempt.setJudgeStatus(status);
        attempt.setProblemTitle(title);
        attempt.setCode(code);
        attempt.setSubmittedAt(new Date(submittedAtMillis));
        return attempt;
    }

    @SuppressWarnings("unchecked")
    private String firstCategoryType(Map<String, Object> analysis) {
        List<Map<String, Object>> categories =
                (List<Map<String, Object>>) analysis.get("errorCategories");
        return (String) categories.get(0).get("type");
    }
}
