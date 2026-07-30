package com.tap.backend.service.grading.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 第一层 guardrail：没有学生真实代码时，绝不编造无关代码演示。 */
class ConceptStepsWorkflowTest {

    // aiClient 传 null：无代码分支在调用 aiClient 之前就返回，不会触达它。
    private final ConceptStepsWorkflow workflow = new ConceptStepsWorkflow(null, new ObjectMapper());

    @Test
    void noStudentCodeProducesTextOnlyConceptWithoutFabricatedCode() {
        AnimationCandidate.AnnotationInfo ann = new AnimationCandidate.AnnotationInfo(
                "CONCEPT", "ev-1", "arr[i]", "这里存在数组越界风险", false);
        // codeContext = null → 无学生真实代码
        AnimationCandidate candidate = new AnimationCandidate(null, ann, null, null, null, null);

        AnimationResult result = workflow.generate(candidate, 1);

        assertEquals(AnimationWorkflow.CONCEPT_STEPS.name(), result.workflow());
        assertEquals(1, result.frames().size());
        @SuppressWarnings("unchecked")
        Map<String, Object> frame0 = (Map<String, Object>) result.frames().get(0);
        assertEquals("这里存在数组越界风险", frame0.get("explanation"));
        assertEquals("code", result.metadata().get("dataStructure"));
        // 关键：无学生代码时不得编造 sourceCode / 节点
        assertFalse(result.metadata().containsKey("sourceCode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) frame0.get("state");
        assertTrue(((List<?>) state.get("nodes")).isEmpty());
    }
}
