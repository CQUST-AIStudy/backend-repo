package com.tap.backend.rag.lc4j.prompt;

import com.tap.backend.rag.lc4j.dto.TeacherRagCitation;
import com.tap.backend.rag.lc4j.dto.TeacherRagExecutionContext;
import com.tap.backend.service.CourseSpaceService;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TeacherRagPromptService {

  private static final String NO_CONTEXT_MESSAGE = "（未检索到相关课程资料）";

  public String buildContextBlock(List<String> evidenceTexts, List<TeacherRagCitation> citations) {
    if (evidenceTexts == null || evidenceTexts.isEmpty()) {
      return NO_CONTEXT_MESSAGE;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < evidenceTexts.size(); i++) {
      sb.append("[").append(i + 1).append("] ");
      if (citations != null && i < citations.size()) {
        TeacherRagCitation citation = citations.get(i);
        if (citation.docName() != null && !citation.docName().isBlank()) {
          sb.append("(").append(citation.docName());
          if (citation.chapterPath() != null && !citation.chapterPath().isBlank()) {
            sb.append(" / ").append(citation.chapterPath());
          }
          sb.append(") ");
        }
      }
      sb.append(evidenceTexts.get(i)).append("\n\n");
    }
    return sb.toString();
  }

  public String buildSystemPrompt(TeacherRagExecutionContext context) {
    String intentType = context.intent().intentType();
    String effectiveMode = context.modeDecision().effectiveMode();
    CourseSpaceService.RagChatScope chatScope = context.chatScope();
    String lowCoverageMessage = context.modeDecision().lowCoverageMessage();
    boolean requireCitation = Boolean.TRUE.equals(context.courseSpace().getRequireCitation());

    StringBuilder sb = new StringBuilder();
    sb.append("你是课程问答助手，只能基于提供的课程资料和明确标记的外部检索结果回答。\n\n");
    sb.append("## 回答规则\n");
    sb.append("- 使用 Markdown 输出\n");
    sb.append("- 语言准确、简洁、便于学生理解\n");

    if (requireCitation) {
      sb.append("- 引用资料时使用 [1]、[2] 这类标注\n");
    }

    if ("strict".equals(effectiveMode)) {
      sb.append("- 严格模式下，只能依据课程资料作答，不要补充未提供的外部知识\n");
      sb.append("- 如果资料不足，请明确说明资料不足\n");
    } else {
      sb.append("- 开放模式下，优先使用课程资料，其次再补充通用知识或明确标记的网页结果\n");
    }

    switch (intentType) {
      case "debug" -> sb.append("- 问题偏向调试，请优先分析可能原因、定位步骤和修复建议\n");
      case "procedure" -> sb.append("- 问题偏向操作步骤，请给出清晰的分步说明\n");
      case "summary" -> sb.append("- 问题偏向总结，请先概括，再分点梳理\n");
      case "paper" -> sb.append("- 问题偏向论文阅读，请关注研究目标、方法和结论\n");
      default -> sb.append("- 问题偏向概念理解，请用通俗语言解释关键概念\n");
    }

    if (chatScope != null && chatScope.activeClassId() != null) {
      sb.append("\n## 班级作用域\n");
      sb.append("- 当前班级 ID：").append(chatScope.activeClassId()).append("\n");
      if (chatScope.activeClassName() != null && !chatScope.activeClassName().isBlank()) {
        sb.append("- 当前班级名称：").append(chatScope.activeClassName()).append("\n");
      }
      sb.append("- 回答时仅能围绕当前课程空间与该班级相关的资料作答，不要混入其他班级的信息\n");
    } else if (chatScope != null && chatScope.boundClassIds() != null && !chatScope.boundClassIds().isEmpty()) {
      sb.append("\n## 班级作用域\n");
      sb.append("- 当前课程空间绑定班级：").append(chatScope.boundClassIds()).append("\n");
      sb.append("- 回答时只能基于当前课程空间和以上绑定班级范围内的资料作答\n");
    }

    if (lowCoverageMessage != null && !lowCoverageMessage.isBlank()) {
      sb.append("\n注意：").append(lowCoverageMessage).append("\n");
    }
    return sb.toString();
  }
}
