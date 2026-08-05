package com.tap.backend.ai;

import java.util.List;

public interface AiProvider {
  String name();
  default String model() { return ""; }

  /** Stage 3: File-level AI classification — returns executable decision unit */
  FileClassifyResult classifyFile(FileClassifyInput input);

  /** Stage 4: Folder-level AI — returns executable organize strategy */
  FolderOrganizeResult organizeFolder(FolderOrganizeInput input);

  /** Structured summary (unchanged) */
  StructuredSummary structuredSummary(StructuredSummaryInput input);

  /**
   * Raw chat completion. Implementations should call the underlying model and
   * return the text content of the first choice.
   */
  default String chat(String prompt, String modelOverride) {
    throw new UnsupportedOperationException("chat not implemented");
  }

  /** Structured JSON completion for callers that must parse the response. */
  default String chatJson(String prompt, String modelOverride, int maxTokens) {
    return chat(prompt, modelOverride);
  }

  // ---- Legacy compat (delegate to new methods) ----
  default DocumentAiResult classifyAndSummarize(DocumentAiInput input) {
    var r = classifyFile(new FileClassifyInput(input.documentId(), input.path(), input.text()));
    return new DocumentAiResult(r.subjectTags(), r.keywords(), r.summaryZh());
  }
  default FolderAiResult organizeFolder(FolderAiInput input) {
    var docs = input.documents().stream()
        .map(d -> new FileClassifySummary(d.documentId(), d.path(), "other", "", d.subjectTags(), d.keywords(), d.summaryZh(), null, 0.5))
        .toList();
    var r = organizeFolder(new FolderOrganizeInput(input.uploadFolderId(), docs));
    return new FolderAiResult(r.folderTopic(), r.folderTags(), r.folderSchema());
  }

  // ---- Stage 3 records ----
  record FileClassifyInput(long documentId, String path, String text) {}
  record FileClassifyResult(
      String docKind,       // paper/teaching/data/code/admin/other
      String topic,
      List<String> subjectTags,
      List<String> keywords,
      String summaryZh,
      String year,          // nullable
      double confidence,    // 0-1
      String reason
  ) {}

  // ---- Stage 4 records ----
  record FileClassifySummary(long documentId, String path, String docKind, String topic,
      List<String> subjectTags, List<String> keywords, String summaryZh, String year, double confidence) {}
  record FolderOrganizeInput(long uploadFolderId, List<FileClassifySummary> documents) {}
  record FolderOrganizeResult(
      String folderTopic,
      List<String> folderTags,
      String groupingStrategy,       // e.g. "topic > year > docKind"
      List<String> folderSchema,     // directory tree entries
      List<PlacementRule> placementRules,
      String namingRule,             // e.g. "{year}_{firstAuthor}_{shortTitle}"
      double reviewThreshold         // confidence below this → NEED_REVIEW
  ) {}
  record PlacementRule(String condition, String targetFolder) {}

  // ---- Legacy records (kept for backward compat) ----
  record DocumentAiInput(long documentId, String path, String text) {}
  record DocumentAiResult(List<String> subjectTags, List<String> keywords, String summaryZh) {}
  record FolderAiInput(long uploadFolderId, List<DocumentView> documents) {}
  record DocumentView(long documentId, String path, List<String> subjectTags, List<String> keywords, String summaryZh) {}
  record FolderAiResult(String folderTopic, List<String> folderTags, List<String> recommendedStructure) {}

  // ---- Summary records ----
  record StructuredSummaryInput(String scopeType, String scopeKey, String text, int minZhChars, int maxZhChars) {}
  record StructuredSummary(
      String researchProblemMotivation,
      List<String> methods,
      List<String> experimentsData,
      String conclusions,
      List<String> limitationsInsights
  ) {}
}
