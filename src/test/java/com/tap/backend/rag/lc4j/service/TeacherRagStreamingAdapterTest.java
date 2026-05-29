package com.tap.backend.rag.lc4j.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TeacherRagStreamingAdapterTest {

  @Test
  void shouldWritePartialResponsesToOutputStream() throws Exception {
    TeacherRagStreamingAdapter adapter = new TeacherRagStreamingAdapter();
    StreamingChatModel streamingChatModel =
        new StreamingChatModel() {
          @Override
          public void doChat(
              ChatRequest chatRequest, StreamingChatResponseHandler streamingChatResponseHandler) {
            streamingChatResponseHandler.onPartialResponse("课程");
            streamingChatResponseHandler.onPartialResponse("资料");
            streamingChatResponseHandler.onCompleteResponse(null);
          }
        };

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    String fullAnswer =
        adapter.stream(
            streamingChatModel,
            ChatRequest.builder().messages(UserMessage.from("test")).build(),
            outputStream,
            Duration.ofSeconds(5));

    assertEquals("课程资料", fullAnswer);
    assertEquals("课程资料", outputStream.toString(StandardCharsets.UTF_8));
  }
}
