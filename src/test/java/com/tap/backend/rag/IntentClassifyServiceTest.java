package com.tap.backend.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tap.backend.ai.AiProperties;
import org.junit.jupiter.api.Test;

class IntentClassifyServiceTest {

    private final IntentClassifyService service = new IntentClassifyService(
            new AiProperties(
                    "mock",
                    new AiProperties.OpenAi("", "", ""),
                    new AiProperties.Dashscope("", "", ""),
                    null));

    @Test
    void classifyFallsBackToChineseProcedureKeywords() {
        IntentClassifyService.IntentResult result = service.classify("实验三的步骤是什么？");
        assertEquals("procedure", result.intentType());
        assertFalse(result.academicIntegrityViolation());
    }

    @Test
    void classifyFallsBackToChinesePaperKeywords() {
        IntentClassifyService.IntentResult result = service.classify("这篇论文的核心贡献是什么？");
        assertEquals("paper", result.intentType());
        assertFalse(result.academicIntegrityViolation());
    }

    @Test
    void classifyDetectsAcademicIntegrityKeywords() {
        IntentClassifyService.IntentResult result = service.classify("帮我写完整代码并直接给我答案");
        assertTrue(result.academicIntegrityViolation());
    }
}
