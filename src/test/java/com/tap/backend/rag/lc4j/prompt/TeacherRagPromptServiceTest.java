package com.tap.backend.rag.lc4j.prompt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tap.backend.domain.rag.CourseSpaceEntity;
import com.tap.backend.rag.IntentClassifyService;
import com.tap.backend.rag.ModeDecisionService;
import com.tap.backend.rag.lc4j.dto.TeacherRagCitation;
import com.tap.backend.rag.lc4j.dto.TeacherRagExecutionContext;
import com.tap.backend.rag.lc4j.retriever.TeacherHybridRetriever;
import com.tap.backend.service.CourseSpaceService;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeacherRagPromptServiceTest {

  @Test
  void shouldBuildPromptWithStrictModeAndClassScope() {
    TeacherRagPromptService service = new TeacherRagPromptService();
    CourseSpaceEntity courseSpace = new CourseSpaceEntity();
    courseSpace.setRequireCitation(true);
    CourseSpaceService.RagChatScope chatScope =
        new CourseSpaceService.RagChatScope(1L, "一班", List.of(1L));

    TeacherRagExecutionContext context =
        new TeacherRagExecutionContext(
            7L,
            "实验步骤是什么",
            "strict",
            courseSpace,
            chatScope,
            new IntentClassifyService.IntentResult("procedure", false),
            new TeacherHybridRetriever.RetrievalResult(
                List.of("步骤一"),
                List.of(),
                List.of(new TeacherRagCitation(1, "讲义.pdf", "第一章", "1-2", 0.8, "local")),
                List.of(11L),
                0.8,
                false,
                false),
            0.4,
            new ModeDecisionService.ModeDecision("strict", false, "资料覆盖不足"),
            false,
            "ctx",
            null);

    String prompt = service.buildSystemPrompt(context);
    String contextBlock =
        service.buildContextBlock(
            List.of("步骤一"), List.of(new TeacherRagCitation(1, "讲义.pdf", "第一章", "1-2", 0.8, "local")));

    assertTrue(prompt.contains("严格模式下"));
    assertTrue(prompt.contains("当前班级 ID：1"));
    assertTrue(prompt.contains("资料覆盖不足"));
    assertTrue(contextBlock.contains("[1] (讲义.pdf / 第一章) 步骤一"));
  }
}
