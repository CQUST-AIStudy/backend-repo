package com.tap.backend.api.documents;

import com.tap.backend.repo.DocumentRepository;
import com.tap.backend.repo.StructuredSummaryRepository;
import com.tap.backend.security.PrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.common.api.ApiResponse;
import com.tap.common.api.Maps;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents")
public class DocumentListController {
  private final DocumentRepository documentRepository;
  private final StructuredSummaryRepository structuredSummaryRepository;
  private final PrincipalResolver principalResolver;

  public DocumentListController(DocumentRepository documentRepository,
      StructuredSummaryRepository structuredSummaryRepository,
      PrincipalResolver principalResolver) {
    this.documentRepository = documentRepository;
    this.structuredSummaryRepository = structuredSummaryRepository;
    this.principalResolver = principalResolver;
  }

  @GetMapping
  public ApiResponse<List<Map<String, Object>>> list(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam(name = "limit", defaultValue = "100") int limit
  ) {
    var resolved = principalResolver.resolve(principal);
    var docs = documentRepository.findAllByUser_IdOrderByCreatedAtDesc(
        resolved.userId(), PageRequest.of(0, Math.min(limit, 200)));
    var result = docs.stream().map(d -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", d.getId());
      m.put("filename", d.getFilename());
      m.put("contentType", d.getContentType() == null ? "" : d.getContentType());
      m.put("sizeBytes", d.getSizeBytes());
      m.put("language", d.getLanguage() == null ? "" : d.getLanguage());
      m.put("createdAt", d.getCreatedAt().toString());
      return m;
    }).toList();
    return ApiResponse.of(result);
  }

  @DeleteMapping("/{docId}")
  @Transactional
  public ApiResponse<Map<String, Object>> delete(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable("docId") long docId
  ) {
    var resolved = principalResolver.resolve(principal);
    var doc = documentRepository.findByIdAndUser_Id(docId, resolved.userId())
        .orElseThrow(() -> new IllegalArgumentException("document not found"));
    // translation_segment has ON DELETE CASCADE, so auto-cleaned
    // structured_summary uses scope_key, clean manually
    structuredSummaryRepository.deleteAllByScopeTypeAndScopeKey("DOCUMENT", String.valueOf(docId));
    documentRepository.delete(doc);
    return ApiResponse.of(Maps.of("id", docId, "deleted", true));
  }

  @DeleteMapping
  @Transactional
  public ApiResponse<Map<String, Object>> deleteAll(
      @AuthenticationPrincipal UserPrincipal principal
  ) {
    var resolved = principalResolver.resolve(principal);
    var docs = documentRepository.findAllByUser_Id(resolved.userId());
    if (docs.isEmpty()) {
      return ApiResponse.of(Maps.of("deletedCount", 0));
    }

    List<String> docIds = docs.stream().map(d -> String.valueOf(d.getId())).toList();
    structuredSummaryRepository.deleteAllByScopeTypeAndScopeKeyIn("DOCUMENT", docIds);
    documentRepository.deleteAllInBatch(docs);
    return ApiResponse.of(Maps.of("deletedCount", docs.size()));
  }
}
