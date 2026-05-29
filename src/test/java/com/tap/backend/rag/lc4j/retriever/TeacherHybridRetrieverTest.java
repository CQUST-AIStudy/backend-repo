package com.tap.backend.rag.lc4j.retriever;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tap.backend.domain.rag.CourseSpaceDocumentEntity;
import com.tap.backend.domain.rag.DocChunkEntity;
import com.tap.backend.rag.DashScopeEmbeddingClient;
import com.tap.backend.rag.DocChunkAnnotationService;
import com.tap.backend.rag.EvidenceCompressService;
import com.tap.backend.rag.FusionRankService;
import com.tap.backend.rag.LuceneBm25Service;
import com.tap.backend.rag.MilvusSearchService;
import com.tap.backend.rag.RagProperties;
import com.tap.backend.rag.RagRetrievalService;
import com.tap.backend.rag.TopRerankService;
import com.tap.backend.repo.CourseSpaceDocumentRepository;
import com.tap.backend.repo.DocChunkRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeacherHybridRetrieverTest {

  @Test
  void shouldAssembleHybridRetrievalResult() {
    RagRetrievalService retrievalService = mock(RagRetrievalService.class);
    LuceneBm25Service bm25Service = mock(LuceneBm25Service.class);
    FusionRankService fusionRankService = mock(FusionRankService.class);
    TopRerankService topRerankService = mock(TopRerankService.class);
    EvidenceCompressService evidenceCompressService = mock(EvidenceCompressService.class);
    DocChunkAnnotationService annotationService = mock(DocChunkAnnotationService.class);
    MilvusSearchService milvusSearchService = mock(MilvusSearchService.class);
    DashScopeEmbeddingClient embeddingClient = mock(DashScopeEmbeddingClient.class);
    CourseSpaceDocumentRepository courseSpaceDocumentRepo = mock(CourseSpaceDocumentRepository.class);
    DocChunkRepository docChunkRepo = mock(DocChunkRepository.class);

    RagProperties ragProperties =
        new RagProperties(
            new RagProperties.DashScope("key", "https://example.com/v1", "text-embedding-v3", 1024),
            new RagProperties.Langchain4j(
                false,
                true,
                false,
                new RagProperties.Chat(null, null, null, 120),
                new RagProperties.Embedding(null, null, null, 0, 30)),
            new RagProperties.Milvus("127.0.0.1", 19530, "course_chunks"),
            new RagProperties.Retrieval(5, 5, 0.0),
            new RagProperties.Fusion(0.5, 0.3, 0.1, 0.1),
            new RagProperties.Rerank(true, "heuristic", null, 0, 0, 1, 0, 0, 0, 0),
            new RagProperties.Mmr(0.7),
            new RagProperties.Coverage(0.3),
            new RagProperties.Evidence(8, 0.25, 0.4),
            new RagProperties.Web(null, false),
            new RagProperties.Lucene("./data/lucene-index"));

    TeacherHybridRetriever retriever =
        new TeacherHybridRetriever(
            retrievalService,
            bm25Service,
            fusionRankService,
            topRerankService,
            evidenceCompressService,
            annotationService,
            milvusSearchService,
            embeddingClient,
            courseSpaceDocumentRepo,
            docChunkRepo,
            ragProperties);

    long courseSpaceId = 7L;
    long parentId = 11L;
    long chunkId = 21L;
    long docId = 101L;
    String query = "实验步骤";

    when(retrievalService.retrieve(courseSpaceId, query))
        .thenReturn(
            new RagRetrievalService.RetrievedContext(
                List.of(
                    new RagRetrievalService.ParentEvidence(
                        parentId, "父块内容", "第一章", "1-2", "课程讲义.pdf", 0.91)),
                0.91));
    when(bm25Service.search(courseSpaceId, query, 5))
        .thenReturn(
            List.of(
                new LuceneBm25Service.Bm25Hit(
                    chunkId, parentId, courseSpaceId, docId, "第一章", "1-2", 0.72f)));
    when(embeddingClient.embedQuery(query)).thenReturn(List.of(0.1f, 0.2f));
    when(milvusSearchService.search(courseSpaceId, List.of(0.1f, 0.2f), 5))
        .thenReturn(
            List.of(
                new MilvusSearchService.SearchHit(
                    chunkId, parentId, courseSpaceId, docId, "第一章", "1-2", 0.91f)));
    when(annotationService.listByCourseSpace(courseSpaceId)).thenReturn(List.of());

    CourseSpaceDocumentEntity courseSpaceDocument = mock(CourseSpaceDocumentEntity.class);
    when(courseSpaceDocument.getDocumentId()).thenReturn(docId);
    when(courseSpaceDocument.getDocType()).thenReturn("faq");
    when(courseSpaceDocumentRepo.findAllByCourseSpaceId(courseSpaceId))
        .thenReturn(List.of(courseSpaceDocument));

    FusionRankService.RankedParent rankedParent =
        new FusionRankService.RankedParent(parentId, docId, 0.88, "第一章", "1-2", "faq");
    when(fusionRankService.rank(
            List.of(new MilvusSearchService.SearchHit(chunkId, parentId, courseSpaceId, docId, "第一章", "1-2", 0.91f)),
            List.of(new LuceneBm25Service.Bm25Hit(chunkId, parentId, courseSpaceId, docId, "第一章", "1-2", 0.72f)),
            java.util.Collections.emptyMap(),
            java.util.Map.of(docId, "faq"),
            1))
        .thenReturn(List.of(rankedParent));

    DocChunkEntity chunk = mock(DocChunkEntity.class);
    when(chunk.getId()).thenReturn(parentId);
    when(chunk.getContent()).thenReturn("步骤一。步骤二。");
    when(docChunkRepo.findAllByIdIn(List.of(parentId))).thenReturn(List.of(chunk));
    when(topRerankService.rerank(query, List.of(rankedParent), java.util.Map.of(parentId, chunk), java.util.Collections.emptyMap()))
        .thenReturn(List.of(rankedParent));
    when(evidenceCompressService.compress("步骤一。步骤二。", query, parentId, "第一章", "1-2"))
        .thenReturn(
            new EvidenceCompressService.CompressedEvidence(
                List.of(
                    new EvidenceCompressService.ScoredSentence(
                        "步骤一", 0.9, 3, parentId, "第一章", "1-2")),
                3));

    TeacherHybridRetriever.RetrievalResult result = retriever.retrieve(courseSpaceId, query);

    assertEquals(0.91, result.top1Score());
    assertEquals(List.of(parentId), result.retrievedChunkIds());
    assertEquals(List.of("步骤一"), result.evidenceTexts());
    assertEquals(1, result.parents().size());
    assertEquals("课程讲义.pdf", result.parents().get(0).docName());
    assertEquals(1, result.citations().size());
    assertEquals("课程讲义.pdf", result.citations().get(0).docName());
    assertTrue(result.hitFaq());
    assertFalse(result.hitAnnotation());
  }
}
