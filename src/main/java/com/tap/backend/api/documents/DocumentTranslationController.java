package com.tap.backend.api.documents;

import com.tap.backend.audit.AuditAction;
import com.tap.backend.audit.AuditService;
import com.tap.backend.security.PrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.translation.DocumentTranslateService;
import com.tap.common.api.ApiResponse;
import com.tap.common.api.Maps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents")
public class DocumentTranslationController {
  private final DocumentTranslateService translateService;
  private final AuditService auditService;
  private final PrincipalResolver principalResolver;

  public DocumentTranslationController(DocumentTranslateService translateService, AuditService auditService,
      PrincipalResolver principalResolver) {
    this.translateService = translateService;
    this.auditService = auditService;
    this.principalResolver = principalResolver;
  }

  @GetMapping("/{docId}/translate")
  public ApiResponse<Map<String, Object>> translate(
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request,
      @PathVariable("docId") long docId,
      @RequestParam(name = "targetLang", defaultValue = "ZH") @NotBlank String targetLang,
      @RequestParam(name = "mode", defaultValue = "text") String mode,
      @RequestParam(name = "force", defaultValue = "false") boolean force
  ) {
    String m = mode == null ? "text" : mode.trim().toLowerCase();
    if (!"text".equals(m) && !"doc".equals(m)) {
      throw new IllegalArgumentException("unsupported translate mode: " + m);
    }
    var resolved = principalResolver.resolve(principal);
    var res = translateService.translateTextMode(resolved.userId(), docId, targetLang, force);
    long chars = res.segments().stream().mapToLong(s -> s.source() == null ? 0 : s.source().length()).sum();
    auditService.record(resolved, AuditAction.TRANSLATE_TEXT, "Document", String.valueOf(docId),
        Maps.of("targetLang", res.targetLang(), "provider", res.provider(), "segments", res.segments().size(), "chars", chars, "force", force),
        request);
    return ApiResponse.of(Maps.of(
        "documentId", res.documentId(),
        "path", res.path(),
        "targetLang", res.targetLang(),
        "provider", res.provider(),
        "segments", res.segments()
    ));
  }
}
