package com.tap.backend.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.ai.AiProvider;
import com.tap.backend.ai.AiProvider.*;
import com.tap.backend.domain.agent.*;
import com.tap.backend.domain.document.DocumentEntity;
import com.tap.backend.infra.storage.ObjectStorageService;
import com.tap.backend.infra.text.FileTextExtractor;
import com.tap.backend.infra.text.LanguageHeuristic;
import com.tap.backend.quota.QuotaService;
import com.tap.backend.repo.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class AgentJobRunner {
  private static final Logger log = LoggerFactory.getLogger(AgentJobRunner.class);
  private static final int MAX_FILES = 200;
  private static final long MAX_TOTAL_BYTES = 500L * 1024 * 1024; // 500MB
  private static final long MAX_SINGLE_BYTES = 100L * 1024 * 1024; // 100MB
  private static final Set<String> BLOCKED_EXT = Set.of("exe","bat","cmd","sh","ps1","dll","so","msi","com");
  private static final Pattern UNSAFE_CHARS = Pattern.compile("[\\\\/<>:\"|?*\\x00-\\x1f]");
  private static final int AI_TEXT_LIMIT = 4000; // chars sent to AI per file

  private final AgentJobRepository jobRepo;
  private final AgentResultRepository resultRepo;
  private final AgentJobFileRepository jobFileRepo;
  private final AgentFileExtractRepository extractRepo;
  private final AgentOrganizePlanRepository planRepo;
  private final DocumentRepository documentRepo;
  private final ObjectStorageService storage;
  private final FileTextExtractor textExtractor;
  private final com.tap.backend.service.DocumentIngestProperties docProps;
  private final AiProvider aiProvider;
  private final AgentProperties props;
  private final ObjectMapper om;
  private final ExecutorService docExecutor;
  private final QuotaService quotaService;

  public AgentJobRunner(AgentJobRepository jobRepo, AgentResultRepository resultRepo,
      AgentJobFileRepository jobFileRepo, AgentFileExtractRepository extractRepo,
      AgentOrganizePlanRepository planRepo, DocumentRepository documentRepo,
      ObjectStorageService storage, FileTextExtractor textExtractor,
      com.tap.backend.service.DocumentIngestProperties docProps,
      AiProvider aiProvider, AgentProperties props, ObjectMapper om,
      ExecutorService agentDocExecutor, QuotaService quotaService) {
    this.jobRepo = jobRepo;
    this.resultRepo = resultRepo;
    this.jobFileRepo = jobFileRepo;
    this.extractRepo = extractRepo;
    this.planRepo = planRepo;
    this.documentRepo = documentRepo;
    this.storage = storage;
    this.textExtractor = textExtractor;
    this.docProps = docProps;
    this.aiProvider = aiProvider;
    this.props = props;
    this.om = om;
    this.docExecutor = agentDocExecutor;
    this.quotaService = quotaService;
  }

  public void runJob(long jobId) {
    MDC.put("jobId", String.valueOf(jobId));
    AgentJobEntity job = jobRepo.findById(jobId).orElseThrow();
    if (job.getStatus() != AgentJobStatus.RUNNING) return;
    long userId = job.getUserId();
    long folderId = job.getUploadFolderId();

    try {
      log.info("Agent job started. folderId={} provider={}", folderId, aiProvider.name());

      // ===== Stage 1: Ingest & Manifest =====
      setStep(job, "INGEST", 2, "枚举文件...");
      List<AgentJobFileEntity> jobFiles = stageIngest(job, folderId);
      setStep(job, "INGEST", 10, "清单完成，共 " + jobFiles.size() + " 个文件");

      // ===== Stage 2: Text Extract (no OCR) =====
      setStep(job, "EXTRACT", 12, "提取文本...");
      Map<Long, AgentFileExtractEntity> extracts = stageExtract(job, jobFiles);
      setStep(job, "EXTRACT", 30, "文本提取完成");

      // ===== Stage 3: File-level AI =====
      setStep(job, "CLASSIFY", 32, "AI 分类标注...");
      Map<Long, FileClassifyResult> classResults = stageClassify(job, userId, jobFiles, extracts);
      setStep(job, "CLASSIFY", 65, "分类完成");

      // ===== Stage 4: Folder-level AI =====
      setStep(job, "ORGANIZE", 67, "生成组织策略...");
      FolderOrganizeResult folderResult = stageOrganize(job, userId, folderId, jobFiles, classResults);
      setStep(job, "ORGANIZE", 75, "策略生成完成");

      // ===== Stage 5: Plan → Apply → Deliver =====
      setStep(job, "DELIVER", 77, "生成执行计划...");
      List<AgentOrganizePlanEntity> plans = stagePlan(job, jobFiles, classResults, folderResult);
      setStep(job, "DELIVER", 82, "执行文件复制...");
      stageApply(job, plans);
      setStep(job, "DELIVER", 90, "生成交付物...");
      stageDeliver(job, plans, folderResult, classResults, jobFiles);
      setStep(job, "DELIVER", 100, "完成");

      job.setStatus(AgentJobStatus.SUCCEEDED);
      job.setFinishedAt(Instant.now());
      jobRepo.save(job);
      log.info("Agent job succeeded. jobId={}", jobId);

    } catch (Exception e) {
      job.setStatus(AgentJobStatus.FAILED);
      job.setFinishedAt(Instant.now());
      job.setErrorMessage(e.toString().substring(0, Math.min(2000, e.toString().length())));
      jobRepo.save(job);
      log.error("Agent job failed. jobId={}", jobId, e);
    } finally {
      MDC.remove("jobId");
    }
  }

  // ==================== Stage 1: Ingest & Manifest ====================
  private List<AgentJobFileEntity> stageIngest(AgentJobEntity job, long folderId) {
    List<DocumentEntity> docs = documentRepo.findAllByUploadFolder_Id(folderId);
    docs.sort(Comparator.comparing(DocumentEntity::getOriginalPath, Comparator.nullsLast(String::compareTo)));

    if (docs.size() > MAX_FILES) throw new IllegalStateException("文件数超限：" + docs.size() + " > " + MAX_FILES);
    long totalBytes = docs.stream().mapToLong(DocumentEntity::getSizeBytes).sum();
    if (totalBytes > MAX_TOTAL_BYTES) throw new IllegalStateException("总大小超限");

    // Clean old data if retrying (order matters: plan -> extract -> job_file)
    planRepo.deleteAllByJobId(job.getId());
    // Delete extracts for old job files
    List<AgentJobFileEntity> oldFiles = jobFileRepo.findAllByJobId(job.getId());
    for (AgentJobFileEntity old : oldFiles) {
      extractRepo.findByJobFileId(old.getId()).ifPresent(extractRepo::delete);
    }
    extractRepo.flush();
    jobFileRepo.deleteAllByJobId(job.getId());
    jobFileRepo.flush();

    List<AgentJobFileEntity> result = new ArrayList<>();
    for (DocumentEntity doc : docs) {
      String ext = extractExt(doc.getFilename());
      if (BLOCKED_EXT.contains(ext)) continue;
      if (doc.getSizeBytes() > MAX_SINGLE_BYTES) continue;

      AgentJobFileEntity jf = new AgentJobFileEntity();
      jf.setJob(job);
      jf.setDocument(doc);
      jf.setObjectKey(doc.getObjectKey());
      jf.setFilename(doc.getFilename());
      jf.setContentType(doc.getContentType());
      jf.setSizeBytes(doc.getSizeBytes());
      jf.setSha256(doc.getSha256());
      jf.setExt(ext);
      jf.setStatus("OK");
      result.add(jobFileRepo.save(jf));
    }
    return result;
  }

  // ==================== Stage 2: Text Extract ====================
  private Map<Long, AgentFileExtractEntity> stageExtract(AgentJobEntity job, List<AgentJobFileEntity> jobFiles) {
    Map<Long, AgentFileExtractEntity> map = new HashMap<>();
    int total = jobFiles.size();
    if (total == 0) return map;

    // Pre-read values in main thread to avoid lazy-loading in worker threads.
    Map<Long, String> extractedTextByDocId = new HashMap<>();
    List<Long> docIds = new ArrayList<>();
    for (AgentJobFileEntity jf : jobFiles) {
      Long docId = jf.getDocument().getId();
      if (docId != null) docIds.add(docId);
    }
    for (DocumentEntity d : documentRepo.findAllById(docIds)) {
      extractedTextByDocId.put(d.getId(), d.getExtractedText());
    }

    record ExtractTask(Long jfId, Long docId, String objectKey, String filename, String contentType, String cachedText) {}
    record ExtractResult(Long jfId, AgentFileExtractEntity ext, String errorMessage) {}

    List<ExtractTask> tasks = new ArrayList<>();
    for (AgentJobFileEntity jf : jobFiles) {
      Long docId = jf.getDocument().getId();
      tasks.add(new ExtractTask(
          jf.getId(),
          docId,
          jf.getObjectKey(),
          jf.getFilename(),
          jf.getContentType(),
          docId == null ? null : extractedTextByDocId.get(docId)
      ));
    }

    var mdc = MDC.getCopyOfContextMap();
    ExecutorCompletionService<ExtractResult> cs = new ExecutorCompletionService<>(docExecutor);
    for (ExtractTask task : tasks) {
      cs.submit(() -> {
        if (mdc != null) MDC.setContextMap(mdc);
        try {
          String fullText = task.cachedText();
          if (fullText == null || fullText.isBlank()) {
            byte[] bytes = storage.getBytes(task.objectKey());
            fullText = textExtractor.extract(task.filename(), task.contentType(), bytes);

            // Save extracted text back to document for future reuse.
            if (task.docId() != null && fullText != null && !fullText.isBlank()) {
              int maxChars = docProps.extractedTextMaxChars() <= 0 ? 20000 : docProps.extractedTextMaxChars();
              final String finalFullText = fullText;
              documentRepo.findById(task.docId()).ifPresent(doc -> {
                doc.setExtractedText(finalFullText.length() > maxChars ? finalFullText.substring(0, maxChars) : finalFullText);
                doc.setExtractedTextTruncated(finalFullText.length() > maxChars);
                doc.setLanguage(LanguageHeuristic.detect(finalFullText));
                documentRepo.save(doc);
              });
            }
          }
          if (fullText == null) fullText = "";

          AgentFileExtractEntity ext = new AgentFileExtractEntity();
          ext.setJobFile(jobFileRepo.getReferenceById(task.jfId()));
          ext.setTitleCandidate(guessTitle(fullText, task.filename()));
          ext.setAbstractSnippet(extractAbstract(fullText));
          ext.setBodyPreview(safe(fullText, AI_TEXT_LIMIT));
          ext.setHeadingsJson(om.writeValueAsString(extractHeadings(fullText)));
          ext.setMetadataJson(om.writeValueAsString(guessMetadata(fullText, task.filename())));
          return new ExtractResult(task.jfId(), extractRepo.save(ext), null);
        } catch (Exception e) {
          return new ExtractResult(task.jfId(), null, e.getMessage());
        }
      });
    }

    for (int i = 0; i < total; i++) {
      try {
        ExtractResult result = cs.take().get();
        if (result.ext() != null) {
          map.put(result.jfId(), result.ext());
        } else {
          jobFileRepo.findById(result.jfId()).ifPresent(jf -> {
            jf.setStatus("EXTRACT_FAILED");
            jf.setErrorMessage(result.errorMessage());
            jobFileRepo.save(jf);
          });
        }
      } catch (Exception e) {
        log.warn("extract task error", e);
      }
      setStep(job, "EXTRACT", 12 + (int)(18.0 * (i + 1) / Math.max(1, total)), null);
    }
    return map;
  }

  // ==================== Stage 3: File-level AI ====================
  private Map<Long, FileClassifyResult> stageClassify(AgentJobEntity job, long userId,
      List<AgentJobFileEntity> jobFiles, Map<Long, AgentFileExtractEntity> extracts) {
    int total = jobFiles.size();
    var mdc = MDC.getCopyOfContextMap();
    ExecutorCompletionService<Map.Entry<Long, FileClassifyResult>> cs = new ExecutorCompletionService<>(docExecutor);

    // Pre-read values in main thread to avoid lazy-loading in worker threads
    record ClassifyTask(Long jfId, Long docId, String filename) {}
    List<ClassifyTask> tasks = jobFiles.stream()
        .map(jf -> new ClassifyTask(jf.getId(), jf.getDocument().getId(), jf.getFilename()))
        .toList();

    for (ClassifyTask task : tasks) {
      cs.submit(() -> {
        if (mdc != null) MDC.setContextMap(mdc);
        try {
          AgentFileExtractEntity ext = extracts.get(task.jfId);
          if (ext == null || (ext.getBodyPreview() == null || ext.getBodyPreview().isBlank())) {
            return Map.entry(task.jfId, new FileClassifyResult("other", "", List.of(), List.of(),
                "无法提取文本", null, 0.1, "文本为空"));
          }
          // Build AI input text: title + abstract + body preview
          StringBuilder aiText = new StringBuilder();
          if (ext.getTitleCandidate() != null) aiText.append("标题: ").append(ext.getTitleCandidate()).append("\n");
          if (ext.getAbstractSnippet() != null && !ext.getAbstractSnippet().isBlank())
            aiText.append("摘要: ").append(ext.getAbstractSnippet()).append("\n");
          aiText.append("正文: ").append(safe(ext.getBodyPreview(), AI_TEXT_LIMIT));

          int maxRetries = props.docTaskMaxRetries() <= 0 ? 3 : props.docTaskMaxRetries();
          Exception last = null;
          for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
              quotaService.consumeAiRequests(userId, 1);
              FileClassifyResult r = aiProvider.classifyFile(
                  new FileClassifyInput(task.docId, task.filename, aiText.toString()));
              // Schema validation
              if (r.docKind() == null || r.docKind().isBlank()) throw new IllegalStateException("docKind is empty");
              return Map.entry(task.jfId, r);
            } catch (Exception e) { last = e; }
          }
          return Map.entry(task.jfId, new FileClassifyResult("other", "", List.of(), List.of(),
              "分类失败", null, 0.0, last == null ? "unknown" : last.getMessage()));
        } finally { MDC.remove("docId"); }
      });
    }

    Map<Long, FileClassifyResult> results = new HashMap<>();
    for (int i = 0; i < total; i++) {
      try {
        var entry = cs.take().get();
        results.put(entry.getKey(), entry.getValue());
      } catch (Exception e) {
        log.warn("classify task error", e);
      }
      setStep(job, "CLASSIFY", 32 + (int)(33.0 * (i + 1) / Math.max(1, total)), null);
    }
    return results;
  }

  // ==================== Stage 4: Folder-level AI ====================
  private FolderOrganizeResult stageOrganize(AgentJobEntity job, long userId, long folderId,
      List<AgentJobFileEntity> jobFiles, Map<Long, FileClassifyResult> classResults) {
    List<FileClassifySummary> summaries = jobFiles.stream()
        .map(jf -> {
          FileClassifyResult r = classResults.getOrDefault(jf.getId(),
              new FileClassifyResult("other","",List.of(),List.of(),"",null,0,""));
          long docId = jf.getDocument().getId(); // safe: still in main thread with open session
          return new FileClassifySummary(docId, jf.getFilename(),
              r.docKind(), r.topic(), r.subjectTags(), r.keywords(), r.summaryZh(), r.year(), r.confidence());
        }).toList();
    FolderOrganizeInput input = new FolderOrganizeInput(folderId, summaries);

    int maxRetries = props.docTaskMaxRetries() <= 0 ? 3 : props.docTaskMaxRetries();
    Exception last = null;
    for (int attempt = 1; attempt <= maxRetries; attempt++) {
      try {
        quotaService.consumeAiRequests(userId, 1);
        FolderOrganizeResult result = aiProvider.organizeFolder(input);
        return normalizeFolderOrganizeResult(result, summaries);
      } catch (Exception e) {
        last = e;
        log.warn("Folder organize attempt {} failed for job {}: {}", attempt, job.getId(), e.getMessage());
      }
    }

    log.warn("Falling back to local organize strategy for job {} after AI organize failure", job.getId(), last);
    return buildLocalFallbackOrganizeResult(summaries);
  }

  // ==================== Stage 5a: Plan ====================
  private List<AgentOrganizePlanEntity> stagePlan(AgentJobEntity job,
      List<AgentJobFileEntity> jobFiles, Map<Long, FileClassifyResult> classResults,
      FolderOrganizeResult folderResult) {

    String prefix = "agent_jobs/" + job.getId() + "/organized/";
    job.setOrganizedPrefix(prefix);
    jobRepo.save(job);

    double reviewThreshold = folderResult.reviewThreshold() > 0 ? folderResult.reviewThreshold() : 0.5;
    String namingRule = folderResult.namingRule() != null ? folderResult.namingRule() : "{filename}";

    // Build placement rule map: condition -> targetFolder
    Map<String, String> ruleMap = new LinkedHashMap<>();
    if (folderResult.placementRules() != null) {
      for (PlacementRule rule : folderResult.placementRules()) {
        ruleMap.put(rule.condition(), rule.targetFolder());
      }
    }

    // Track used target paths for conflict detection
    Map<String, Integer> usedPaths = new HashMap<>();
    // Track sha256 for duplicate detection
    Map<String, String> sha256Groups = new HashMap<>();

    List<AgentOrganizePlanEntity> plans = new ArrayList<>();
    for (AgentJobFileEntity jf : jobFiles) {
      FileClassifyResult cr = classResults.getOrDefault(jf.getId(),
          new FileClassifyResult("other","",List.of(),List.of(),"",null,0,""));

      // Determine target folder from placement rules
      String targetFolder = sanitizeFolderPath(matchPlacementRule(cr, ruleMap, folderResult.folderSchema()));

      // Determine new filename from naming rule
      String newFilename = applyNamingRule(namingRule, cr, jf);
      newFilename = sanitizeFilename(newFilename);

      // Duplicate detection
      String dupGroupId = null;
      if (jf.getSha256() != null && !jf.getSha256().isBlank()) {
        String existing = sha256Groups.get(jf.getSha256());
        if (existing != null) {
          dupGroupId = "dup_" + jf.getSha256().substring(0, 8);
          targetFolder = sanitizeFolderPath("重复文件");
        } else {
          sha256Groups.put(jf.getSha256(), jf.getFilename());
        }
      }

      // Review flag
      boolean reviewFlag = cr.confidence() < reviewThreshold;
      String reviewReason = null;
      if (reviewFlag) reviewReason = "置信度 " + String.format("%.2f", cr.confidence()) + " < " + reviewThreshold;
      if (cr.docKind() == null || "other".equals(cr.docKind())) {
        if (!reviewFlag) { reviewFlag = true; reviewReason = "无法确定文档类型"; }
      }
      if (reviewFlag && dupGroupId == null) targetFolder = sanitizeFolderPath("待确认");

      // Conflict resolution: append sequence number if path already used
      String fullPath = buildRelativePath(targetFolder, newFilename);
      int count = usedPaths.getOrDefault(fullPath, 0);
      if (count > 0) {
        String base = newFilename.contains(".") ? newFilename.substring(0, newFilename.lastIndexOf('.')) : newFilename;
        String ext = newFilename.contains(".") ? newFilename.substring(newFilename.lastIndexOf('.')) : "";
        newFilename = base + "_" + count + ext;
        fullPath = buildRelativePath(targetFolder, newFilename);
      }
      usedPaths.put(fullPath, count + 1);

      String targetKey = prefix + buildRelativePath(targetFolder, newFilename);

      AgentOrganizePlanEntity plan = new AgentOrganizePlanEntity();
      plan.setJob(job);
      plan.setJobFile(jf);
      plan.setSourceObjectKey(jf.getObjectKey());
      plan.setTargetObjectKey(targetKey);
      plan.setNewFilename(newFilename);
      plan.setTargetFolder(targetFolder);
      plan.setDocKind(cr.docKind());
      plan.setTopic(cr.topic());
      plan.setConfidence(cr.confidence());
      plan.setDecisionSource("ai");
      plan.setReviewFlag(reviewFlag);
      plan.setReviewReason(reviewReason);
      plan.setDuplicateGroupId(dupGroupId);
      plans.add(planRepo.save(plan));
    }
    return plans;
  }

  // ==================== Stage 5b: Apply ====================
  private void stageApply(AgentJobEntity job, List<AgentOrganizePlanEntity> plans) {
    int total = plans.size();
    if (total == 0) return;

    record ApplyTask(Long planId, String sourceObjectKey, String targetObjectKey, boolean alreadyApplied) {}
    record ApplyResult(Long planId, boolean success, String errorMessage) {}

    Map<Long, AgentOrganizePlanEntity> planMap = new HashMap<>();
    List<ApplyTask> tasks = new ArrayList<>();
    for (AgentOrganizePlanEntity plan : plans) {
      planMap.put(plan.getId(), plan);
      tasks.add(new ApplyTask(plan.getId(), plan.getSourceObjectKey(), plan.getTargetObjectKey(), plan.isApplied()));
    }

    ExecutorCompletionService<ApplyResult> cs = new ExecutorCompletionService<>(docExecutor);
    for (ApplyTask task : tasks) {
      cs.submit(() -> {
        if (task.alreadyApplied()) {
          return new ApplyResult(task.planId(), true, null);
        }
        try {
          storage.copyObject(task.sourceObjectKey(), task.targetObjectKey());
          return new ApplyResult(task.planId(), true, null);
        } catch (Exception e) {
          return new ApplyResult(task.planId(), false, e.getMessage());
        }
      });
    }

    for (int i = 0; i < total; i++) {
      try {
        ApplyResult r = cs.take().get();
        AgentOrganizePlanEntity plan = planMap.get(r.planId());
        if (plan != null) {
          if (r.success()) {
            plan.setApplied(true);
          } else {
            log.warn("Copy failed: {} -> {}: {}", plan.getSourceObjectKey(), plan.getTargetObjectKey(), r.errorMessage());
            plan.setReviewFlag(true);
            plan.setReviewReason("复制失败: " + r.errorMessage());
          }
          planRepo.save(plan);
        }
      } catch (Exception e) {
        log.warn("apply task error", e);
      }
      setStep(job, "DELIVER", 82 + (int)(8.0 * (i + 1) / Math.max(1, total)), null);
    }
  }

  // ==================== Stage 5c: Deliver ====================
  private void stageDeliver(AgentJobEntity job, List<AgentOrganizePlanEntity> plans,
      FolderOrganizeResult folderResult, Map<Long, FileClassifyResult> classResults,
      List<AgentJobFileEntity> jobFiles) throws Exception {
    String prefix = job.getOrganizedPrefix();

    // Pre-build lookup: jobFileId -> filename (avoid lazy loading in loops)
    Map<Long, String> jfNames = new HashMap<>();
    for (AgentJobFileEntity jf : jobFiles) {
      jfNames.put(jf.getId(), jf.getFilename());
    }

    // 1) Generate index.csv
    StringBuilder csv = new StringBuilder();
    csv.append("原文件名,目标目录,新文件名,文档类型,主题,标签,关键词,摘要,年份,置信度,需确认,确认原因,重复组\n");
    for (AgentOrganizePlanEntity p : plans) {
      Long jfId = p.getJobFile().getId();
      String origName = jfNames.getOrDefault(jfId, "unknown");
      FileClassifyResult cr = classResults.getOrDefault(jfId,
          new FileClassifyResult("other","",List.of(),List.of(),"",null,0,""));
      csv.append(csvEscape(origName)).append(',');
      csv.append(csvEscape(p.getTargetFolder())).append(',');
      csv.append(csvEscape(p.getNewFilename())).append(',');
      csv.append(csvEscape(cr.docKind())).append(',');
      csv.append(csvEscape(cr.topic())).append(',');
      csv.append(csvEscape(String.join(";", cr.subjectTags() != null ? cr.subjectTags() : List.of()))).append(',');
      csv.append(csvEscape(String.join(";", cr.keywords() != null ? cr.keywords() : List.of()))).append(',');
      csv.append(csvEscape(safe(cr.summaryZh(), 100))).append(',');
      csv.append(csvEscape(cr.year())).append(',');
      csv.append(String.format("%.2f", cr.confidence())).append(',');
      csv.append(p.isReviewFlag() ? "是" : "否").append(',');
      csv.append(csvEscape(p.getReviewReason())).append(',');
      csv.append(csvEscape(p.getDuplicateGroupId())).append('\n');
    }
    storage.putBytes(prefix + "index.csv", csv.toString().getBytes(StandardCharsets.UTF_8), "text/csv; charset=utf-8");

    // 2) Generate README.md
    long reviewCount = plans.stream().filter(AgentOrganizePlanEntity::isReviewFlag).count();
    long dupCount = plans.stream().filter(p -> p.getDuplicateGroupId() != null).count();
    StringBuilder readme = new StringBuilder();
    readme.append("# AI 智能整理报告\n\n");
    readme.append("- 主题: ").append(folderResult.folderTopic()).append('\n');
    readme.append("- 标签: ").append(String.join(", ", folderResult.folderTags() != null ? folderResult.folderTags() : List.of())).append('\n');
    readme.append("- 分组策略: ").append(folderResult.groupingStrategy()).append('\n');
    readme.append("- 命名规则: ").append(folderResult.namingRule()).append('\n');
    readme.append("- 确认阈值: ").append(folderResult.reviewThreshold()).append('\n');
    readme.append("- 文件总数: ").append(plans.size()).append('\n');
    readme.append("- 需人工确认: ").append(reviewCount).append('\n');
    readme.append("- 重复文件: ").append(dupCount).append('\n');
    readme.append("- AI 模型: ").append(aiProvider.name()).append(" / ").append(aiProvider.model()).append('\n');
    readme.append("\n## 目录结构\n\n");
    if (folderResult.folderSchema() != null) {
      for (String dir : folderResult.folderSchema()) readme.append("- ").append(dir).append('\n');
    }
    if (reviewCount > 0) {
      readme.append("\n## 待确认文件\n\n");
      for (AgentOrganizePlanEntity p : plans) {
        if (p.isReviewFlag()) {
          String origName = jfNames.getOrDefault(p.getJobFile().getId(), "unknown");
          readme.append("- `").append(origName).append("` → 原因: ").append(p.getReviewReason()).append('\n');
        }
      }
    }
    storage.putBytes(prefix + "README.md", readme.toString().getBytes(StandardCharsets.UTF_8), "text/markdown; charset=utf-8");

    // 3) Generate ZIP
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
      // Add index.csv and README.md
      zos.putNextEntry(new ZipEntry("index.csv"));
      zos.write(csv.toString().getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();
      zos.putNextEntry(new ZipEntry("README.md"));
      zos.write(readme.toString().getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();

      // Add organized files (concurrent prefetch + single-thread zip write).
      // If the organized object copy failed, fall back to the source object so the delivery ZIP stays complete.
      record ZipReadTask(Long planId, String targetObjectKey, String sourceObjectKey, String zipPath, boolean preferTarget) {}
      record ZipReadResult(Long planId, String zipPath, byte[] data, String objectKey, String errorMessage) {}

      List<ZipReadTask> zipTasks = new ArrayList<>();
      for (AgentOrganizePlanEntity p : plans) {
        zipTasks.add(new ZipReadTask(
            p.getId(),
            p.getTargetObjectKey(),
            p.getSourceObjectKey(),
            buildRelativePath(p.getTargetFolder(), p.getNewFilename()),
            p.isApplied()
        ));
      }

      ExecutorCompletionService<ZipReadResult> zipCs = new ExecutorCompletionService<>(docExecutor);
      for (ZipReadTask t : zipTasks) {
        zipCs.submit(() -> {
          try {
            if (t.preferTarget()) {
              try {
                byte[] data = storage.getBytes(t.targetObjectKey());
                return new ZipReadResult(t.planId(), t.zipPath(), data, t.targetObjectKey(), null);
              } catch (Exception ignored) {
                // Fall through to the original source object.
              }
            }
            byte[] data = storage.getBytes(t.sourceObjectKey());
            return new ZipReadResult(t.planId(), t.zipPath(), data, t.sourceObjectKey(), null);
          } catch (Exception e) {
            String failedKey = t.preferTarget() ? t.targetObjectKey() : t.sourceObjectKey();
            return new ZipReadResult(t.planId(), t.zipPath(), null, failedKey, e.getMessage());
          }
        });
      }

      for (int i = 0; i < zipTasks.size(); i++) {
        try {
          ZipReadResult r = zipCs.take().get();
          if (r.data() == null) {
            log.warn("Failed to add to zip: {} ({})", r.objectKey(), r.errorMessage());
            continue;
          }
          zos.putNextEntry(new ZipEntry(r.zipPath()));
          zos.write(r.data());
          zos.closeEntry();
        } catch (Exception e) {
          log.warn("Failed to collect zip payload", e);
        }
      }
    }
    String zipKey = prefix + "organized.zip";
    storage.putBytes(zipKey, baos.toByteArray(), "application/zip");
    job.setZipObjectKey(zipKey);
    jobRepo.save(job);

    // 4) Persist result JSON
    var resultMap = new LinkedHashMap<String, Object>();
    resultMap.put("provider", aiProvider.name());
    resultMap.put("model", aiProvider.model());
    resultMap.put("folderTopic", folderResult.folderTopic());
    resultMap.put("folderTags", folderResult.folderTags());
    resultMap.put("groupingStrategy", folderResult.groupingStrategy());
    resultMap.put("namingRule", folderResult.namingRule());
    resultMap.put("totalFiles", plans.size());
    resultMap.put("reviewCount", reviewCount);
    resultMap.put("duplicateCount", dupCount);
    resultMap.put("zipObjectKey", zipKey);
    var fileList = plans.stream().map(p -> {
      var m = new LinkedHashMap<String, Object>();
      m.put("originalName", jfNames.getOrDefault(p.getJobFile().getId(), "unknown"));
      m.put("targetFolder", p.getTargetFolder());
      m.put("newFilename", p.getNewFilename());
      m.put("docKind", p.getDocKind());
      m.put("topic", p.getTopic());
      m.put("confidence", p.getConfidence());
      m.put("reviewFlag", p.isReviewFlag());
      m.put("reviewReason", p.getReviewReason());
      m.put("duplicateGroupId", p.getDuplicateGroupId());
      m.put("applied", p.isApplied());
      return m;
    }).toList();
    resultMap.put("files", fileList);

    AgentResultEntity result = resultRepo.findByJob_Id(job.getId()).orElseGet(AgentResultEntity::new);
    result.setJob(job);
    result.setTopic(folderResult.folderTopic());
    result.setTagsJson(om.writeValueAsString(folderResult.folderTags()));
    result.setResultJson(om.writeValueAsString(resultMap));
    resultRepo.save(result);
  }

  // ==================== Helper methods ====================

  private void setStep(AgentJobEntity job, String step, int progress, String detail) {
    job.setCurrentStep(step);
    job.setProgress(Math.max(0, Math.min(100, progress)));
    if (detail != null) job.setStepDetail(detail);
    jobRepo.save(job);
  }

  private String matchPlacementRule(FileClassifyResult cr, Map<String, String> rules, List<String> schema) {
    String docKind = cr.docKind() == null || cr.docKind().isBlank() ? "other" : cr.docKind();
    // Simple rule matching: check if condition contains docKind or topic match
    for (var entry : rules.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isBlank()) continue;
      String cond = entry.getKey().toLowerCase(Locale.ROOT);
      if (cond.contains("dockind==" + docKind.toLowerCase(Locale.ROOT))) return entry.getValue();
      if (cr.topic() != null && !cr.topic().isBlank() && cond.contains("topic==" + cr.topic().toLowerCase(Locale.ROOT)))
        return entry.getValue();
    }
    // Fallback: use first schema entry that loosely matches docKind
    if (schema != null) {
      for (String dir : schema) {
        if (dir == null || dir.isBlank()) continue;
        String dl = dir.toLowerCase(Locale.ROOT);
        if (dl.contains(docKind.toLowerCase(Locale.ROOT))) return dir;
      }
    }
    return "其他";
  }

  private String applyNamingRule(String rule, FileClassifyResult cr, AgentJobFileEntity jf) {
    String ext = jf.getExt() != null ? "." + jf.getExt() : "";
    String base = jf.getFilename().contains(".") ? jf.getFilename().substring(0, jf.getFilename().lastIndexOf('.')) : jf.getFilename();
    String result = rule;
    result = result.replace("{year}", cr.year() != null ? cr.year() : "");
    result = result.replace("{topic}", cr.topic() != null ? cr.topic() : "");
    result = result.replace("{docKind}", cr.docKind() != null ? cr.docKind() : "");
    result = result.replace("{filename}", base);
    result = result.replace("{shortTitle}", safe(base, 40));
    result = result.replace("{firstAuthor}", ""); // Not always available
    // Clean up multiple underscores
    result = result.replaceAll("_+", "_").replaceAll("^_|_$", "");
    if (result.isBlank()) result = base;
    return result + ext;
  }

  private String sanitizeFilename(String name) {
    String s = name == null ? "" : name;
    s = UNSAFE_CHARS.matcher(s).replaceAll("_");
    s = s.replaceAll("\\.\\.+", "_");
    s = s.replaceAll("\\s+", " ").trim();
    s = s.replaceAll("^[\\.\\s]+|[\\.\\s]+$", "");
    if (s.length() > 200) {
      String ext = s.contains(".") ? s.substring(s.lastIndexOf('.')) : "";
      s = s.substring(0, 200 - ext.length()) + ext;
    }
    return s.isBlank() ? "unnamed" : s;
  }

  private String sanitizeFolderPath(String path) {
    if (path == null || path.isBlank()) return "其他";
    String normalized = path.replace('\\', '/');
    List<String> parts = new ArrayList<>();
    for (String raw : normalized.split("/+")) {
      String part = sanitizePathPart(raw);
      if (part != null) parts.add(part);
    }
    return parts.isEmpty() ? "其他" : String.join("/", parts);
  }

  private String sanitizePathPart(String part) {
    if (part == null) return null;
    String s = UNSAFE_CHARS.matcher(part).replaceAll("_");
    s = s.replaceAll("\\.\\.+", "_");
    s = s.replaceAll("\\s+", " ").trim();
    s = s.replaceAll("^[\\.\\s]+|[\\.\\s]+$", "");
    if (s.isBlank() || ".".equals(s) || "..".equals(s)) return null;
    return s.length() > 80 ? s.substring(0, 80) : s;
  }

  private String buildRelativePath(String folder, String filename) {
    String cleanFolder = sanitizeFolderPath(folder);
    String cleanFilename = sanitizeFilename(filename);
    return cleanFolder + "/" + cleanFilename;
  }

  private static String extractExt(String filename) {
    if (filename == null) return "";
    int dot = filename.lastIndexOf('.');
    return dot >= 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
  }

  private static String guessTitle(String text, String filename) {
    if (text == null || text.isBlank()) return filename;
    // Take first non-empty line as title candidate
    String[] lines = text.split("\n");
    for (String line : lines) {
      String trimmed = line.trim();
      if (!trimmed.isEmpty() && trimmed.length() > 3 && trimmed.length() < 200) return trimmed;
    }
    return filename;
  }

  private static String extractAbstract(String text) {
    if (text == null) return "";
    String lower = text.toLowerCase(Locale.ROOT);
    int idx = lower.indexOf("abstract");
    if (idx < 0) idx = lower.indexOf("摘要");
    if (idx >= 0) {
      String sub = text.substring(idx, Math.min(text.length(), idx + 1000));
      // Find end of abstract (next section heading or double newline)
      int end = sub.indexOf("\n\n");
      if (end < 0) end = sub.length();
      return sub.substring(0, Math.min(end, 500));
    }
    return safe(text, 300);
  }

  private static List<String> extractHeadings(String text) {
    if (text == null) return List.of();
    List<String> headings = new ArrayList<>();
    for (String line : text.split("\n")) {
      String t = line.trim();
      // Heuristic: short lines that look like headings
      if (t.length() > 2 && t.length() < 100) {
        if (t.matches("^\\d+[\\.、]\\s*.+") || t.matches("^第.+[章节].*") || t.matches("^[A-Z][A-Z\\s]+$")
            || t.startsWith("# ") || t.startsWith("## ")) {
          headings.add(t);
          if (headings.size() >= 20) break;
        }
      }
    }
    return headings;
  }

  private static Map<String, String> guessMetadata(String text, String filename) {
    Map<String, String> meta = new LinkedHashMap<>();
    if (text == null) return meta;
    // Try to find DOI
    var doiMatcher = Pattern.compile("10\\.\\d{4,}/[^\\s]+").matcher(text);
    if (doiMatcher.find()) meta.put("doi", doiMatcher.group());
    // Try to find year
    var yearMatcher = Pattern.compile("(19|20)\\d{2}").matcher(text.substring(0, Math.min(text.length(), 2000)));
    if (yearMatcher.find()) meta.put("year", yearMatcher.group());
    meta.put("filename", filename);
    return meta;
  }

  private static String safe(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max);
  }

  private static String csvEscape(String s) {
    if (s == null) return "";
    if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
      return "\"" + s.replace("\"", "\"\"") + "\"";
    }
    return s;
  }

  static FolderOrganizeResult normalizeFolderOrganizeResult(
      FolderOrganizeResult result, List<FileClassifySummary> summaries) {
    FolderOrganizeResult fallback = buildLocalFallbackOrganizeResult(summaries);
    if (result == null) return fallback;

    String folderTopic = isBlank(result.folderTopic()) ? fallback.folderTopic() : result.folderTopic().trim();
    List<String> folderTags = sanitizedStrings(result.folderTags(), fallback.folderTags());
    String groupingStrategy = isBlank(result.groupingStrategy())
        ? fallback.groupingStrategy()
        : result.groupingStrategy().trim();
    List<String> folderSchema = sanitizedStrings(result.folderSchema(), fallback.folderSchema());
    List<PlacementRule> placementRules = sanitizePlacementRules(result.placementRules(), fallback.placementRules());
    String namingRule = isBlank(result.namingRule()) ? fallback.namingRule() : result.namingRule().trim();
    double reviewThreshold = result.reviewThreshold() >= 0 && result.reviewThreshold() <= 1
        ? result.reviewThreshold()
        : fallback.reviewThreshold();

    return new FolderOrganizeResult(
        folderTopic,
        folderTags,
        groupingStrategy,
        folderSchema,
        placementRules,
        namingRule,
        reviewThreshold
    );
  }

  static FolderOrganizeResult buildLocalFallbackOrganizeResult(List<FileClassifySummary> summaries) {
    Map<String, Long> topicCounts = new LinkedHashMap<>();
    LinkedHashSet<String> tags = new LinkedHashSet<>();
    for (FileClassifySummary summary : summaries) {
      if (!isBlank(summary.topic())) {
        topicCounts.merge(summary.topic().trim(), 1L, Long::sum);
      }
      if (summary.subjectTags() != null) {
        for (String tag : summary.subjectTags()) {
          if (!isBlank(tag)) tags.add(tag.trim());
        }
      }
    }

    String folderTopic = topicCounts.entrySet().stream()
        .max(Map.Entry.comparingByValue())
        .map(Map.Entry::getKey)
        .orElse("智能整理结果");

    List<String> folderTags = tags.isEmpty()
        ? List.of("自动整理", "本地兜底")
        : tags.stream().limit(8).toList();

    return new FolderOrganizeResult(
        folderTopic,
        folderTags,
        "docKind",
        List.of("论文", "教学资料", "代码与实验", "数据", "行政材料", "其他", "待确认"),
        List.of(
            new PlacementRule("docKind==paper", "论文"),
            new PlacementRule("docKind==teaching", "教学资料"),
            new PlacementRule("docKind==code", "代码与实验"),
            new PlacementRule("docKind==data", "数据"),
            new PlacementRule("docKind==admin", "行政材料"),
            new PlacementRule("docKind==other", "其他")
        ),
        "{topic}_{filename}",
        0.5
    );
  }

  private static List<String> sanitizedStrings(List<String> values, List<String> fallback) {
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    if (values != null) {
      for (String value : values) {
        if (!isBlank(value)) normalized.add(value.trim());
      }
    }
    return normalized.isEmpty() ? fallback : List.copyOf(normalized);
  }

  private static List<PlacementRule> sanitizePlacementRules(List<PlacementRule> rules, List<PlacementRule> fallback) {
    List<PlacementRule> normalized = new ArrayList<>();
    if (rules != null) {
      for (PlacementRule rule : rules) {
        if (rule == null || isBlank(rule.condition()) || isBlank(rule.targetFolder())) continue;
        normalized.add(new PlacementRule(rule.condition().trim(), rule.targetFolder().trim()));
      }
    }
    return normalized.isEmpty() ? fallback : List.copyOf(normalized);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
