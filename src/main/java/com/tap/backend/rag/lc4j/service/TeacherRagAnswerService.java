package com.tap.backend.rag.lc4j.service;

import com.tap.backend.rag.RagProperties;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class TeacherRagAnswerService {

  private final ObjectProvider<StreamingChatModel> streamingChatModelProvider;
  private final TeacherRagStreamingAdapter streamingAdapter;
  private final RagProperties ragProperties;

  public TeacherRagAnswerService(
      ObjectProvider<StreamingChatModel> streamingChatModelProvider,
      TeacherRagStreamingAdapter streamingAdapter,
      RagProperties ragProperties) {
    this.streamingChatModelProvider = streamingChatModelProvider;
    this.streamingAdapter = streamingAdapter;
    this.ragProperties = ragProperties;
  }

  public boolean isEnabled() {
    return ragProperties.langchain4j() != null
        && ragProperties.langchain4j().enabled()
        && ragProperties.langchain4j().useStreaming();
  }

  public String streamAnswer(
      String systemPrompt, String contextBlock, String query, OutputStream outputStream)
      throws IOException {
    StreamingChatModel streamingChatModel = streamingChatModelProvider.getIfAvailable();
    if (streamingChatModel == null) {
      throw new IllegalStateException("Teacher rag streaming chat model is not configured");
    }

    ChatRequest chatRequest =
        ChatRequest.builder()
            .messages(
                SystemMessage.from(systemPrompt),
                UserMessage.from("以下是检索到的课程资料：\n\n" + contextBlock + "\n\n学生问题：\n" + query))
            .build();

    int timeoutSeconds =
        ragProperties.langchain4j() == null || ragProperties.langchain4j().chat() == null
            ? 120
            : ragProperties.langchain4j().chat().timeoutSeconds();
    return streamingAdapter.stream(
        streamingChatModel, chatRequest, outputStream, Duration.ofSeconds(timeoutSeconds + 30L));
  }
}
