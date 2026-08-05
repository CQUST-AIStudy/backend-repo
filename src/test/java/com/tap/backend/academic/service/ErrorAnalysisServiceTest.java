package com.tap.backend.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.tap.backend.academic.dao.AiErrorAnalysisReportDao;
import com.tap.backend.academic.dao.teacherexperiment.TeacherExperimentQueryDao;
import com.tap.backend.academic.entity.Experiment;
import com.tap.backend.academic.entity.StudentSubmissionAttempt;
import com.tap.backend.academic.teacherexperiment.TeacherSubmissionProblemRow;
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
        compileError.setRawJson("{\"errorMessage\":\"main.cpp:5: error: expected ';'\"}");
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
        @SuppressWarnings("unchecked")
        Map<String, Object> compileCategory = categories.stream()
                .filter(item -> "COMPILE_ERROR".equals(item.get("type")))
                .findFirst().orElseThrow();
        assertTrue(((List<String>) compileCategory.get("suggestions")).stream()
                .anyMatch(item -> item.contains("main.cpp:5")));
        verify(reportDao).save(any());
    }

    @Test
    void unavailableMicroserviceUsesBackendAiProviderBeforeRuleFallback() {
        StudentSubmissionAttempt wrongAnswer = attempt(
                "WRONG_ANSWER", "反转链表", "int main() { return 1; }", 2000L);
        StudentSubmissionAttempt partialAccepted = attempt(
                "PARTIAL_ACCEPTED", "反转链表", "int main() { return 2; }", 1500L);
        when(queryDao.findSubmissionAttemptsForErrorAnalysis("2026001", 7))
                .thenReturn(List.of(wrongAnswer, partialAccepted));
        when(aiProvider.name()).thenReturn("openai");
        when(aiProvider.model()).thenReturn("deepseek-chat");
        when(aiProvider.chatJson(any(), any(), anyInt())).thenReturn("""
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
                          "count": 99,
                          "isSystemic": false,
                          "rootCause": "待验证：当前只有答案错误状态，缺少具体失败测试点，需要用最小链表样例定位。",
                          "specificIssues": [
                            "第5天应为 Fishing",
                            "两次提交相同代码均得5分",
                            "条件 (x % 5) <= 3 && (x % 5) != 0 在余数为0时输出 Drying，但第5天应为 Fishing",
                            "税额表达式 taxable * 0.03 导致应缴税只计算为135元",
                            "条件 (x % 5) <= 3 && (x % 5) != 0 在余数为0时输出 Drying，但第5天应为 Fishing"
                          ],
                          "suggestions": ["分别构造空链表和单节点链表", "逐步核对指针更新后的输出顺序"]
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
        assertTrue(String.valueOf(result.get("overallAssessment")).startsWith("真实判题记录显示"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> categories =
                (List<Map<String, Object>>) result.get("errorCategories");
        assertEquals(1, categories.size());
        assertEquals("WRONG_ANSWER", categories.get(0).get("type"));
        assertEquals(2, categories.get(0).get("count"));
        assertTrue(String.valueOf(categories.get(0).get("rootCause")).startsWith("待验证："));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> problemAnalyses =
                (List<Map<String, Object>>) result.get("problemAnalyses");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> problemCategories =
                (List<Map<String, Object>>) problemAnalyses.get(0).get("errorCategories");
        Map<String, Object> guardedCategory = problemCategories.get(0);
        assertTrue(String.valueOf(guardedCategory.get("rootCause")).startsWith("待验证："));
        assertEquals(2, guardedCategory.get("count"));
        assertEquals(true, guardedCategory.get("isSystemic"));
        @SuppressWarnings("unchecked")
        List<String> guardedIssues = (List<String>) guardedCategory.get("specificIssues");
        assertTrue(guardedIssues.get(0).startsWith("判题证据："));
        assertTrue(guardedIssues.get(0).contains("2 次未通过记录"));
        assertEquals(3, guardedIssues.size());
        assertTrue(guardedIssues.get(1).contains("条件 (x % 5) <= 3 && (x % 5) != 0 在余数为0时输出 Drying"));
        assertTrue(guardedIssues.get(2).contains("税额表达式 taxable * 0.03"));
        assertTrue(guardedIssues.stream().noneMatch(item -> item.contains("第5天应为")
                || item.contains("应缴税") || item.contains("135元") || item.contains("期望输出")
                || item.contains("相同代码") || item.contains("均得") || item.contains("条件错误")));
        @SuppressWarnings("unchecked")
        List<String> guardedSuggestions = (List<String>) guardedCategory.get("suggestions");
        assertEquals(3, guardedSuggestions.size());
        assertTrue(guardedSuggestions.get(0).startsWith("步骤1："));
        assertTrue(guardedSuggestions.get(1).startsWith("步骤2："));
        assertTrue(guardedSuggestions.get(2).startsWith("步骤3："));
        assertTrue(guardedSuggestions.stream().noneMatch(item -> item.contains("期望输出")));
        verify(aiProvider).chatJson(any(), any(), eq(6000));
        verify(reportDao).save(any());
    }

    @Test
    void incompleteAiResponseDoesNotDowngradeEveryProblemToRuleFallback() {
        StudentSubmissionAttempt wrongAnswer = attempt(
                "WRONG_ANSWER", "first problem", "int main() { return 1; }", 2000L);
        wrongAnswer.setProblemId(11L);
        StudentSubmissionAttempt compileError = attempt(
                "COMPILE_ERROR", "second problem", "int main( {", 1000L);
        compileError.setProblemId(22L);
        when(queryDao.findSubmissionAttemptsForErrorAnalysis("2026001", 7))
                .thenReturn(List.of(wrongAnswer, compileError));
        when(aiProvider.name()).thenReturn("openai");
        when(aiProvider.model()).thenReturn("deepseek-chat");
        when(aiProvider.chatJson(any(), any(), anyInt())).thenReturn("""
                {
                  "overallAssessment": "The first problem has a real wrong-answer record.",
                  "errorCategories": [
                    {
                      "type": "WRONG_ANSWER",
                      "count": 1,
                      "rootCause": "pending evidence for this judge result",
                      "specificIssues": ["judge result is wrong answer"],
                      "suggestions": ["reproduce with a minimal boundary case"]
                    }
                  ],
                  "problemAnalyses": [
                    {
                      "problemId": 11,
                      "problemTitle": "first problem",
                      "errorCategories": [
                        {
                          "type": "WRONG_ANSWER",
                          "count": 1,
                          "rootCause": "pending evidence for this judge result",
                          "specificIssues": ["judge result is wrong answer"],
                          "suggestions": ["reproduce with a minimal boundary case"]
                        }
                      ]
                    }
                  ]
                }
                """);
        ReflectionTestUtils.setField(service, "aiProvider", aiProvider);

        Map<String, Object> result = service.analyzeErrorFromDb("2026001", "student", 7, true);

        assertEquals("AI_MODEL", result.get("generationMode"));
        assertTrue((Boolean) result.get("aiGenerated"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> analyses =
                (List<Map<String, Object>>) result.get("problemAnalyses");
        Map<String, Object> first = analyses.stream()
                .filter(item -> Long.valueOf(11L).equals(item.get("problemId")))
                .findFirst().orElseThrow();
        Map<String, Object> second = analyses.stream()
                .filter(item -> Long.valueOf(22L).equals(item.get("problemId")))
                .findFirst().orElseThrow();
        assertEquals("AI_MODEL", first.get("generationMode"));
        assertEquals("RULE_FALLBACK", second.get("generationMode"));
        verify(aiProvider).chatJson(any(), any(), eq(6000));
    }

    @Test
    void backendAiPromptUsesCompactRepresentativeEvidence() {
        List<StudentSubmissionAttempt> attempts = new java.util.ArrayList<>();
        attempts.add(attempt("ACCEPTED", "已通过题", "AC_CODE_SHOULD_NOT_BE_SENT", 1000L));
        for (int i = 0; i < 10; i++) {
            attempts.add(attempt("WRONG_ANSWER", "第" + i + "题", "x".repeat(1500), 2000L + i));
        }
        attempts.get(1).setRawJson("{\"errorMessage\":\"main.cpp:7: error: expected ';'\"}");
        TeacherSubmissionProblemRow problem = new TeacherSubmissionProblemRow();
        problem.setProblemId(11L);
        problem.setProblemNo("7-1");
        problem.setProblemTitle("第0题");
        problem.setStatementMd("输出结果必须保留两位小数。");
        when(queryDao.findSubmissionProblemRows("2026001", 7)).thenReturn(List.of(problem));
        when(queryDao.findSubmissionAttemptsForErrorAnalysis("2026001", 7)).thenReturn(attempts);
        when(aiProvider.name()).thenReturn("openai");
        when(aiProvider.model()).thenReturn("deepseek-chat");
        when(aiProvider.chatJson(any(), any(), anyInt())).thenReturn("""
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
                          "rootCause": "待验证：同一题真实判题记录连续十次为答案错误，但缺少失败用例，需要逐项核对题面要求。",
                          "specificIssues": ["十次提交均为答案错误", "代码内容超过分析长度并已截断"],
                          "suggestions": ["先使用题目最小样例核对输出", "再检查截断位置之后的边界处理"]
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
        verify(aiProvider).chatJson(prompt.capture(), any(), eq(6000));
        String sent = prompt.getValue();
        assertFalse(sent.contains("AC_CODE_SHOULD_NOT_BE_SENT"));
        assertTrue(sent.contains("selectedErrorAttempts"));
        assertTrue(sent.contains("LATEST_PROBLEM_STATE_SNAPSHOT"));
        assertTrue(sent.contains("0001 |"));
        assertTrue(sent.contains("problemRequirements"));
        assertTrue(sent.contains("输出结果必须保留两位小数"));
        assertTrue(sent.contains("代码中的具体位置/表达式"));
        assertTrue(sent.contains("specificIssues 输出 2 至 4 条定位依据"));
        assertTrue(sent.contains("\"problemId\":11"));
        assertTrue(sent.contains("main.cpp:7: error: expected ';'"));
        assertTrue(sent.contains("truncated for AI prompt"));
        assertTrue(sent.length() < 18000);
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
    void missingAttemptHistoryIsCompletedFromRawHistoryAndCurrentProblemState() {
        StudentSubmissionAttempt otherProblem = attempt(
                "COMPILE_ERROR", "第二题", "int main( {", 1500L);
        otherProblem.setProblemId(22L);
        StudentSubmissionAttempt historicalWrongAnswer = attempt(
                "WRONG_ANSWER", "求1到N的和", "int main() { return 1; }", 1000L);
        historicalWrongAnswer.setProblemId(11L);

        TeacherSubmissionProblemRow currentState = new TeacherSubmissionProblemRow();
        currentState.setProblemId(11L);
        currentState.setProblemTitle("求1到N的和");
        currentState.setLatestStatus("ACCEPTED");
        currentState.setSubmitTime(new Date(2000L));
        currentState.setCode("int main() { return 0; }");

        when(queryDao.findSubmissionProblemRows("2026001", 7)).thenReturn(List.of(currentState));
        when(queryDao.findSubmissionAttemptsForErrorAnalysis("2026001", 7))
                .thenReturn(List.of(otherProblem));
        when(queryDao.findSubmissionAttemptsFromRaw("2026001", 7))
                .thenReturn(List.of(historicalWrongAnswer));

        Map<String, Object> result = service.analyzeErrorFromDb("2026001", "张三", 7, true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> analyses =
                (List<Map<String, Object>>) result.get("problemAnalyses");
        Map<String, Object> firstProblem = analyses.stream()
                .filter(item -> Long.valueOf(11L).equals(item.get("problemId")))
                .findFirst().orElseThrow();
        assertEquals("ACCEPTED", firstProblem.get("latestJudgeStatus"));
        assertEquals("int main() { return 0; }", firstProblem.get("latestCode"));
        assertEquals("WRONG_ANSWER", firstCategoryType(firstProblem));
        assertTrue(analyses.stream().anyMatch(item -> Long.valueOf(22L).equals(item.get("problemId"))));
    }

    @Test
    void rawHistoryIsMergedWhenPrimaryAlreadyContainsCurrentProblemState() {
        StudentSubmissionAttempt currentAccepted = attempt(
                "ACCEPTED", "求1到N的和", "int main() { return 0; }", 2000L);
        currentAccepted.setProblemId(11L);
        StudentSubmissionAttempt duplicatedCurrent = attempt(
                "ACCEPTED", "求1到N的和", "int main() { return 0; }", 2000L);
        duplicatedCurrent.setProblemId(11L);
        duplicatedCurrent.setRawJson("{\"submissionId\":2}");
        StudentSubmissionAttempt historicalWrongAnswer = attempt(
                "WRONG_ANSWER", "求1到N的和", "int main() { return 1; }", 1000L);
        historicalWrongAnswer.setProblemId(11L);
        historicalWrongAnswer.setRawJson("{\"submissionId\":1}");

        TeacherSubmissionProblemRow currentState = new TeacherSubmissionProblemRow();
        currentState.setProblemId(11L);
        currentState.setProblemTitle("求1到N的和");
        currentState.setLatestStatus("ACCEPTED");
        currentState.setSubmitTime(new Date(2000L));
        currentState.setCode("int main() { return 0; }");

        when(queryDao.findSubmissionProblemRows("2026001", 7)).thenReturn(List.of(currentState));
        when(queryDao.findSubmissionAttemptsForErrorAnalysis("2026001", 7))
                .thenReturn(List.of(currentAccepted));
        when(queryDao.findSubmissionAttemptsFromRaw("2026001", 7))
                .thenReturn(List.of(historicalWrongAnswer, duplicatedCurrent));

        Map<String, Object> result = service.analyzeErrorFromDb("2026001", "张三", 7, true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> analyses =
                (List<Map<String, Object>>) result.get("problemAnalyses");
        Map<String, Object> problem = analyses.stream()
                .filter(item -> Long.valueOf(11L).equals(item.get("problemId")))
                .findFirst().orElseThrow();
        assertEquals("ACCEPTED", problem.get("latestJudgeStatus"));
        assertEquals("WRONG_ANSWER", firstCategoryType(problem));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> categories =
                (List<Map<String, Object>>) problem.get("errorCategories");
        assertEquals(1, categories.get(0).get("count"));
        assertEquals(2, ((List<?>) result.get("submissions")).size());
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
