package com.tap.backend.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.ai.AiProvider;
import com.tap.backend.domain.document.DocumentEntity;
import com.tap.backend.domain.paper.PaperEntity;
import com.tap.backend.domain.summary.StructuredSummaryEntity;
import com.tap.backend.infra.crypto.Digests;
import com.tap.backend.infra.storage.ObjectStorageService;
import com.tap.backend.infra.text.FileTextExtractor;
import com.tap.backend.quota.QuotaService;
import com.tap.backend.repo.DocumentRepository;
import com.tap.backend.repo.PaperRepository;
import com.tap.backend.repo.StructuredSummaryRepository;
import com.tap.backend.summary.dto.StructuredSummaryDto;
import com.tap.backend.summary.dto.StructuredSummaryResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SummaryService {
  private final PaperRepository paperRepository;
  private final DocumentRepository documentRepository;
  private final StructuredSummaryRepository structuredSummaryRepository;
  private final StringRedisTemplate redis;
  private final ObjectStorageService objectStorageService;
  private final FileTextExtractor fileTextExtractor;
  private final AiProvider aiProvider;
  private final ObjectMapper objectMapper;
  private final QuotaService quotaService;
  private final ArxivFetchService arxivFetchService;
  private final DoiFetchService doiFetchService;

  public SummaryService(PaperRepository paperRepository,
      DocumentRepository documentRepository,
      StructuredSummaryRepository structuredSummaryRepository,
      StringRedisTemplate redis,
      ObjectStorageService objectStorageService,
      FileTextExtractor fileTextExtractor,
      AiProvider aiProvider,
      ObjectMapper objectMapper,
      QuotaService quotaService,
      ArxivFetchService arxivFetchService,
      DoiFetchService doiFetchService) {
    this.paperRepository = paperRepository;
    this.documentRepository = documentRepository;
    this.structuredSummaryRepository = structuredSummaryRepository;
    this.redis = redis;
    this.objectStorageService = objectStorageService;
    this.fileTextExtractor = fileTextExtractor;
    this.aiProvider = aiProvider;
    this.objectMapper = objectMapper;
    this.quotaService = quotaService;
    this.arxivFetchService = arxivFetchService;
    this.doiFetchService = doiFetchService;
  }

  @Transactional
  public StructuredSummaryResponse summarizePaper(long userId, String arxivId, boolean force) throws Exception {
    PaperEntity paper = paperRepository.findByArxivId(arxivId)
        .orElseGet(() -> { try { return arxivFetchService.fetchAndSave(arxivId); } catch (Exception e) { throw new RuntimeException(e); } });
    // 旧记录只有摘要（< 500字符），重新抓 PDF 全文（仅限 arXiv 论文）
    boolean isArxivPaper = !arxivId.startsWith("doi:") && !arxivId.startsWith("text:");
    if (isArxivPaper && (paper.getAbstractText() == null || paper.getAbstractText().length() < 500)) {
      try { paper = arxivFetchService.fetchAndSave(arxivId); } catch (Exception ignored) {}
    }
    String text = buildPaperText(paper);
    String contentHash = sha(text);
    return summarize(userId, SummaryScopeType.PAPER, arxivId, contentHash, text, force);
  }

  @Transactional
  public StructuredSummaryResponse summarizeDocument(long userId, long docId, boolean force) throws Exception {
    DocumentEntity doc = documentRepository.findByIdAndUser_Id(docId, userId)
        .orElseThrow(() -> new IllegalArgumentException("document not found"));
    String text = loadDocumentText(doc);
    String contentHash = sha(text);
    return summarize(userId, SummaryScopeType.DOCUMENT, String.valueOf(docId), contentHash, text, force);
  }

  private StructuredSummaryResponse summarize(long userId, SummaryScopeType scopeType, String scopeKey,
      String contentHash, String text, boolean force) throws Exception {
    String provider = aiProvider.name();
    String model = aiProvider.model();
    String cacheKey = "tap:sum:v1:" + scopeType + ":" + scopeKey + ":" + provider + ":" + model + ":" + contentHash;

    if (!force) {
      String cachedId = redis.opsForValue().get(cacheKey);
      if (cachedId != null && !cachedId.isBlank()) {
        StructuredSummaryEntity e = structuredSummaryRepository.findById(Long.valueOf(cachedId)).orElse(null);
        if (e != null) return toResponse(scopeType, scopeKey, e);
      }
    }

    StructuredSummaryEntity existing = structuredSummaryRepository
        .findByScopeTypeAndScopeKeyAndProviderAndModel(scopeType.name(), scopeKey, provider, model)
        .orElse(null);
    if (!force && existing != null && contentHash.equals(existing.getContentHash())) {
      cache(cacheKey, existing.getId());
      return toResponse(scopeType, scopeKey, existing);
    }

    quotaService.consumeAiRequests(userId, 1);
    AiProvider.StructuredSummary out = aiProvider.structuredSummary(
        new AiProvider.StructuredSummaryInput(scopeType.name(), scopeKey, text, 500, 900));
    StructuredSummaryDto dto = new StructuredSummaryDto(
        out.researchProblemMotivation(), out.methods(), out.experimentsData(),
        out.conclusions(), out.limitationsInsights());

    String markdown = markdown(dto);
    if (countZhChars(markdown) > 900) markdown = trimToZhChars(markdown, 900);

    String summaryJson = objectMapper.writeValueAsString(dto);
    StructuredSummaryEntity saved = existing == null ? new StructuredSummaryEntity() : existing;
    saved.setScopeType(scopeType.name());
    saved.setScopeKey(scopeKey);
    saved.setProvider(provider);
    saved.setModel(model == null ? "" : model);
    saved.setContentHash(contentHash);
    saved.setSummaryJson(summaryJson);
    saved.setMarkdown(markdown);
    saved = structuredSummaryRepository.save(saved);

    cache(cacheKey, saved.getId());
    return toResponse(scopeType, scopeKey, saved);
  }

  private StructuredSummaryResponse toResponse(SummaryScopeType scopeType, String scopeKey, StructuredSummaryEntity e)
      throws Exception {
    StructuredSummaryDto structured = objectMapper.readValue(e.getSummaryJson(), StructuredSummaryDto.class);
    return new StructuredSummaryResponse(scopeType.name(), scopeKey, e.getProvider(), e.getModel(),
        countZhChars(e.getMarkdown()), structured, e.getMarkdown());
  }

  private void cache(String key, Long id) {
    redis.opsForValue().set(key, String.valueOf(id), Duration.ofHours(24));
  }

  private String buildPaperText(PaperEntity p) {
    String authors = String.join(", ", p.getAuthors() == null ? List.of() : p.getAuthors());
    String cats = String.join(", ", p.getCategories() == null ? List.of() : p.getCategories());
    return "Title: " + n(p.getTitle()) + "\n"
        + "Authors: " + n(authors) + "\n"
        + "Categories: " + n(cats) + "\n"
        + "Abstract:\n" + n(p.getAbstractText());
  }

  private String loadDocumentText(DocumentEntity doc) {
    if (doc.getExtractedTextKey() != null && !doc.getExtractedTextKey().isBlank()) {
      try { return new String(objectStorageService.getBytes(doc.getExtractedTextKey()), StandardCharsets.UTF_8); }
      catch (Exception ignored) {}
    }
    if (doc.getExtractedText() != null && !doc.getExtractedText().isBlank()) return doc.getExtractedText();
    byte[] bytes = objectStorageService.getBytes(doc.getObjectKey());
    String full = fileTextExtractor.extract(doc.getFilename(), doc.getContentType(), bytes);
    doc.setExtractedText(full == null ? "" : full);
    documentRepository.save(doc);
    return doc.getExtractedText();
  }

  private static String sha(String text) {
    return Digests.sha256Hex((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
  }

  private static String markdown(StructuredSummaryDto s) {
    StringBuilder sb = new StringBuilder();
    sb.append("## 研究问题/动机\n").append(n(s.researchProblemMotivation())).append("\n\n");
    sb.append("## 方法\n");
    for (String m : safeList(s.methods())) sb.append("- ").append(m).append("\n");
    sb.append("\n## 实验/数据\n");
    for (String m : safeList(s.experimentsData())) sb.append("- ").append(m).append("\n");
    sb.append("\n## 结论\n").append(n(s.conclusions())).append("\n\n");
    sb.append("## 局限与启发\n");
    for (String m : safeList(s.limitationsInsights())) sb.append("- ").append(m).append("\n");
    return sb.toString().trim();
  }

  private static List<String> safeList(List<String> in) {
    return in == null ? List.of() : in.stream().filter(x -> x != null && !x.isBlank()).toList();
  }

  private static String n(String s) { return s == null ? "" : s.trim(); }

  private static int countZhChars(String s) {
    if (s == null) return 0;
    int cnt = 0;
    for (char c : s.toCharArray()) if (c >= 0x4E00 && c <= 0x9FFF) cnt++;
    return cnt;
  }

  private static String trimToZhChars(String s, int maxZh) {
    if (s == null) return "";
    StringBuilder out = new StringBuilder();
    int zh = 0;
    for (char c : s.toCharArray()) {
      if (c >= 0x4E00 && c <= 0x9FFF && ++zh > maxZh) break;
      out.append(c);
    }
    return out.toString();
  }
}
