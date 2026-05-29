package com.tap.backend.api.papers;

import com.tap.backend.domain.paper.PaperEntity;
import com.tap.backend.repo.PaperRepository;
import com.tap.backend.security.PrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.summary.DoiFetchService;
import com.tap.backend.summary.SummaryService;
import com.tap.common.api.ApiResponse;
import com.tap.common.api.Maps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/papers")
public class PaperFreeTextController {
  private final SummaryService summaryService;
  private final DoiFetchService doiFetchService;
  private final PaperRepository paperRepository;
  private final PrincipalResolver principalResolver;

  public PaperFreeTextController(SummaryService summaryService, DoiFetchService doiFetchService,
      PaperRepository paperRepository, PrincipalResolver principalResolver) {
    this.summaryService = summaryService;
    this.doiFetchService = doiFetchService;
    this.paperRepository = paperRepository;
    this.principalResolver = principalResolver;
  }

  public record DoiRequest(@NotBlank @Size(max = 256) String doi) {}

  public record FreeTextRequest(
      @NotBlank @Size(max = 512) String title,
      @NotBlank @Size(max = 8000) String text
  ) {}

  /** 通过 DOI 生成精读卡 */
  @PostMapping("/doi/summary")
  public ApiResponse<Map<String, Object>> doiSummary(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody DoiRequest req
  ) throws Exception {
    var resolved = principalResolver.resolve(principal);
    PaperEntity paper = doiFetchService.fetchByDoi(req.doi());
    var res = summaryService.summarizePaper(resolved.userId(), paper.getArxivId(), false);
    return ApiResponse.of(Maps.of(
        "title", paper.getTitle(),
        "provider", res.provider(), "model", res.model(),
        "charCountZh", res.charCountZh(), "markdown", res.markdown()
    ));
  }

  /** 粘贴自由文本生成精读卡 */
  @PostMapping("/freetext/summary")
  public ApiResponse<Map<String, Object>> freetextSummary(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody FreeTextRequest req
  ) throws Exception {
    // 用标题 hash 作为 scopeKey，避免重复存储
    String scopeKey = "text:" + Integer.toHexString(req.title().hashCode());
    PaperEntity paper = paperRepository.findByArxivId(scopeKey).orElseGet(PaperEntity::new);
    paper.setArxivId(scopeKey);
    paper.setTitle(req.title());
    paper.setAbstractText(req.text());
    paper.setAuthors(java.util.List.of());
    paper.setCategories(java.util.List.of());
    paperRepository.save(paper);

    var resolved = principalResolver.resolve(principal);
    var res = summaryService.summarizePaper(resolved.userId(), scopeKey, false);
    return ApiResponse.of(Maps.of(
        "title", req.title(),
        "provider", res.provider(), "model", res.model(),
        "charCountZh", res.charCountZh(), "markdown", res.markdown()
    ));
  }
}
