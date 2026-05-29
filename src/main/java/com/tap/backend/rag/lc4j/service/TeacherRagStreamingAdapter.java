package com.tap.backend.rag.lc4j.service;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TeacherRagStreamingAdapter {

  private static final Logger log = LoggerFactory.getLogger(TeacherRagStreamingAdapter.class);

  public String stream(
      StreamingChatModel streamingChatModel,
      ChatRequest chatRequest,
      OutputStream outputStream,
      Duration timeout)
      throws IOException {
    StringBuilder fullAnswer = new StringBuilder();
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> errorRef = new AtomicReference<>();

    streamingChatModel.chat(
        chatRequest,
        new StreamingChatResponseHandler() {
          @Override
          public void onPartialResponse(String partialResponse) {
            if (partialResponse == null || partialResponse.isEmpty()) {
              return;
            }
            try {
              outputStream.write(partialResponse.getBytes(StandardCharsets.UTF_8));
              outputStream.flush();
              fullAnswer.append(partialResponse);
            } catch (IOException e) {
              errorRef.compareAndSet(null, e);
            }
          }

          @Override
          public void onCompleteResponse(ChatResponse chatResponse) {
            done.countDown();
          }

          @Override
          public void onError(Throwable error) {
            errorRef.compareAndSet(null, error);
            done.countDown();
          }
        });

    try {
      long timeoutSeconds = timeout == null ? 150 : Math.max(10, timeout.getSeconds());
      if (!done.await(timeoutSeconds, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Teacher rag streaming timed out");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Teacher rag streaming interrupted", e);
    }

    Throwable error = errorRef.get();
    if (error != null) {
      log.warn("[RAG] LangChain4j streaming failed: {}", error.getMessage());
      if (error instanceof IOException ioException) {
        throw ioException;
      }
      throw new IllegalStateException("Teacher rag streaming failed", error);
    }

    return fullAnswer.toString();
  }
}
