package com.tap.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.ai.AiProvider;
import com.tap.backend.repo.TeachingAdviceReportRepository;
import com.tap.backend.repo.TeachingClassRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class TeachingAdviceServiceTest {
    @Mock TeachingClassRepository classRepository;
    @Mock TeachingAdviceReportRepository reportRepository;
    @Mock AiProvider aiProvider;

    private TeachingAdviceService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        service = new TeachingAdviceService(
                classRepository,
                reportRepository,
                new TeachingAdvicePromptFactory(objectMapper),
                aiProvider,
                objectMapper);
    }

    @Test
    void rejectsInvalidScopeLevelBeforeReadingData() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.context(10L, "STUDENT", 1L, null, false));

        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    void rejectsClassOwnedByAnotherTeacher() {
        com.tap.backend.domain.classroom.TeachingClassEntity otherTeacherClass =
                new com.tap.backend.domain.classroom.TeachingClassEntity();
        ReflectionTestUtils.setField(otherTeacherClass, "teacherId", 11L);
        when(classRepository.findById(3L)).thenReturn(java.util.Optional.of(otherTeacherClass));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.context(10L, "CLASS", 3L, null, false));

        assertEquals(404, error.getStatusCode().value());
    }

    @Test
    void rejectsAiAdviceThatReferencesUnknownEvidence() {
        String raw = """
                {
                  "summary":"测试",
                  "risks":[{"title":"风险","evidenceRefs":["M99"]}],
                  "actions":[],
                  "limitations":[]
                }
                """;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "parseAndValidateAdvice", raw, Set.of("M01")));

        assertTrue(error.getMessage().contains("unknown evidence"));
    }

    @Test
    void mockAdviceAlwaysUsesTheStructuredSchema() {
        Map<String, Object> metrics = Map.of(
                "evidence", List.of(Map.of("evidenceId", "M01"), Map.of("evidenceId", "M02")),
                "scoreDistribution", Map.of("excellent", 2, "middle", 6, "risk", 3, "highRisk", 1, "incomplete", 1),
                "learningDiagnosis", Map.of(
                        "conclusion", "优先处理题目“找指定元素的前驱结点”的“前驱指针维护和当前结点移动顺序错误”。",
                        "nextTeachingAction", "用题目“找指定元素的前驱结点”讲清前驱指针维护和当前结点移动顺序错误。",
                        "problemErrorPoints", List.of(Map.of(
                                "title", "找指定元素的前驱结点",
                                "errorPoint", "前驱指针维护和当前结点移动顺序错误",
                                "teachingAdvice", "用题目“找指定元素的前驱结点”讲清前驱指针维护和当前结点移动顺序错误。"
                        )),
                        "inferredKnowledgeSignals", List.of(Map.of("knowledge", "链表前驱定位"))
                ),
                "focusStudents", List.of(Map.of(
                        "studentNo", "20230001",
                        "studentName", "张三",
                        "reason", "连续低分",
                        "suggestionHint", "课后检查基础语法"
                )));

        JsonNode advice = ReflectionTestUtils.invokeMethod(service, "fallbackAdvice", "CLASS", metrics);
        ReflectionTestUtils.invokeMethod(service, "validateAdviceQuality", advice, Set.of("M01", "M02"));

        assertTrue(advice.hasNonNull("summary"));
        assertTrue(advice.hasNonNull("teachingConclusion"));
        assertTrue(advice.hasNonNull("markdown"));
        assertTrue(advice.path("risks").isArray());
        assertTrue(advice.path("teacherFocus").isArray());
        assertTrue(advice.path("nextTeachingPlan").isObject());
        assertTrue(advice.path("nextTeachingPlan").path("steps").isArray());
        assertTrue(advice.path("nextClassPlan").isArray());
        assertTrue(advice.path("differentiatedTeaching").isObject());
        assertTrue(advice.path("quickActions").isArray());
        assertTrue(advice.path("actions").isArray());
        assertTrue(advice.path("focusStudents").isArray());
        assertTrue(advice.path("limitations").isArray());
        assertTrue(advice.path("summary").asText().contains("前驱指针"));
        assertTrue(advice.path("nextTeachingPlan").path("summary").asText().contains("前驱指针"));
        assertTrue(advice.path("teachingConclusion").path("problem").asText().contains("前驱指针"));
        JsonNode firstStep = advice.path("nextTeachingPlan").path("steps").get(0);
        assertTrue(firstStep.path("teacherAction").asText().contains("找指定元素的前驱结点"));
        assertTrue(firstStep.path("teacherAction").asText().contains("前驱指针"));
        assertTrue(firstStep.path("studentTask").asText().contains("边界样例"));
        assertTrue(firstStep.path("successMetric").asText().contains("修正顺序"));
        assertTrue(firstStep.path("material").asText().contains("找指定元素的前驱结点"));
        assertTrue(firstStep.path("targetStudents").asText().contains("未通过"));
        assertTrue(firstStep.path("deliverable").asText().contains("边界样例"));
        assertTrue(firstStep.path("checkMethod").asText().contains("抽问"));
        assertTrue(!firstStep.path("teacherAction").asText().contains("典型错误样例"));
        assertEquals("M01", advice.path("risks").get(0).path("evidenceRefs").get(0).asText());
        assertEquals("20230001", advice.path("focusStudents").get(0).path("studentNo").asText());
        assertTrue(advice.path("focusStudents").get(0).hasNonNull("teacherAction"));
        assertTrue(advice.path("actions").get(0).hasNonNull("successMetric"));
    }

    @Test
    void legacyStructuredAdviceWithoutExecutableDetailsIsRejected() {
        String raw = """
                {
                  "summary":"建议优先处理低分题目",
                  "risks":[{"title":"风险","level":"MEDIUM","evidenceRefs":["M01"]}],
                  "actions":[{"priority":1,"action":"讲解薄弱题","target":"低分学生","evidenceRefs":["M01"],"successMetric":"正确率提升"}],
                  "limitations":[]
                }
                """;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "parseAndValidateAdvice", raw, Set.of("M01")));

        assertTrue(error.getMessage().contains("quality gate"));
    }

    @Test
    void learningDiagnosisTurnsProblemsErrorsAndDataQualityIntoActionableSignals() {
        Map<String, Object> metrics = Map.of(
                "problemPerformance", List.of(
                        Map.of(
                                "evidenceId", "M01",
                                "problemNo", "1",
                                "title", "找指定元素的前驱结点",
                                "studentCount", 7,
                                "acceptedCount", 0,
                                "acceptanceRate", 0.0,
                                "averageAttempts", 4.4
                        ),
                        Map.of(
                                "evidenceId", "M02",
                                "problemNo", "0",
                                "title", "PTA Problem 0",
                                "studentCount", 20,
                                "acceptedCount", 0,
                                "acceptanceRate", 0.0,
                                "averageAttempts", 3.0
                        )
                ),
                "problemErrorPoints", List.of(
                        Map.ofEntries(
                                Map.entry("evidenceRefs", List.of("M03")),
                                Map.entry("problemNo", "1"),
                                Map.entry("title", "找指定元素的前驱结点"),
                                Map.entry("affectedStudentCount", 7),
                                Map.entry("dominantStatus", "WRONG_ANSWER"),
                                Map.entry("averageAttempts", 4.4),
                                Map.entry("acceptanceRate", 0.0),
                                Map.entry("inferredKnowledge", "链表前驱定位"),
                                Map.entry("errorPoint", "前驱指针维护和当前结点移动顺序错误"),
                                Map.entry("teachingAdvice", "用题目“找指定元素的前驱结点”讲清“前驱指针维护和当前结点移动顺序错误”，先画状态变化/流程图，再让学生写同类变式题。"),
                                Map.entry("validation", "学生能口头说明关键判断条件，并在同类变式题中正确实现。"),
                                Map.entry("confidence", "HIGH")
                        )
                ),
                "errorStatusSummary", List.of(
                        Map.of("status", "COMPILE_ERROR", "recordCount", 20, "studentCount", 8, "problemCount", 3, "averageAttempts", 3.5),
                        Map.of("status", "WAITING", "recordCount", 5, "studentCount", 5, "problemCount", 1, "averageAttempts", 1.0)
                ),
                "scoreDistribution", Map.of("excellent", 2, "middle", 6, "risk", 3, "highRisk", 1, "incomplete", 1),
                "focusStudents", List.of(Map.of("studentNo", "20230001", "reason", "低分风险"))
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> diagnosis = ReflectionTestUtils.invokeMethod(service, "learningDiagnosis", "EXPERIMENT", metrics);

        assertEquals("MEDIUM", diagnosis.get("reliability"));
        assertTrue(String.valueOf(diagnosis.get("conclusion")).contains("前驱指针维护"));
        assertTrue(String.valueOf(diagnosis.get("nextTeachingAction")).contains("同类变式题"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> problemErrorPoints = (List<Map<String, Object>>) diagnosis.get("problemErrorPoints");
        assertEquals(1, problemErrorPoints.size());
        assertEquals("前驱指针维护和当前结点移动顺序错误", problemErrorPoints.get(0).get("errorPoint"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> weakProblems = (List<Map<String, Object>>) diagnosis.get("weakProblemSignals");
        assertEquals(1, weakProblems.size());
        assertEquals("链表前驱定位", weakProblems.get(0).get("inferredKnowledge"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dataQualityIssues = (List<Map<String, Object>>) diagnosis.get("dataQualityIssues");
        assertTrue(dataQualityIssues.stream().anyMatch(item -> "UNRESOLVED_PROBLEM_TITLE".equals(item.get("type"))));
        assertTrue(dataQualityIssues.stream().anyMatch(item -> "JUDGE_OR_SYNC_PENDING".equals(item.get("type"))));
    }

    @Test
    void teachingContextPackagesPriorityProblemsKnowledgeAndStudentLayersForAi() {
        Map<String, Object> metrics = Map.of(
                "problemErrorPoints", List.of(
                        Map.ofEntries(
                                Map.entry("evidenceRefs", List.of("M03")),
                                Map.entry("problemNo", "1"),
                                Map.entry("title", "找指定元素的前驱结点"),
                                Map.entry("problemStatementSummary", "输入单链表和目标元素，输出其前驱结点。"),
                                Map.entry("affectedStudentCount", 7),
                                Map.entry("dominantStatus", "WRONG_ANSWER"),
                                Map.entry("averageAttempts", 4.4),
                                Map.entry("acceptanceRate", 0.0),
                                Map.entry("inferredKnowledge", "链表前驱定位"),
                                Map.entry("errorPoint", "前驱指针维护和当前结点移动顺序错误"),
                                Map.entry("teachingAdvice", "用图示讲清 pre/cur 移动顺序。"),
                                Map.entry("validation", "同类小题复测。"),
                                Map.entry("confidence", "HIGH")
                        )
                ),
                "scoreDistribution", Map.of("excellent", 2, "middle", 6, "risk", 3, "highRisk", 1, "incomplete", 1, "evidenceId", "M04")
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnosis = ReflectionTestUtils.invokeMethod(service, "learningDiagnosis", "EXPERIMENT", metrics);
        Map<String, Object> enriched = new java.util.LinkedHashMap<>(metrics);
        enriched.put("learningDiagnosis", diagnosis);

        @SuppressWarnings("unchecked")
        Map<String, Object> context = ReflectionTestUtils.invokeMethod(service, "teachingContext", "EXPERIMENT", enriched);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> priorityProblems = (List<Map<String, Object>>) context.get("priorityProblems");
        assertEquals(1, priorityProblems.size());
        assertEquals("输入单链表和目标元素，输出其前驱结点。", priorityProblems.get(0).get("problemStatementSummary"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> priorityKnowledge = (List<Map<String, Object>>) context.get("priorityKnowledgePoints");
        assertTrue(priorityKnowledge.stream().anyMatch(item -> "链表前驱定位".equals(item.get("knowledge"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> layers = (Map<String, Object>) context.get("studentLayerSummary");
        assertEquals(2, layers.get("supportCount"));
        assertEquals("student-layer-analysis", layers.get("jumpTarget"));
    }

    @Test
    void studentFollowUpUsesRiskScoreBeyondIncompleteExperiments() {
        Map<String, Object> repeatedFailures = new java.util.LinkedHashMap<>();
        repeatedFailures.put("studentNo", "20230002");
        repeatedFailures.put("studentName", "李四");
        repeatedFailures.put("score", 72.0);
        repeatedFailures.put("acceptedProblemCount", 2);
        repeatedFailures.put("problemCount", 5);
        repeatedFailures.put("failedProblemCount", 3);
        repeatedFailures.put("averageAttempts", 4.2);
        repeatedFailures.put("reason", "关键题未完全通过");

        ReflectionTestUtils.invokeMethod(service, "enrichStudentFollowUp", repeatedFailures, true);

        assertEquals("REPEATED_FAILED_ATTEMPTS", repeatedFailures.get("followUpType"));
        assertEquals("MEDIUM", repeatedFailures.get("riskLevel"));
        assertEquals("P2", repeatedFailures.get("followUpPriority"));
        assertTrue(String.valueOf(repeatedFailures.get("problem")).contains("反复尝试"));
        assertTrue(String.valueOf(repeatedFailures.get("teacherAction")).contains("失败提交"));

        Map<String, Object> lowScoreButSubmitted = new java.util.LinkedHashMap<>();
        lowScoreButSubmitted.put("studentNo", "20230003");
        lowScoreButSubmitted.put("studentName", "王五");
        lowScoreButSubmitted.put("averageScore", 58.0);
        lowScoreButSubmitted.put("completionRate", 100.0);
        lowScoreButSubmitted.put("experimentCount", 4);
        lowScoreButSubmitted.put("riskExperimentCount", 2);
        lowScoreButSubmitted.put("lowestScore", 45.0);
        lowScoreButSubmitted.put("lowScoreExperimentCount", 2);
        lowScoreButSubmitted.put("failedProblemCount", 0);
        lowScoreButSubmitted.put("averageAttempts", 1.8);
        lowScoreButSubmitted.put("reason", "持续低分风险");

        ReflectionTestUtils.invokeMethod(service, "enrichStudentFollowUp", lowScoreButSubmitted, false);

        assertEquals("LOW_SCORE", lowScoreButSubmitted.get("followUpType"));
        assertEquals("MEDIUM", lowScoreButSubmitted.get("riskLevel"));
        assertEquals("P2", lowScoreButSubmitted.get("followUpPriority"));
        assertTrue(String.valueOf(lowScoreButSubmitted.get("riskSummary")).contains("平均分低于 60"));
        assertTrue(String.valueOf(lowScoreButSubmitted.get("teacherAction")).contains("最低分实验"));

        Map<String, Object> trendDownFromPortraitTable = new java.util.LinkedHashMap<>();
        trendDownFromPortraitTable.put("studentNo", "20230006");
        trendDownFromPortraitTable.put("studentName", "赵六");
        trendDownFromPortraitTable.put("averageScore", 82.0);
        trendDownFromPortraitTable.put("completionRate", 92.0);
        trendDownFromPortraitTable.put("abilityTrend", "down");
        trendDownFromPortraitTable.put("abilityTrendLabel", "下降");
        trendDownFromPortraitTable.put("recentAverageScore", 74.0);
        trendDownFromPortraitTable.put("studentPortraitRiskLevel", "MEDIUM");
        trendDownFromPortraitTable.put("studentPortraitRiskLabel", "中风险");
        trendDownFromPortraitTable.put("studentPortraitSummary", "学生画像表：完成率 92%，均分 82，趋势下降，判定为中风险");
        trendDownFromPortraitTable.put("reason", "表现波动，建议观察");

        ReflectionTestUtils.invokeMethod(service, "enrichStudentFollowUp", trendDownFromPortraitTable, false);

        assertEquals("VOLATILE", trendDownFromPortraitTable.get("followUpType"));
        assertEquals("MEDIUM", trendDownFromPortraitTable.get("riskLevel"));
        assertEquals("P2", trendDownFromPortraitTable.get("followUpPriority"));
        assertTrue(String.valueOf(trendDownFromPortraitTable.get("riskSummary")).contains("学生画像表"));
        assertTrue(String.valueOf(trendDownFromPortraitTable.get("riskSummary")).contains("能力趋势下降"));
    }

    @Test
    void focusStudentSelectionKeepsDifferentRiskTypes() {
        Map<String, Object> incomplete1 = new java.util.LinkedHashMap<>(Map.of(
                "studentNo", "20230001",
                "riskScore", 90,
                "followUpGroup", "SUBMISSION_BLOCKED"
        ));
        Map<String, Object> incomplete2 = new java.util.LinkedHashMap<>(Map.of(
                "studentNo", "20230002",
                "riskScore", 88,
                "followUpGroup", "SUBMISSION_BLOCKED"
        ));
        Map<String, Object> incomplete3 = new java.util.LinkedHashMap<>(Map.of(
                "studentNo", "20230003",
                "riskScore", 86,
                "followUpGroup", "SUBMISSION_BLOCKED"
        ));
        Map<String, Object> repeatedFailure = new java.util.LinkedHashMap<>(Map.of(
                "studentNo", "20230004",
                "riskScore", 55,
                "followUpGroup", "REPEATED_FAILED_ATTEMPTS"
        ));
        Map<String, Object> lowScore = new java.util.LinkedHashMap<>(Map.of(
                "studentNo", "20230005",
                "riskScore", 45,
                "followUpGroup", "LOW_SCORE"
        ));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> selected = ReflectionTestUtils.invokeMethod(
                service,
                "diversifiedFocusStudents",
                List.of(incomplete1, incomplete2, incomplete3, repeatedFailure, lowScore),
                3
        );

        assertEquals(3, selected.size());
        assertTrue(selected.stream().anyMatch(item -> "SUBMISSION_BLOCKED".equals(item.get("followUpGroup"))));
        assertTrue(selected.stream().anyMatch(item -> "REPEATED_FAILED_ATTEMPTS".equals(item.get("followUpGroup"))));
        assertTrue(selected.stream().anyMatch(item -> "LOW_SCORE".equals(item.get("followUpGroup"))));
    }

    @Test
    void directKnowledgeTagsKeepDiagnosisHighConfidence() {
        Map<String, Object> metrics = Map.of(
                "problemErrorPoints", List.of(
                        Map.ofEntries(
                                Map.entry("evidenceRefs", List.of("M03")),
                                Map.entry("problemNo", "1"),
                                Map.entry("title", "找指定元素的前驱结点"),
                                Map.entry("problemStatementSummary", "输入单链表和目标元素，输出其前驱结点。"),
                                Map.entry("affectedStudentCount", 7),
                                Map.entry("dominantStatus", "WRONG_ANSWER"),
                                Map.entry("averageAttempts", 4.4),
                                Map.entry("acceptanceRate", 0.0),
                                Map.entry("inferredKnowledge", "链表前驱定位"),
                                Map.entry("knowledgePath", "数据结构;线性表;链表;前驱定位"),
                                Map.entry("knowledgeSource", "PTA_KNOWLEDGE_LEAF"),
                                Map.entry("errorPoint", "前驱指针维护和当前结点移动顺序错误"),
                                Map.entry("teachingAdvice", "用图示讲清 pre/cur 移动顺序。"),
                                Map.entry("validation", "同类小题复测。"),
                                Map.entry("confidence", "HIGH")
                        )
                )
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> diagnosis = ReflectionTestUtils.invokeMethod(service, "learningDiagnosis", "EXPERIMENT", metrics);

        assertEquals("HIGH", diagnosis.get("reliability"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dataQualityIssues = (List<Map<String, Object>>) diagnosis.get("dataQualityIssues");
        assertTrue(dataQualityIssues.stream().noneMatch(item -> "KNOWLEDGE_TAG_COVERAGE".equals(item.get("type"))));
    }

    @Test
    void sourceHashIsStableForSameSnapshotAndChangesWhenMetricsChange() {
        Map<String, Object> scope = Map.of("level", "CLASS", "classId", 1L, "includeHistory", false);
        Map<String, Object> metrics = Map.of(
                "scoreDistribution", Map.of("total", 30, "highRisk", 3),
                "problemErrorPoints", List.of(Map.of("problemNo", "1", "errorPoint", "链表指针移动顺序错误"))
        );

        String first = ReflectionTestUtils.invokeMethod(service, "sourceHash", "CLASS", scope, metrics);
        String second = ReflectionTestUtils.invokeMethod(service, "sourceHash", "CLASS", scope, metrics);
        String changed = ReflectionTestUtils.invokeMethod(service, "sourceHash", "CLASS", scope,
                Map.of("scoreDistribution", Map.of("total", 30, "highRisk", 4)));

        assertEquals(first, second);
        assertEquals(64, first.length());
        assertTrue(!first.equals(changed));
    }

    @Test
    void repairPromptLimitsOriginalPromptAndInvalidOutput() {
        String originalPrompt = "原始提示".repeat(8000) + "SHOULD_NOT_APPEAR";
        String invalidOutput = "不合格输出".repeat(3000) + "INVALID_TAIL_SHOULD_NOT_APPEAR";

        String repairPrompt = ReflectionTestUtils.invokeMethod(
                service,
                "buildAdviceRepairPrompt",
                originalPrompt,
                invalidOutput,
                "nextTeachingPlan.steps[0].studentTask is incomplete",
                1
        );

        assertTrue(repairPrompt.contains("已截断"));
        assertTrue(repairPrompt.contains("nextTeachingPlan.steps[0].studentTask"));
        assertTrue(!repairPrompt.contains("SHOULD_NOT_APPEAR"));
        assertTrue(!repairPrompt.contains("INVALID_TAIL_SHOULD_NOT_APPEAR"));
        assertTrue(repairPrompt.length() < originalPrompt.length() + invalidOutput.length());
    }
}
