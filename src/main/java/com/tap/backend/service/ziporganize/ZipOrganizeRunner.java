package com.tap.backend.service.ziporganize;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.ai.AiProvider;
import com.tap.backend.ai.AiProvider.FileClassifyInput;
import com.tap.backend.ai.AiProvider.FileClassifyResult;
import com.tap.backend.ai.AiProvider.FileClassifySummary;
import com.tap.backend.ai.AiProvider.FolderOrganizeInput;
import com.tap.backend.ai.AiProvider.FolderOrganizeResult;
import com.tap.backend.ai.AiProvider.PlacementRule;
import com.tap.backend.domain.ziporganize.ZipOrganizeExtractStatus;
import com.tap.backend.domain.ziporganize.ZipOrganizeItemEntity;
import com.tap.backend.domain.ziporganize.ZipOrganizeJobEntity;
import com.tap.backend.domain.ziporganize.ZipOrganizeJobStatus;
import com.tap.backend.infra.crypto.Digests;
import com.tap.backend.infra.storage.ObjectStorageService;
import com.tap.backend.infra.text.FileTextExtractor;
import com.tap.backend.quota.QuotaService;
import com.tap.backend.repo.ZipOrganizeItemRepository;
import com.tap.backend.repo.ZipOrganizeJobRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ZipOrganizeRunner {
  private static final Logger log = LoggerFactory.getLogger(ZipOrganizeRunner.class);

  private final ZipOrganizeJobRepository jobRepo;
  private final ZipOrganizeItemRepository itemRepo;
  private final ObjectStorageService storage;
  private final FileTextExtractor textExtractor;
  private final AiProvider aiProvider;
  private final ZipOrganizeProperties props;
  private final ObjectMapper om;
  private final ExecutorService itemExecutor;
  private final QuotaService quotaService;
  private final ZipPackService zipPackService;

  public ZipOrganizeRunner(ZipOrganizeJobRepository jobRepo,
      ZipOrganizeItemRepository itemRepo,
      ObjectStorageService storage,
      FileTextExtractor textExtractor,
      AiProvider aiProvider,
      ZipOrganizeProperties props,
      ObjectMapper om,
      @Qualifier("zipOrganizeItemExecutor") ExecutorService itemExecutor,
      QuotaService quotaService,
      ZipPackService zipPackService) {
    this.jobRepo = jobRepo;
    this.itemRepo = itemRepo;
    this.storage = storage;
    this.textExtractor = textExtractor;
    this.aiProvider = aiProvider;
    this.props = props;
    this.om = om;
    this.itemExecutor = itemExecutor;
    this.quotaService = quotaService;
    this.zipPackService = zipPackService;
  }

  public void runJob(long jobId) {
    MDC.put("zipJobId", String.valueOf(jobId));
    ZipOrganizeJobEntity job = jobRepo.findById(jobId).orElseThrow();
    if (job.getStatus() != ZipOrganizeJobStatus.RUNNING) return;
    long userId = job.getUserId();
    try {
      itemRepo.deleteAllByJob_Id(jobId);

      setStep(job, "INGEST", 2, "枚举 ZIP 内文件...");
      List<ZipOrganizeItemEntity> items = unpack(job);
      setStep(job, "INGEST", 12, "发现 " + items.size() + " 个文件");

      setStep(job, "EXTRACT", 14, "提取文本...");
      extract(job, items);
      setStep(job, "EXTRACT", 34, "文本提取完成");

      setStep(job, "CLASSIFY", 36, "AI 分类标注...");
      classify(job, userId, items);
      setStep(job, "CLASSIFY", 68, "分类完成");

      setStep(job, "ORGANIZE", 70, "生成目录策略...");
      FolderOrganizeResult folderResult = organize(job, userId, items);
      setStep(job, "ORGANIZE", 78, "目录策略生成完成");

      setStep(job, "DELIVER", 80, "生成最终目录与压缩包...");
      plan(items, folderResult);
      itemRepo.saveAll(items);
      byte[] reportJson = buildReport(job, items, folderResult);
      String readme = buildReadme(job, items, folderResult);
      byte[] zipBytes = zipPackService.buildZip(items, readme, reportJson);
      String outputKey = "zip-organize/%d/output/organized.zip".formatted(job.getId());
      String reportKey = "zip-organize/%d/output/report.json".formatted(job.getId());
      storage.putBytes(outputKey, zipBytes, "application/zip");
      storage.putBytes(reportKey, reportJson, "application/json");

      Map<String, Object> result = buildResultMap(outputKey, items, folderResult);
      job.setZipObjectKey(outputKey);
      job.setReportObjectKey(reportKey);
      job.setResultJson(om.writeValueAsString(result));
      job.setStatus(ZipOrganizeJobStatus.SUCCEEDED);
      job.setFinishedAt(Instant.now());
      setStep(job, "DELIVER", 100, "完成");
      jobRepo.save(job);
    } catch (Exception e) {
      job.setStatus(ZipOrganizeJobStatus.FAILED);
      job.setFinishedAt(Instant.now());
      job.setErrorMessage(safe(e.getMessage(), 2000));
      jobRepo.save(job);
      log.warn("zip organize job failed {}", jobId, e);
    } finally {
      MDC.remove("zipJobId");
    }
  }

  private List<ZipOrganizeItemEntity> unpack(ZipOrganizeJobEntity job) throws Exception {
    byte[] zipBytes = storage.getBytes(job.getInputObjectKey());
    int maxFiles = props.maxFiles() <= 0 ? 120 : props.maxFiles();
    List<ZipOrganizeItemEntity> items = new ArrayList<>();
    try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        if (entry.isDirectory()) continue;
        if (items.size() >= maxFiles) throw new IllegalStateException("文件数超过限制");
        String originalPath = ZipOrganizeNaming.normalizeZipEntryPath(entry.getName());
        String filename = ZipOrganizeNaming.filenameOf(originalPath);
        String ext = ZipOrganizeNaming.extOf(filename);
        if (ZipOrganizeNaming.BLOCKED_EXT.contains(ext)) continue;
        byte[] bytes = zis.readAllBytes();
        if (bytes.length == 0) continue;
        String objectKey = "zip-organize/%d/items/%s/%s".formatted(
            job.getId(),
            Digests.sha256Hex(bytes),
            ZipOrganizeNaming.sanitizeFilename(filename, ext)
        );
        storage.putBytes(objectKey, bytes, ZipOrganizeNaming.guessContentType(filename));
        ZipOrganizeItemEntity item = new ZipOrganizeItemEntity();
        item.setJob(job);
        item.setOriginalPath(originalPath);
        item.setFilename(filename);
        item.setContentType(ZipOrganizeNaming.guessContentType(filename));
        item.setSizeBytes(bytes.length);
        item.setSha256(Digests.sha256Hex(bytes));
        item.setExt(ext);
        item.setObjectKey(objectKey);
        items.add(itemRepo.save(item));
      }
    }
    if (items.isEmpty()) throw new IllegalStateException("ZIP 中没有可处理文件");
    return items;
  }

  private void extract(ZipOrganizeJobEntity job, List<ZipOrganizeItemEntity> items) {
    int maxChars = props.extractTextMaxChars() <= 0 ? 8000 : props.extractTextMaxChars();
    int total = items.size();
    for (int i = 0; i < total; i++) {
      ZipOrganizeItemEntity item = items.get(i);
      try {
        byte[] bytes = storage.getBytes(item.getObjectKey());
        String text = textExtractor.extract(item.getFilename(), item.getContentType(), bytes);
        String preview = safe(text, maxChars);
        item.setExtractedTextPreview(preview);
        item.setTitleCandidate(guessTitle(text, item.getFilename()));
        item.setExtractStatus(preview.isBlank() ? ZipOrganizeExtractStatus.EMPTY : ZipOrganizeExtractStatus.EXTRACTED);
      } catch (Exception e) {
        item.setExtractStatus(ZipOrganizeExtractStatus.FAILED);
        item.setExtractedTextPreview("");
      }
      itemRepo.save(item);
      setStep(job, "EXTRACT", 14 + (int) Math.round(20.0 * (i + 1) / Math.max(1, total)), null);
    }
  }

  private void classify(ZipOrganizeJobEntity job, long userId, List<ZipOrganizeItemEntity> items) {
    var mdc = MDC.getCopyOfContextMap();
    ExecutorCompletionService<ItemResult> cs = new ExecutorCompletionService<>(itemExecutor);
    int aiChars = props.aiTextMaxChars() <= 0 ? 5000 : props.aiTextMaxChars();
    for (ZipOrganizeItemEntity item : items) {
      cs.submit(() -> {
        if (mdc != null) MDC.setContextMap(mdc);
        try {
          String text = safe(item.getExtractedTextPreview(), aiChars);
          if (text.isBlank()) {
            return new ItemResult(item.getId(), fallbackClassify(item), "文本为空");
          }
          quotaService.consumeAiRequests(userId, 1);
          FileClassifyResult result = aiProvider.classifyFile(new FileClassifyInput(0L, item.getOriginalPath(), text));
          return new ItemResult(item.getId(), result, null);
        } catch (Exception e) {
          return new ItemResult(item.getId(), fallbackClassify(item), e.getMessage());
        }
      });
    }

    int total = items.size();
    Map<Long, ZipOrganizeItemEntity> itemMap = new HashMap<>();
    for (ZipOrganizeItemEntity item : items) itemMap.put(item.getId(), item);
    for (int i = 0; i < total; i++) {
      try {
        ItemResult out = cs.take().get();
        ZipOrganizeItemEntity item = itemMap.get(out.itemId());
        if (item == null) continue;
        FileClassifyResult r = out.result();
        item.setDocKind(r.docKind());
        item.setTopic(r.topic());
        item.setSummaryZh(r.summaryZh());
        item.setYearValue(r.year());
        item.setConfidence(r.confidence());
        item.setKeywordsJson(om.writeValueAsString(r.keywords() == null ? List.of() : r.keywords()));
        if (out.errorMessage() != null && !out.errorMessage().isBlank()) {
          item.setReviewFlag(true);
          item.setReviewReason(safe(out.errorMessage(), 200));
        }
        itemRepo.save(item);
      } catch (Exception e) {
        log.warn("zip classify task error", e);
      }
      setStep(job, "CLASSIFY", 36 + (int) Math.round(32.0 * (i + 1) / Math.max(1, total)), null);
    }
  }

  private FolderOrganizeResult organize(ZipOrganizeJobEntity job, long userId, List<ZipOrganizeItemEntity> items) {
    List<FileClassifySummary> summaries = items.stream()
        .map(item -> new FileClassifySummary(
            0L,
            item.getOriginalPath(),
            blankTo(item.getDocKind(), "other"),
            blankTo(item.getTopic(), ""),
            List.of(),
            readKeywords(item),
            blankTo(item.getSummaryZh(), ""),
            item.getYearValue(),
            item.getConfidence()
        ))
        .toList();

    try {
      quotaService.consumeAiRequests(userId, 1);
      FolderOrganizeResult result = aiProvider.organizeFolder(new FolderOrganizeInput(job.getId(), summaries));
      return normalize(result, summaries);
    } catch (Exception e) {
      return fallbackOrganize(summaries);
    }
  }

  private void plan(List<ZipOrganizeItemEntity> items, FolderOrganizeResult folderResult) {
    Map<String, String> rules = new LinkedHashMap<>();
    if (folderResult.placementRules() != null) {
      for (PlacementRule rule : folderResult.placementRules()) {
        if (rule != null && rule.condition() != null && rule.targetFolder() != null) {
          rules.put(rule.condition(), rule.targetFolder());
        }
      }
    }
    double reviewThreshold = folderResult.reviewThreshold() > 0 ? folderResult.reviewThreshold()
        : (props.reviewThreshold() > 0 ? props.reviewThreshold() : 0.6);

    Set<String> used = new HashSet<>();
    Map<String, String> firstSha = new HashMap<>();
    items.sort(Comparator.comparing(ZipOrganizeItemEntity::getOriginalPath));
    for (ZipOrganizeItemEntity item : items) {
      String targetFolder = matchPlacement(item, rules, folderResult.folderSchema());
      String newFilename = applyNamingRule(folderResult.namingRule(), item);

      if (item.getSha256() != null && firstSha.containsKey(item.getSha256())) {
        item.setDuplicateGroupId("dup_" + item.getSha256().substring(0, Math.min(8, item.getSha256().length())));
        targetFolder = "重复文件";
      } else if (item.getSha256() != null) {
        firstSha.put(item.getSha256(), item.getFilename());
      }

      boolean review = item.isReviewFlag()
          || item.getConfidence() < reviewThreshold
          || "other".equals(blankTo(item.getDocKind(), "other"));
      if (review) {
        item.setReviewFlag(true);
        if (item.getReviewReason() == null || item.getReviewReason().isBlank()) {
          item.setReviewReason("置信度不足或类型不明确");
        }
        targetFolder = "待确认";
      }

      String finalPath = ZipOrganizeNaming.ensureUniquePath(
          ZipOrganizeNaming.buildRelativePath(targetFolder, newFilename),
          used
      );
      item.setTargetFolder(ZipOrganizeNaming.sanitizeFolderPath(targetFolder));
      item.setNewFilename(ZipOrganizeNaming.filenameOf(finalPath));
      item.setFinalPath(finalPath);
    }
  }

  private byte[] buildReport(ZipOrganizeJobEntity job, List<ZipOrganizeItemEntity> items, FolderOrganizeResult folderResult) throws Exception {
    Map<String, Object> result = buildResultMap("zip-organize/%d/output/organized.zip".formatted(job.getId()), items, folderResult);
    return om.writeValueAsBytes(result);
  }

  private String buildReadme(ZipOrganizeJobEntity job, List<ZipOrganizeItemEntity> items, FolderOrganizeResult folderResult) {
    long reviewCount = items.stream().filter(ZipOrganizeItemEntity::isReviewFlag).count();
    StringBuilder sb = new StringBuilder();
    sb.append("# AI 智能整理报告\n\n");
    sb.append("- 任务ID: ").append(job.getId()).append('\n');
    sb.append("- 主题: ").append(folderResult.folderTopic()).append('\n');
    sb.append("- 文件总数: ").append(items.size()).append('\n');
    sb.append("- 待确认: ").append(reviewCount).append('\n');
    sb.append("- 模型: ").append(aiProvider.name()).append(" / ").append(aiProvider.model()).append('\n');
    return sb.toString();
  }

  private Map<String, Object> buildResultMap(String zipKey, List<ZipOrganizeItemEntity> items, FolderOrganizeResult folderResult) {
    long reviewCount = items.stream().filter(ZipOrganizeItemEntity::isReviewFlag).count();
    long duplicateCount = items.stream().filter(item -> item.getDuplicateGroupId() != null).count();
    Map<String, Object> resultMap = new LinkedHashMap<>();
    resultMap.put("provider", aiProvider.name());
    resultMap.put("model", aiProvider.model());
    resultMap.put("folderTopic", folderResult.folderTopic());
    resultMap.put("folderTags", folderResult.folderTags());
    resultMap.put("groupingStrategy", folderResult.groupingStrategy());
    resultMap.put("namingRule", folderResult.namingRule());
    resultMap.put("totalFiles", items.size());
    resultMap.put("reviewCount", reviewCount);
    resultMap.put("duplicateCount", duplicateCount);
    resultMap.put("zipObjectKey", zipKey);
    resultMap.put("files", items.stream().map(item -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("originalName", item.getFilename());
      m.put("targetFolder", item.getTargetFolder());
      m.put("newFilename", item.getNewFilename());
      m.put("docKind", item.getDocKind());
      m.put("topic", item.getTopic());
      m.put("confidence", item.getConfidence());
      m.put("reviewFlag", item.isReviewFlag());
      m.put("reviewReason", item.getReviewReason());
      m.put("duplicateGroupId", item.getDuplicateGroupId());
      m.put("applied", true);
      return m;
    }).toList());
    return resultMap;
  }

  private void setStep(ZipOrganizeJobEntity job, String step, int progress, String detail) {
    job.setCurrentStep(step);
    job.setProgress(Math.max(0, Math.min(100, progress)));
    if (detail != null) job.setStepDetail(detail);
    jobRepo.save(job);
  }

  private String matchPlacement(ZipOrganizeItemEntity item, Map<String, String> rules, List<String> schema) {
    String docKind = blankTo(item.getDocKind(), "other").toLowerCase(Locale.ROOT);
    String topic = blankTo(item.getTopic(), "").toLowerCase(Locale.ROOT);
    for (var entry : rules.entrySet()) {
      String cond = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
      if (cond.contains("dockind==" + docKind)) return entry.getValue();
      if (!topic.isBlank() && cond.contains("topic==" + topic)) return entry.getValue();
    }
    if (schema != null) {
      for (String dir : schema) {
        if (dir != null && dir.toLowerCase(Locale.ROOT).contains(docKind)) return dir;
      }
    }
    return switch (docKind) {
      case "paper" -> "论文";
      case "teaching" -> "教学资料";
      case "data" -> "数据";
      case "code" -> "代码";
      case "admin" -> "行政材料";
      default -> "其他";
    };
  }

  private String applyNamingRule(String namingRule, ZipOrganizeItemEntity item) {
    String ext = item.getExt() == null || item.getExt().isBlank() ? "" : "." + item.getExt();
    String baseName = item.getFilename().contains(".")
        ? item.getFilename().substring(0, item.getFilename().lastIndexOf('.'))
        : item.getFilename();
    String rule = blankTo(namingRule, "{topic}_{filename}");
    String out = rule
        .replace("{year}", blankTo(item.getYearValue(), ""))
        .replace("{topic}", blankTo(item.getTopic(), "other"))
        .replace("{docKind}", blankTo(item.getDocKind(), "other"))
        .replace("{filename}", baseName)
        .replace("{shortTitle}", safe(baseName, 40));
    out = out.replaceAll("_+", "_").replaceAll("^_|_$", "");
    if (out.isBlank()) out = baseName;
    return ZipOrganizeNaming.sanitizeFilename(out + ext, item.getExt());
  }

  private FileClassifyResult fallbackClassify(ZipOrganizeItemEntity item) {
    String ext = blankTo(item.getExt(), "");
    String docKind = switch (ext) {
      case "pdf", "doc", "docx" -> "paper";
      case "pptx" -> "teaching";
      case "csv" -> "data";
      default -> "other";
    };
    return new FileClassifyResult(docKind, "", List.of(), List.of(), "", null, 0.2, "fallback");
  }

  private FolderOrganizeResult normalize(FolderOrganizeResult result, List<FileClassifySummary> summaries) {
    if (result == null) return fallbackOrganize(summaries);
    return new FolderOrganizeResult(
        blankTo(result.folderTopic(), "智能整理结果"),
        result.folderTags() == null || result.folderTags().isEmpty() ? List.of("自动整理") : result.folderTags(),
        blankTo(result.groupingStrategy(), "docKind"),
        result.folderSchema() == null || result.folderSchema().isEmpty() ? List.of("论文", "教学资料", "数据", "代码", "其他", "待确认") : result.folderSchema(),
        result.placementRules() == null ? List.of() : result.placementRules(),
        blankTo(result.namingRule(), "{topic}_{filename}"),
        result.reviewThreshold() > 0 ? result.reviewThreshold() : 0.5
    );
  }

  private FolderOrganizeResult fallbackOrganize(List<FileClassifySummary> summaries) {
    LinkedHashSet<String> tags = new LinkedHashSet<>();
    for (FileClassifySummary summary : summaries) {
      if (summary.subjectTags() != null) tags.addAll(summary.subjectTags());
    }
    return new FolderOrganizeResult(
        "智能整理结果",
        tags.isEmpty() ? List.of("自动整理", "本地兜底") : tags.stream().limit(8).toList(),
        "docKind",
        List.of("论文", "教学资料", "数据", "代码", "其他", "待确认"),
        List.of(
            new PlacementRule("docKind==paper", "论文"),
            new PlacementRule("docKind==teaching", "教学资料"),
            new PlacementRule("docKind==data", "数据"),
            new PlacementRule("docKind==code", "代码"),
            new PlacementRule("docKind==admin", "行政材料"),
            new PlacementRule("docKind==other", "其他")
        ),
        "{topic}_{filename}",
        0.5
    );
  }

  private List<String> readKeywords(ZipOrganizeItemEntity item) {
    try {
      return om.readValue(blankTo(item.getKeywordsJson(), "[]"),
          om.getTypeFactory().constructCollectionType(List.class, String.class));
    } catch (Exception e) {
      return List.of();
    }
  }

  private static String guessTitle(String text, String filename) {
    if (text == null || text.isBlank()) return filename;
    for (String line : text.split("\n")) {
      String trimmed = line.trim();
      if (!trimmed.isEmpty() && trimmed.length() > 3 && trimmed.length() < 200) return trimmed;
    }
    return filename;
  }

  private static String safe(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max);
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private record ItemResult(Long itemId, FileClassifyResult result, String errorMessage) {}
}
