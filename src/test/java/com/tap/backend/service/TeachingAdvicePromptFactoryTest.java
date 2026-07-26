package com.tap.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TeachingAdvicePromptFactoryTest {
    private final TeachingAdvicePromptFactory factory = new TeachingAdvicePromptFactory(new ObjectMapper());

    @Test
    void buildsAdviceFirstMarkdownPromptForEachScopeLevel() {
        Map<String, Object> context = Map.of(
                "scope", Map.of("className", "软件工程1班"),
                "metrics", Map.of("evidence", List.of(Map.of("evidenceId", "M01"))));

        String experiment = factory.build("EXPERIMENT", context);
        String classAdvice = factory.build("CLASS", context);
        String course = factory.build("COURSE", context);

        assertEquals("teaching-advice-v5", TeachingAdvicePromptFactory.VERSION);
        assertTrue(experiment.contains("单个实验"));
        assertTrue(classAdvice.contains("阶段性核心教学问题"));
        assertTrue(course.contains("课程层面"));
        assertTrue(experiment.contains("教学决策报告"));
        assertTrue(experiment.contains("metrics.learningDiagnosis"));
        assertTrue(experiment.contains("metrics.teachingContext"));
        assertTrue(experiment.contains("problemErrorPoints"));
        assertTrue(experiment.contains("priorityProblems"));
        assertTrue(experiment.contains("problemStatementSummary"));
        assertTrue(experiment.contains("knowledgeSource"));
        assertTrue(experiment.contains("PTA_KNOWLEDGE_LEAF"));
        assertTrue(experiment.contains("哪道题暴露了什么错误点"));
        assertTrue(experiment.contains("推断知识点"));
        assertTrue(experiment.contains("dataQualityIssues"));
        assertTrue(experiment.contains("不要输出平均分、完成率、人数清单作为主体内容"));
        assertTrue(experiment.contains("\"teachingConclusion\""));
        assertTrue(experiment.contains("\"nextTeachingPlan\""));
        assertTrue(experiment.contains("\"material\""));
        assertTrue(experiment.contains("\"targetStudents\""));
        assertTrue(experiment.contains("\"deliverable\""));
        assertTrue(experiment.contains("\"checkMethod\""));
        assertTrue(experiment.contains("打开哪道题/哪份材料"));
        assertTrue(experiment.contains("学生交什么产物"));
        assertTrue(experiment.contains("\"priorityKnowledgePoints\""));
        assertTrue(experiment.contains("\"studentLayerActions\""));
        assertTrue(experiment.contains("\"teacherFocus\""));
        assertTrue(experiment.contains("\"nextClassPlan\""));
        assertTrue(experiment.contains("\"differentiatedTeaching\""));
        assertTrue(experiment.contains("\"markdown\""));
        assertTrue(experiment.contains("\"quickActions\""));
        assertTrue(experiment.contains("\"focusStudents\""));
        assertTrue(experiment.contains("\"evidenceId\":\"M01\""));
        assertTrue(experiment.contains("仅输出严格 JSON"));
    }

    @Test
    void rejectsUnsupportedScopeLevel() {
        assertThrows(IllegalArgumentException.class, () -> factory.build("STUDENT", Map.of()));
    }
}
