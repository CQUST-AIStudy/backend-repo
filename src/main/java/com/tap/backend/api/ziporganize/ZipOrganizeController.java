package com.tap.backend.api.ziporganize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.audit.AuditAction;
import com.tap.backend.audit.AuditService;
import com.tap.backend.domain.ziporganize.ZipOrganizeJobEntity;
import com.tap.backend.domain.ziporganize.ZipOrganizeJobStatus;
import com.tap.backend.infra.storage.ObjectStorageService;
import com.tap.backend.repo.ZipOrganizeJobRepository;
import com.tap.backend.security.PrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.UserService;
import com.tap.backend.service.ziporganize.ZipOrganizeService;
import com.tap.common.api.ApiResponse;
import com.tap.common.api.Maps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/zip-organize/jobs")
public class ZipOrganizeController {
  private final UserService userService;
  private final ZipOrganizeJobRepository jobRepo;
  private final ZipOrganizeService zipOrganizeService;
  private final ObjectStorageService storage;
  private final ObjectMapper om;
  private final AuditService auditService;
  private final PrincipalResolver principalResolver;

  public ZipOrganizeController(UserService userService,
      ZipOrganizeJobRepository jobRepo,
      ZipOrganizeService zipOrganizeService,
      ObjectStorageService storage,
      ObjectMapper om,
      AuditService auditService,
      PrincipalResolver principalResolver) {
    this.userService = userService;
    this.jobRepo = jobRepo;
    this.zipOrganizeService = zipOrganizeService;
    this.storage = storage;
    this.om = om;
    this.auditService = auditService;
    this.principalResolver = principalResolver;
  }

  public record JobSummary(
      Long id,
      String status,
      Integer progress,
      String currentStep,
      String errorMessage,
      Boolean hasZip,
      java.time.Instant createdAt,
      java.time.Instant finishedAt
  ) {}

  @GetMapping
  @Transactional(readOnly = true)
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
  ) {
    int safeLimit = Math.max(1, Math.min(30, limit));
    var resolved = principalResolver.resolve(principal);
    var user = userService.requireById(resolved.userId());
    List<ZipOrganizeJobEntity> jobs = jobRepo.findTop30ByUser_IdOrderByCreatedAtDesc(user.getId());
    List<JobSummary> items = new ArrayList<>();
    for (ZipOrganizeJobEntity job : jobs) {
      if (items.size() >= safeLimit) break;
      items.add(new JobSummary(
          job.getId(),
          job.getStatus() == null ? null : job.getStatus().name(),
          job.getProgress(),
          job.getCurrentStep(),
          job.getErrorMessage(),
          job.getZipObjectKey() != null,
          job.getCreatedAt(),
          job.getFinishedAt()
      ));
    }
    return ApiResponse.of(Maps.of("items", items, "count", items.size()));
  }

  @PostMapping
  public ApiResponse<Map<String, Object>> submit(
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request,
      @RequestPart("file") MultipartFile file
  ) throws Exception {
    if (file.isEmpty()) throw new IllegalArgumentException("zip file is empty");
    var resolved = principalResolver.resolve(principal);
    var user = userService.requireById(resolved.userId());
    ZipOrganizeJobEntity job = zipOrganizeService.createJob(user, file.getOriginalFilename(), file.getBytes());
    auditService.record(resolved, AuditAction.ZIP_ORGANIZE_SUBMIT, "ZipOrganizeJob", String.valueOf(job.getId()),
        Maps.of("originalFilename", job.getOriginalFilename(), "bytes", file.getSize()), request);
    return ApiResponse.of(Maps.of("jobId", job.getId(), "status", job.getStatus()));
  }

  @GetMapping("/{jobId}")
  @Transactional(readOnly = true)
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable("jobId") long jobId
  ) throws Exception {
    var resolved = principalResolver.resolve(principal);
    var user = userService.requireById(resolved.userId());
    ZipOrganizeJobEntity job = jobRepo.findById(jobId).orElseThrow(() -> new IllegalArgumentException("job not found"));
    if (!job.getUser().getId().equals(user.getId())) throw new IllegalArgumentException("job not owned by user");
    JsonNode resultJson = null;
    if (job.getResultJson() != null) resultJson = om.readTree(job.getResultJson());
    var map = new java.util.HashMap<String, Object>();
    map.put("id", job.getId());
    map.put("status", job.getStatus());
    map.put("progress", job.getProgress());
    map.put("currentStep", job.getCurrentStep());
    map.put("stepDetail", job.getStepDetail());
    map.put("retryCount", job.getRetryCount());
    map.put("errorMessage", job.getErrorMessage());
    map.put("startedAt", job.getStartedAt());
    map.put("finishedAt", job.getFinishedAt());
    map.put("hasZip", job.getZipObjectKey() != null);
    map.put("result", resultJson);
    return ApiResponse.of(map);
  }

  @PostMapping("/{jobId}/retry")
  @Transactional
  public ApiResponse<Map<String, Object>> retry(
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request,
      @PathVariable("jobId") long jobId
  ) {
    var resolved = principalResolver.resolve(principal);
    var user = userService.requireById(resolved.userId());
    ZipOrganizeJobEntity job = jobRepo.findById(jobId).orElseThrow(() -> new IllegalArgumentException("job not found"));
    if (!job.getUser().getId().equals(user.getId())) throw new IllegalArgumentException("job not owned by user");
    job = zipOrganizeService.retry(job);
    auditService.record(resolved, AuditAction.ZIP_ORGANIZE_RETRY, "ZipOrganizeJob", String.valueOf(job.getId()),
        Maps.of("retryCount", job.getRetryCount()), request);
    return ApiResponse.of(Maps.of("jobId", job.getId(), "status", job.getStatus(), "retryCount", job.getRetryCount()));
  }

  @PostMapping("/{jobId}/cancel")
  @Transactional
  public ApiResponse<Map<String, Object>> cancel(
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request,
      @PathVariable("jobId") long jobId
  ) {
    var resolved = principalResolver.resolve(principal);
    var user = userService.requireById(resolved.userId());
    ZipOrganizeJobEntity job = jobRepo.findById(jobId).orElseThrow(() -> new IllegalArgumentException("job not found"));
    if (!job.getUser().getId().equals(user.getId())) throw new IllegalArgumentException("job not owned by user");
    if (job.getStatus() == ZipOrganizeJobStatus.SUCCEEDED || job.getStatus() == ZipOrganizeJobStatus.FAILED) {
      throw new IllegalArgumentException("job already finished");
    }
    zipOrganizeService.cancel(job);
    auditService.record(resolved, AuditAction.ZIP_ORGANIZE_CANCEL, "ZipOrganizeJob", String.valueOf(job.getId()), null, request);
    return ApiResponse.of(Maps.of("jobId", job.getId(), "status", job.getStatus()));
  }

  @GetMapping("/{jobId}/download")
  public void download(
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request,
      @PathVariable("jobId") long jobId,
      HttpServletResponse response
  ) throws Exception {
    var resolved = principalResolver.resolve(principal);
    var user = userService.requireById(resolved.userId());
    ZipOrganizeJobEntity job = jobRepo.findById(jobId).orElseThrow(() -> new IllegalArgumentException("job not found"));
    if (!job.getUser().getId().equals(user.getId())) throw new IllegalArgumentException("job not owned by user");
    if (job.getZipObjectKey() == null) throw new IllegalArgumentException("整理结果尚未生成");
    byte[] zipBytes = storage.getBytes(job.getZipObjectKey());
    response.setContentType("application/zip");
    response.setHeader("Content-Disposition", "attachment; filename=\"organized_" + jobId + ".zip\"");
    response.setContentLength(zipBytes.length);
    response.getOutputStream().write(zipBytes);
    response.getOutputStream().flush();
    auditService.record(resolved, AuditAction.ZIP_ORGANIZE_DOWNLOAD, "ZipOrganizeJob", String.valueOf(job.getId()),
        Maps.of("bytes", zipBytes.length), request);
  }
}
