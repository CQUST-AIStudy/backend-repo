package com.tap.backend.service;

import com.tap.backend.domain.document.DocumentEntity;
import com.tap.backend.domain.rag.CourseSpaceDocumentEntity;
import com.tap.backend.domain.rag.CourseSpaceEntity;
import com.tap.backend.domain.rag.DocChunkEntity;
import com.tap.backend.rag.DashScopeEmbeddingClient;
import com.tap.backend.rag.LuceneBm25Service;
import com.tap.backend.rag.MilvusSearchService;
import com.tap.backend.rag.RagProperties;
import com.tap.backend.repo.CourseSpaceDocumentRepository;
import com.tap.backend.repo.CourseSpaceRepository;
import com.tap.backend.repo.DocChunkRepository;
import com.tap.backend.repo.DocumentRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RagDocumentProcessor {

  private static final Logger log = LoggerFactory.getLogger(RagDocumentProcessor.class);
  private static final int PARENT_CHUNK_SIZE = 1500;
  private static final int PARENT_OVERLAP = 100;
  private static final int CHILD_CHUNK_SIZE = 350;
  private static final int CHILD_OVERLAP = 30;
  private static final int MIN_VISIBLE_CHARS = 120;
  private static final int EMBEDDING_BATCH_SIZE = 16;

  private final CourseSpaceDocumentRepository csDocRepo;
  private final DocChunkRepository docChunkRepo;
  private final DocumentRepository documentRepo;
  private final CourseSpaceRepository courseSpaceRepo;
  private final LuceneBm25Service luceneBm25;
  private final DashScopeEmbeddingClient embeddingClient;
  private final MilvusSearchService milvusSearchService;
  private final RagProperties ragProps;

  public RagDocumentProcessor(
      CourseSpaceDocumentRepository csDocRepo,
      DocChunkRepository docChunkRepo,
      DocumentRepository documentRepo,
      CourseSpaceRepository courseSpaceRepo,
      LuceneBm25Service luceneBm25,
      DashScopeEmbeddingClient embeddingClient,
      MilvusSearchService milvusSearchService,
      RagProperties ragProps) {
    this.csDocRepo = csDocRepo;
    this.docChunkRepo = docChunkRepo;
    this.documentRepo = documentRepo;
    this.courseSpaceRepo = courseSpaceRepo;
    this.luceneBm25 = luceneBm25;
    this.embeddingClient = embeddingClient;
    this.milvusSearchService = milvusSearchService;
    this.ragProps = ragProps;
  }

  @Async("fileExecutor")
  @Transactional
  public void processAsync(Long courseSpaceDocId) {
    processInternal(courseSpaceDocId);
  }

  @Transactional
  public void process(Long courseSpaceDocId) {
    processInternal(courseSpaceDocId);
  }

  private void processInternal(Long courseSpaceDocId) {
    CourseSpaceDocumentEntity csDoc = csDocRepo.findById(courseSpaceDocId).orElse(null);
    if (csDoc == null) {
      log.error("[RAG] CourseSpaceDocument {} not found", courseSpaceDocId);
      return;
    }

    csDoc.setStatus("PROCESSING");
    csDoc.setErrorMessage(null);
    csDocRepo.save(csDoc);

    Long courseSpaceId = csDoc.getCourseSpaceId();
    Long documentId = null;
    try {
      DocumentEntity doc = documentRepo.findById(csDoc.getDocumentId()).orElse(null);
      if (doc == null) {
        fail(csDoc, "Document not found");
        return;
      }
      documentId = doc.getId();

      String text = doc.getExtractedText();
      if (text == null || text.isBlank()) {
        fail(csDoc, "No extracted text available");
        return;
      }

      text = normalizeExtractedText(text);
      if (!isUsableExtractedText(text)) {
        fail(
            csDoc,
            "Extracted text quality is too low after built-in PDF parsing and OCR/VLM fallback. "
                + "Please upload a clearer file or verify the scan quality.");
        return;
      }

      CourseSpaceEntity courseSpace = courseSpaceRepo.findById(csDoc.getCourseSpaceId()).orElse(null);
      if (courseSpace == null) {
        fail(csDoc, "Course space not found");
        return;
      }
      courseSpaceId = courseSpace.getId();

      clearExistingChunks(courseSpaceId, documentId);

      PageIndexedText pageIndexedText = buildPageIndexedText(text);
      PageRangeLocator pageRangeLocator = pageIndexedText.newLocator();

      List<DocChunkEntity> allChildren = new ArrayList<>();
      List<String> parentTexts = splitText(pageIndexedText.renderText(), PARENT_CHUNK_SIZE, PARENT_OVERLAP);
      int totalChunks = 0;

      for (int pi = 0; pi < parentTexts.size(); pi++) {
        String parentText = parentTexts.get(pi);
        String chapter = detectChapter(parentText);
        String pageRange = pageRangeLocator.locate(parentText);

        DocChunkEntity parent = new DocChunkEntity();
        parent.setDocument(doc);
        parent.setCourseSpace(courseSpace);
        parent.setChunkType("parent");
        parent.setChunkIndex(pi);
        parent.setContent(parentText);
        parent.setChapterPath(chapter);
        parent.setPageRange(pageRange);
        parent.setTokenCount(parentText.length() / 2);
        parent = docChunkRepo.save(parent);

        List<String> childTexts = splitText(parentText, CHILD_CHUNK_SIZE, CHILD_OVERLAP);
        for (int ci = 0; ci < childTexts.size(); ci++) {
          String childText = childTexts.get(ci);
          DocChunkEntity child = new DocChunkEntity();
          child.setDocument(doc);
          child.setCourseSpace(courseSpace);
          child.setChunkType("child");
          child.setParent(parent);
          child.setChunkIndex(ci);
          child.setContent(childText);
          child.setChapterPath(chapter);
          child.setPageRange(pageRange);
          child.setTokenCount(childText.length() / 2);
          child = docChunkRepo.save(child);
          child.setMilvusId(child.getId());
          allChildren.add(child);
          totalChunks++;
        }
      }

      if (!allChildren.isEmpty()) {
        docChunkRepo.saveAll(allChildren);
        List<Long> childIds = allChildren.stream().map(DocChunkEntity::getId).toList();
        List<DocChunkEntity> reloaded = docChunkRepo.findAllByIdIn(childIds);
        luceneBm25.addChunks(reloaded);
        indexChildChunks(reloaded);
      }

      csDoc.setStatus("READY");
      csDoc.setChunkCount(totalChunks);
      csDoc.setErrorMessage(null);
      csDocRepo.save(csDoc);

      log.info(
          "[RAG] Processed csd={}: {} parents, {} children",
          courseSpaceDocId,
          parentTexts.size(),
          totalChunks);
    } catch (Exception e) {
      log.error("[RAG] Processing failed for csd={}: {}", courseSpaceDocId, e.getMessage(), e);
      cleanupPartialState(courseSpaceId, documentId);
      fail(csDoc, e.getMessage());
    }
  }

  private void indexChildChunks(List<DocChunkEntity> childChunks) {
    if (childChunks == null || childChunks.isEmpty()) {
      return;
    }

    List<List<Float>> vectors = new ArrayList<>(childChunks.size());
    for (int start = 0; start < childChunks.size(); start += EMBEDDING_BATCH_SIZE) {
      int end = Math.min(start + EMBEDDING_BATCH_SIZE, childChunks.size());
      List<String> batchTexts =
          childChunks.subList(start, end).stream().map(DocChunkEntity::getContent).toList();
      List<List<Float>> batchVectors = embeddingClient.embedTexts(batchTexts);
      if (batchVectors.size() != batchTexts.size()) {
        throw new IllegalStateException(
            "Embedding result size mismatch: expected "
                + batchTexts.size()
                + ", actual "
                + batchVectors.size());
      }
      vectors.addAll(batchVectors);
    }

    if (vectors.size() != childChunks.size()) {
      throw new IllegalStateException(
          "Embedding result size mismatch after batching: expected "
              + childChunks.size()
              + ", actual "
              + vectors.size());
    }

    List<MilvusSearchService.ChunkVector> rows = new ArrayList<>(childChunks.size());
    int expectedDim = embeddingDimension();
    for (int i = 0; i < childChunks.size(); i++) {
      DocChunkEntity chunk = childChunks.get(i);
      List<Float> vector = vectors.get(i);
      if (vector == null || vector.isEmpty()) {
        throw new IllegalStateException("Empty embedding vector for chunk " + chunk.getId());
      }
      if (expectedDim > 0 && vector.size() != expectedDim) {
        throw new IllegalStateException(
            "Embedding dimension mismatch for chunk "
                + chunk.getId()
                + ": expected "
                + expectedDim
                + ", actual "
                + vector.size());
      }

      rows.add(
          new MilvusSearchService.ChunkVector(
              chunk.getId(),
              chunk.getParentId() == null ? 0L : chunk.getParentId(),
              resolveCourseSpaceId(chunk),
              resolveDocumentId(chunk),
              chunk.getChapterPath(),
              chunk.getPageRange(),
              vector));
    }

    milvusSearchService.upsertChunks(rows);
  }

  private void fail(CourseSpaceDocumentEntity csDoc, String error) {
    csDoc.setStatus("FAILED");
    csDoc.setErrorMessage(error != null ? error.substring(0, Math.min(error.length(), 500)) : "unknown");
    csDocRepo.save(csDoc);
    log.error("[RAG] FAILED csd={}: {}", csDoc.getId(), error);
  }

  private void clearExistingChunks(Long courseSpaceId, Long documentId) {
    try {
      luceneBm25.deleteByDocument(documentId);
    } catch (Exception e) {
      log.warn("[RAG] failed to clear Lucene chunks for documentId={}: {}", documentId, e.getMessage());
    }

    try {
      milvusSearchService.deleteByDocument(documentId);
    } catch (Exception e) {
      log.warn("[RAG] failed to clear Milvus vectors for documentId={}: {}", documentId, e.getMessage());
    }

    List<DocChunkEntity> children =
        docChunkRepo.findAllByCourseSpaceIdAndDocumentIdAndChunkType(courseSpaceId, documentId, "child");
    if (!children.isEmpty()) {
      docChunkRepo.deleteAllInBatch(children);
    }

    List<DocChunkEntity> parents =
        docChunkRepo.findAllByCourseSpaceIdAndDocumentIdAndChunkType(courseSpaceId, documentId, "parent");
    if (!parents.isEmpty()) {
      docChunkRepo.deleteAllInBatch(parents);
    }

    if (!children.isEmpty() || !parents.isEmpty()) {
      log.info(
          "[RAG] cleared existing chunks for courseSpaceId={}, documentId={}",
          courseSpaceId,
          documentId);
    }
  }

  private void cleanupPartialState(Long courseSpaceId, Long documentId) {
    if (courseSpaceId == null || documentId == null) {
      return;
    }
    try {
      clearExistingChunks(courseSpaceId, documentId);
    } catch (Exception cleanupError) {
      log.warn(
          "[RAG] cleanup after failure also failed for courseSpaceId={}, documentId={}: {}",
          courseSpaceId,
          documentId,
          cleanupError.getMessage());
    }
  }

  private List<String> splitText(String text, int chunkSize, int overlap) {
    List<String> chunks = new ArrayList<>();
    if (text == null || text.isBlank()) {
      return chunks;
    }

    String[] paragraphs = text.split("\n\n+");
    StringBuilder current = new StringBuilder();
    for (String paragraph : paragraphs) {
      String trimmed = paragraph.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      for (String segment : splitLongParagraph(trimmed, chunkSize, overlap)) {
        if (current.length() + segment.length() + 2 > chunkSize && current.length() > 0) {
          chunks.add(current.toString().trim());
          String overlapText = current.substring(Math.max(0, current.length() - overlap)).trim();
          current = new StringBuilder(overlapText);
        }
        if (current.length() > 0) {
          current.append("\n\n");
        }
        current.append(segment);
      }
    }

    if (current.length() > 0) {
      chunks.add(current.toString().trim());
    }

    if (chunks.isEmpty() && text.length() > chunkSize) {
      return splitLongParagraph(text.trim(), chunkSize, overlap);
    } else if (chunks.isEmpty()) {
      chunks.add(text.trim());
    }
    return chunks;
  }

  private List<String> splitLongParagraph(String paragraph, int chunkSize, int overlap) {
    if (paragraph.length() <= chunkSize) {
      return List.of(paragraph);
    }

    List<String> segments = new ArrayList<>();
    int start = 0;
    while (start < paragraph.length()) {
      int tentativeEnd = Math.min(start + chunkSize, paragraph.length());
      int end = chooseSplitPoint(paragraph, start, tentativeEnd);
      String segment = paragraph.substring(start, end).trim();
      if (!segment.isEmpty()) {
        segments.add(segment);
      }
      if (end >= paragraph.length()) {
        break;
      }
      start = Math.max(end - overlap, start + 1);
    }
    return segments;
  }

  private int chooseSplitPoint(String text, int start, int tentativeEnd) {
    if (tentativeEnd >= text.length()) {
      return text.length();
    }
    int lowerBound = Math.max(start + 1, tentativeEnd - 120);
    for (int i = tentativeEnd; i >= lowerBound; i--) {
      char ch = text.charAt(i - 1);
      if (Character.isWhitespace(ch) || "。！？；;,.，、".indexOf(ch) >= 0) {
        return i;
      }
    }
    return tentativeEnd;
  }

  private String detectChapter(String text) {
    String[] lines = text.split("\n", 4);
    for (String line : lines) {
      String s = line.trim();
      if (s.startsWith("#")) {
        return s.replaceFirst("^#+\\s*", "");
      }
      if (s.matches("^第[一二三四五六七八九十百千万0-9]+[章节篇].*")) {
        return s;
      }
      if (s.matches("^\\d+[\\.\\s].*") && s.length() < 80) {
        return s;
      }
      if (s.matches("^Chapter\\s+\\d+.*")) {
        return s;
      }
    }
    return "";
  }

  private String normalizeExtractedText(String text) {
    if (text == null) {
      return "";
    }
    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
    normalized = normalized.replaceAll("[\\x00-\\x08\\x0B\\x0E-\\x1F]", "");
    normalized = normalized.replaceAll("\\n*\\f\\n*", "\n\f\n");
    normalized = normalized.replaceAll("\\n{3,}", "\n\n").trim();
    return normalized;
  }

  private PageIndexedText buildPageIndexedText(String text) {
    if (text == null || !text.contains("\f")) {
      return new PageIndexedText(text == null ? "" : text, List.of());
    }

    String[] rawPages = text.split("\\f", -1);
    StringBuilder rendered = new StringBuilder();
    List<PageBoundary> boundaries = new ArrayList<>();
    for (int i = 0; i < rawPages.length; i++) {
      String pageText = rawPages[i] == null ? "" : rawPages[i].trim();
      int startInclusive = rendered.length();
      rendered.append(pageText);
      int endExclusive = rendered.length();
      boundaries.add(new PageBoundary(i + 1, startInclusive, endExclusive));
      if (i < rawPages.length - 1) {
        rendered.append("\n\n");
      }
    }
    return new PageIndexedText(rendered.toString(), boundaries);
  }

  private boolean isUsableExtractedText(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    String visible = text.replaceAll("\\s+", "");
    if (visible.length() < MIN_VISIBLE_CHARS) {
      return false;
    }

    String[] lines = text.split("\n");
    long nonEmptyLineCount = Arrays.stream(lines).map(String::trim).filter(s -> !s.isEmpty()).count();
    if (nonEmptyLineCount == 0) {
      return false;
    }

    long informativeLineCount = Arrays.stream(lines).map(String::trim).filter(this::isInformativeLine).count();
    long textualCharCount = text.codePoints().filter(this::isTextLikeCodePoint).count();
    double density = visible.isEmpty() ? 0.0 : (double) textualCharCount / visible.length();
    double informativeRatio = (double) informativeLineCount / nonEmptyLineCount;
    return density >= 0.45 && informativeLineCount >= 2 && informativeRatio >= 0.35;
  }

  private boolean isInformativeLine(String line) {
    if (line == null) {
      return false;
    }
    String s = line.trim();
    if (s.length() < 8) {
      return false;
    }
    String lower = s.toLowerCase(Locale.ROOT);
    if ((lower.startsWith("[") && lower.endsWith("]"))
        || (s.contains("=") && s.length() < 40)
        || s.matches("^(封面页|书名页|版权页|前言|目录|目次|索引)$")) {
      return false;
    }
    long textLikeChars = s.codePoints().filter(this::isTextLikeCodePoint).count();
    return textLikeChars >= 8;
  }

  private boolean isTextLikeCodePoint(int cp) {
    Character.UnicodeScript script = Character.UnicodeScript.of(cp);
    return Character.isLetterOrDigit(cp) || script == Character.UnicodeScript.HAN;
  }

  private long resolveCourseSpaceId(DocChunkEntity chunk) {
    if (chunk.getCourseSpaceId() != null) {
      return chunk.getCourseSpaceId();
    }
    if (chunk.getCourseSpace() != null && chunk.getCourseSpace().getId() != null) {
      return chunk.getCourseSpace().getId();
    }
    throw new IllegalStateException("courseSpaceId is null for chunk " + chunk.getId());
  }

  private long resolveDocumentId(DocChunkEntity chunk) {
    if (chunk.getDocumentId() != null) {
      return chunk.getDocumentId();
    }
    if (chunk.getDocument() != null && chunk.getDocument().getId() != null) {
      return chunk.getDocument().getId();
    }
    throw new IllegalStateException("documentId is null for chunk " + chunk.getId());
  }

  private int embeddingDimension() {
    return ragProps.dashscope() != null && ragProps.dashscope().embeddingDimensions() > 0
        ? ragProps.dashscope().embeddingDimensions()
        : 1024;
  }

  private record PageIndexedText(String renderText, List<PageBoundary> boundaries) {
    private PageRangeLocator newLocator() {
      return new PageRangeLocator(renderText, boundaries);
    }
  }

  private record PageBoundary(int pageNo, int startInclusive, int endExclusive) {}

  private static final class PageRangeLocator {
    private final String renderText;
    private final List<PageBoundary> boundaries;
    private int cursor;

    private PageRangeLocator(String renderText, List<PageBoundary> boundaries) {
      this.renderText = renderText == null ? "" : renderText;
      this.boundaries = boundaries == null ? List.of() : boundaries;
      this.cursor = 0;
    }

    private String locate(String chunkText) {
      if (chunkText == null || chunkText.isBlank() || boundaries.isEmpty()) {
        return "";
      }

      int searchFrom = Math.max(0, cursor - 600);
      int found = renderText.indexOf(chunkText, searchFrom);
      if (found < 0) {
        found = renderText.indexOf(chunkText);
      }
      if (found < 0) {
        found = Math.min(cursor, Math.max(0, renderText.length() - 1));
      }

      int startOffset = Math.max(0, Math.min(found, Math.max(0, renderText.length() - 1)));
      int endOffset =
          Math.max(
              startOffset,
              Math.min(found + Math.max(0, chunkText.length() - 1), Math.max(0, renderText.length() - 1)));
      cursor = Math.min(renderText.length(), found + Math.max(1, chunkText.length() - 32));

      int startPage = pageForOffset(startOffset);
      int endPage = pageForOffset(endOffset);
      if (startPage <= 0 || endPage <= 0) {
        return "";
      }
      if (startPage == endPage) {
        return "p" + startPage;
      }
      return "p" + startPage + "-p" + endPage;
    }

    private int pageForOffset(int offset) {
      for (PageBoundary boundary : boundaries) {
        if (offset >= boundary.startInclusive() && offset < boundary.endExclusive()) {
          return boundary.pageNo();
        }
      }
      PageBoundary last = boundaries.get(boundaries.size() - 1);
      return offset >= last.endExclusive() ? last.pageNo() : 0;
    }
  }
}
