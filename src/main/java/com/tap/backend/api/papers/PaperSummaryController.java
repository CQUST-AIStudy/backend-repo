package com.tap.backend.api.papers;

import com.tap.backend.audit.AuditAction;
import com.tap.backend.audit.AuditService;
import com.tap.backend.security.PrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.summary.SummaryService;
import com.tap.common.api.ApiResponse;
import com.tap.common.api.Maps;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/papers")
public class PaperSummaryController {
  private final SummaryService summaryService;
  private final AuditService auditService;
  private final PrincipalResolver principalResolver;

  public PaperSummaryController(SummaryService summaryService, AuditService auditService,
      PrincipalResolver principalResolver) {
    this.summaryService = summaryService;
    this.auditService = auditService;
    this.principalResolver = principalResolver;
  }

  @GetMapping("/{arxivId}/summary")
  public ApiResponse<Map<String, Object>> summary(
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request,
      @PathVariable("arxivId") String arxivId,
      @RequestParam(name = "force", defaultValue = "false") boolean force
  ) throws Exception {
    var resolved = principalResolver.resolve(principal);
    var res = summaryService.summarizePaper(resolved.userId(), arxivId, force);
    auditService.record(resolved, AuditAction.SUMMARY_PAPER, "Paper", arxivId,
        Maps.of("provider", res.provider(), "model", res.model(), "charCountZh", res.charCountZh(), "force", force),
        request);
    return ApiResponse.of(Maps.of(
        "scopeType", res.scopeType(),
        "scopeKey", res.scopeKey(),
        "provider", res.provider(),
        "model", res.model(),
        "charCountZh", res.charCountZh(),
        "structured", res.structured(),
        "markdown", res.markdown()
    ));
  }
}
