package com.tap.backend.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class DashScopeEmbeddingClientTest {

  @Test
  void shouldUseLangChain4jEmbeddingModelWhenEmbeddingToggleEnabled() {
    RagProperties properties =
        new RagProperties(
            new RagProperties.DashScope("legacy-key", "https://example.com/v1", "text-embedding-v3", 3),
            new RagProperties.Langchain4j(
                false,
                true,
                true,
                new RagProperties.Chat(null, null, null, 120),
                new RagProperties.Embedding(null, null, null, 3, 30)),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    EmbeddingModel embeddingModel =
        segments ->
            Response.from(
                segments.stream()
                    .map(segment -> Embedding.from(List.of(1.0f, 2.0f, (float) segment.text().length())))
                    .toList());

    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    beanFactory.addBean("embeddingModel", embeddingModel);

    DashScopeEmbeddingClient client =
        new DashScopeEmbeddingClient(properties, beanFactory.getBeanProvider(EmbeddingModel.class));

    List<List<Float>> embeddings = client.embedTexts(List.of("abc", "hello"));

    assertEquals(2, embeddings.size());
    assertEquals(List.of(1.0f, 2.0f, 3.0f), embeddings.get(0));
    assertEquals(List.of(1.0f, 2.0f, 5.0f), embeddings.get(1));
  }
}
