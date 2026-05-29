package com.tap.backend.rag.lc4j.retriever;

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
import com.tap.backend.rag.lc4j.dto.TeacherRagCitation;
import com.tap.backend.repo.CourseSpaceDocumentRepository;
import com.tap.backend.repo.DocChunkRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TeacherHybridRetriever {

  private static final Logger log = LoggerFactory.getLogger(TeacherHybridRetriever.class);

  private final RagRetrievalService retrievalService;
  private final LuceneBm25Service bm25Service;
  private final FusionRankService fusionRankService;
  private final TopRerankService topRerankService;
  private final EvidenceCompressService evidenceCompressService;
  private final DocChunkAnnotationService annotationService;
  private final MilvusSearchService milvusSearchService;
  private final DashScopeEmbeddingClient embeddingClient;
  private final CourseSpaceDocumentRepository courseSpaceDocumentRepo;
  private final DocChunkRepository docChunkRepo;
  private final RagProperties ragProperties;

  public TeacherHybridRetriever(
      RagRetrievalService retrievalService,
      LuceneBm25Service bm25Service,
      FusionRankService fusionRankService,
      TopRerankService topRerankService,
      EvidenceCompressService evidenceCompressService,
      DocChunkAnnotationService annotationService,
      MilvusSearchService milvusSearchService,
      DashScopeEmbeddingClient embeddingClient,
      CourseSpaceDocumentRepository courseSpaceDocumentRepo,
      DocChunkRepository docChunkRepo,
      RagProperties ragProperties) {
    this.retrievalService = retrievalService;
    this.bm25Service = bm25Service;
    this.fusionRankService = fusionRankService;
    this.topRerankService = topRerankService;
    this.evidenceCompressService = evidenceCompressService;
    this.annotationService = annotationService;
    this.milvusSearchService = milvusSearchService;
    this.embeddingClient = embeddingClient;
    this.courseSpaceDocumentRepo = courseSpaceDocumentRepo;
    this.docChunkRepo = docChunkRepo;
    this.ragProperties = ragProperties;
  }

  public RetrievalResult retrieve(long courseSpaceId, String query) {
    RagRetrievalService.RetrievedContext vecContext = retrieveVectorContext(courseSpaceId, query);
    List<LuceneBm25Service.Bm25Hit> bm25Hits = searchBm25(courseSpaceId, query);
    List<MilvusSearchService.SearchHit> vecHits = searchMilvus(courseSpaceId, query);
    Map<Long, String> chunkAnnotations = loadAnnotations(courseSpaceId);
    Map<Long, String> docTypeMap = loadDocTypeMap(courseSpaceId);

    int topN = ragProperties.rerank() != null ? ragProperties.rerank().topN() : 5;
    List<FusionRankService.RankedParent> ranked =
        fusionRankService.rank(vecHits, bm25Hits, chunkAnnotations, docTypeMap, topN);

    List<Long> parentIds =
        ranked.stream().map(FusionRankService.RankedParent::parentId).toList();
    List<DocChunkEntity> parentChunks =
        parentIds.isEmpty() ? Collections.emptyList() : docChunkRepo.findAllByIdIn(parentIds);
    Map<Long, DocChunkEntity> parentChunkMap =
        parentChunks.stream()
            .collect(
                Collectors.toMap(
                    DocChunkEntity::getId, chunk -> chunk, (a, b) -> a, LinkedHashMap::new));

    List<FusionRankService.RankedParent> reranked =
        topRerankService.rerank(query, ranked, parentChunkMap, chunkAnnotations);

    return assembleResult(reranked, parentChunkMap, vecContext, query, chunkAnnotations);
  }

  private RagRetrievalService.RetrievedContext retrieveVectorContext(long courseSpaceId, String query) {
    try {
      return retrievalService.retrieve(courseSpaceId, query);
    } catch (Exception e) {
      log.error("[RAG] vector retrieval failed: {}", e.getMessage(), e);
      return new RagRetrievalService.RetrievedContext(List.of(), 0.0);
    }
  }

  private List<LuceneBm25Service.Bm25Hit> searchBm25(long courseSpaceId, String query) {
    try {
      return bm25Service.search(courseSpaceId, query, ragProperties.retrieval().topK());
    } catch (Exception e) {
      log.warn("[RAG] BM25 search failed: {}", e.getMessage());
      return Collections.emptyList();
    }
  }

  private List<MilvusSearchService.SearchHit> searchMilvus(long courseSpaceId, String query) {
    try {
      List<Float> queryVec = embeddingClient.embedQuery(query);
      return milvusSearchService.search(courseSpaceId, queryVec, ragProperties.retrieval().topK());
    } catch (Exception e) {
      log.warn("[RAG] Milvus search for fusion failed: {}", e.getMessage());
      return Collections.emptyList();
    }
  }

  private Map<Long, String> loadAnnotations(long courseSpaceId) {
    try {
      return annotationService.listByCourseSpace(courseSpaceId).stream()
          .collect(
              Collectors.toMap(
                  annotation -> annotation.getChunkId(),
                  annotation -> annotation.getNote() != null ? annotation.getNote() : "",
                  (a, b) -> a));
    } catch (Exception e) {
      log.warn("[RAG] annotation load failed: {}", e.getMessage());
      return Collections.emptyMap();
    }
  }

  private Map<Long, String> loadDocTypeMap(long courseSpaceId) {
    try {
      return courseSpaceDocumentRepo.findAllByCourseSpaceId(courseSpaceId).stream()
          .collect(
              Collectors.toMap(
                  CourseSpaceDocumentEntity::getDocumentId,
                  doc -> normalizeDocType(doc.getDocType()),
                  (a, b) -> a));
    } catch (Exception e) {
      log.warn("[RAG] doc type load failed: {}", e.getMessage());
      return Collections.emptyMap();
    }
  }

  private RetrievalResult assembleResult(
      List<FusionRankService.RankedParent> reranked,
      Map<Long, DocChunkEntity> parentChunkMap,
      RagRetrievalService.RetrievedContext vecContext,
      String query,
      Map<Long, String> chunkAnnotations) {
    List<String> evidenceTexts = new ArrayList<>();
    List<RetrievedParent> parents = new ArrayList<>();
    List<TeacherRagCitation> citations = new ArrayList<>();
    List<Long> retrievedChunkIds = new ArrayList<>();
    boolean hitFaq = false;
    boolean hitAnnotation = false;

    for (int i = 0; i < reranked.size(); i++) {
      FusionRankService.RankedParent rankedParent = reranked.get(i);
      DocChunkEntity chunk = parentChunkMap.get(rankedParent.parentId());
      if (chunk == null) {
        continue;
      }

      retrievedChunkIds.add(rankedParent.parentId());
      String content = chunk.getContent() != null ? chunk.getContent() : "";
      EvidenceCompressService.CompressedEvidence compressed =
          evidenceCompressService.compress(
              content,
              query,
              rankedParent.parentId(),
              rankedParent.chapterPath(),
              rankedParent.pageRange());

      String evidenceText =
          compressed.sentences().stream()
              .map(EvidenceCompressService.ScoredSentence::text)
              .collect(Collectors.joining("。"));
      if (!evidenceText.isBlank()) {
        evidenceTexts.add(evidenceText);
      }

      String docName = findDocName(vecContext, rankedParent.parentId());
      citations.add(
          new TeacherRagCitation(
              i + 1,
              docName,
              rankedParent.chapterPath(),
              rankedParent.pageRange() != null ? rankedParent.pageRange() : "",
              rankedParent.finalScore(),
              "local"));
      parents.add(
          new RetrievedParent(
              rankedParent.parentId(),
              rankedParent.docId(),
              docName,
              content,
              rankedParent.chapterPath(),
              rankedParent.pageRange(),
              rankedParent.finalScore(),
              rankedParent.docType()));

      if ("faq".equals(rankedParent.docType())) {
        hitFaq = true;
      }
      if (chunkAnnotations.containsKey(rankedParent.parentId())) {
        hitAnnotation = true;
      }
    }

    return new RetrievalResult(
        evidenceTexts, parents, citations, retrievedChunkIds, vecContext.top1Score(), hitFaq, hitAnnotation);
  }

  private String findDocName(RagRetrievalService.RetrievedContext vecContext, long parentId) {
    for (RagRetrievalService.ParentEvidence parent : vecContext.parents()) {
      if (parent.parentId() == parentId) {
        return parent.docName();
      }
    }
    return "未知文档";
  }

  private String normalizeDocType(String docType) {
    if (docType == null || docType.isBlank()) {
      return "other";
    }
    String normalized = docType.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    return switch (normalized) {
      case "faq", "textbook", "ppt", "other" -> normalized;
      case "labguide", "lab_guide", "guide", "manual", "experiment", "experiment_guide" -> "lab_guide";
      case "slide", "slides", "pptx" -> "ppt";
      default -> "other";
    };
  }

  public record RetrievedParent(
      long parentId,
      long docId,
      String docName,
      String content,
      String chapterPath,
      String pageRange,
      double score,
      String docType) {}

  public record RetrievalResult(
      List<String> evidenceTexts,
      List<RetrievedParent> parents,
      List<TeacherRagCitation> citations,
      List<Long> retrievedChunkIds,
      double top1Score,
      boolean hitFaq,
      boolean hitAnnotation) {}
}
