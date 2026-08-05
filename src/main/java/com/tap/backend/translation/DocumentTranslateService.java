package com.tap.backend.translation;

import com.tap.backend.domain.document.DocumentEntity;
import com.tap.backend.domain.translation.TranslationSegmentEntity;
import com.tap.backend.infra.storage.ObjectStorageService;
import com.tap.backend.infra.text.FileTextExtractor;
import com.tap.backend.quota.QuotaService;
import com.tap.backend.repo.DocumentRepository;
import com.tap.backend.repo.TranslationSegmentRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentTranslateService {
  private final DocumentRepository documentRepository;
  private final TranslationSegmentRepository segmentRepository;
  private final ObjectStorageService objectStorageService;
  private final FileTextExtractor fileTextExtractor;
  private final DeepLClient deepLClient;
  private final StringRedisTemplate redis;
  private final TranslationProperties props;
  private final QuotaService quotaService;

  public DocumentTranslateService(DocumentRepository documentRepository,
      TranslationSegmentRepository segmentRepository,
      ObjectStorageService objectStorageService,
      FileTextExtractor fileTextExtractor,
      DeepLClient deepLClient,
      StringRedisTemplate redis,
      TranslationProperties props,
      QuotaService quotaService) {
    this.documentRepository = documentRepository;
    this.segmentRepository = segmentRepository;
    this.objectStorageService = objectStorageService;
    this.fileTextExtractor = fileTextExtractor;
    this.deepLClient = deepLClient;
    this.redis = redis;
    this.props = props;
    this.quotaService = quotaService;
  }

  public record SegmentDto(int index, String source, String target) {}

  @Transactional
  public TranslateResult translateTextMode(long userId, long docId, String targetLang, boolean force) {
    if (!deepLClient.isEnabled()) {
      throw new IllegalStateException("翻译功能未启用");
    }
    DocumentEntity doc = documentRepository.findByIdAndUser_Id(docId, userId)
        .orElseThrow(() -> new IllegalArgumentException("document not found"));
    String tl = normalizeLang(targetLang);
    if (tl.isBlank()) throw new IllegalArgumentException("targetLang required");

    String cacheKey = "tap:docTr:ready:v1:" + docId + ":" + tl + ":" + deepLClient.name();
    if (!force && Boolean.TRUE.equals(redis.hasKey(cacheKey))) {
      return loadFromDb(doc, tl);
    }

    long existing = segmentRepository.countByDocument_IdAndTargetLang(docId, tl);
    if (!force && existing > 0) {
      setReady(cacheKey);
      return loadFromDb(doc, tl);
    }

    if (force) {
      segmentRepository.deleteAllByDocument_IdAndTargetLang(docId, tl);
    }

    String text = loadDocumentText(doc);
    List<String> paragraphs = ParagraphSplitter.split(text);
    if (paragraphs.isEmpty()) throw new IllegalArgumentException(
        "document has no text to translate (extractedText is empty, file type: " + doc.getContentType() + ")");

    long chars = paragraphs.stream().mapToLong(p -> p == null ? 0 : p.length()).sum();
    quotaService.consumeTranslationChars(userId, chars);

    List<String> translated = deepLClient.translateText(paragraphs, tl);
    for (int i = 0; i < paragraphs.size(); i++) {
      TranslationSegmentEntity seg = new TranslationSegmentEntity();
      seg.setDocument(doc);
      seg.setTargetLang(tl);
      seg.setSegmentIndex(i);
      seg.setSourceText(paragraphs.get(i));
      seg.setTargetText(translated.get(i));
      seg.setProvider(deepLClient.name());
      segmentRepository.save(seg);
    }

    setReady(cacheKey);
    return loadFromDb(doc, tl);
  }

  public record TranslateResult(long documentId, String path, String targetLang, String provider, List<SegmentDto> segments) {}

  private TranslateResult loadFromDb(DocumentEntity doc, String targetLang) {
    List<TranslationSegmentEntity> segs =
        segmentRepository.findAllByDocument_IdAndTargetLangOrderBySegmentIndexAsc(doc.getId(), targetLang);
    List<SegmentDto> out = new ArrayList<>(segs.size());
    for (TranslationSegmentEntity s : segs) {
      out.add(new SegmentDto(s.getSegmentIndex(), s.getSourceText(), s.getTargetText()));
    }
    return new TranslateResult(doc.getId(), doc.getOriginalPath(), targetLang, deepLClient.name(), out);
  }

  private String loadDocumentText(DocumentEntity doc) {
    // 1) Try full text stored in MinIO
    if (doc.getExtractedTextKey() != null && !doc.getExtractedTextKey().isBlank()) {
      try {
        String text = new String(objectStorageService.getBytes(doc.getExtractedTextKey()), StandardCharsets.UTF_8);
        if (text != null && !text.isBlank()) return text;
      } catch (Exception ignored) {
        // fallback
      }
    }
    // 2) Try extractedText column
    if (doc.getExtractedText() != null && !doc.getExtractedText().isBlank()) {
      return doc.getExtractedText();
    }
    // 3) Re-extract from original file in MinIO
    byte[] bytes = objectStorageService.getBytes(doc.getObjectKey());
    String full = fileTextExtractor.extract(doc.getFilename(), doc.getContentType(), bytes);
    if (full != null && !full.isBlank()) {
      doc.setExtractedText(full);
      doc.setLanguage(com.tap.backend.infra.text.LanguageHeuristic.detect(full));
      documentRepository.save(doc);
      return full;
    }
    // 4) Last resort: try as plain text
    String asText = new String(bytes, StandardCharsets.UTF_8);
    if (!asText.isBlank()) {
      doc.setExtractedText(asText);
      documentRepository.save(doc);
      return asText;
    }
    return "";
  }

  private void setReady(String cacheKey) {
    Duration ttl = Duration.ofSeconds(props.cacheTtlSeconds() <= 0 ? 604800 : props.cacheTtlSeconds());
    redis.opsForValue().set(cacheKey, "1", ttl);
  }

  private static String normalizeLang(String lang) {
    return lang == null ? "" : lang.trim().toUpperCase();
  }
}
