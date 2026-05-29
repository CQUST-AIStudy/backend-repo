package com.tap.backend.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.agent.AgentJobEntity;
import com.tap.backend.domain.agent.AgentJobStatus;
import com.tap.backend.domain.agent.AgentResultEntity;
import com.tap.backend.domain.upload.UploadFolderEntity;
import com.tap.backend.infra.storage.ObjectStorageService;
import com.tap.backend.repo.AgentJobRepository;
import com.tap.backend.repo.AgentResultRepository;
import com.tap.backend.repo.UploadFolderRepository;
import com.tap.backend.service.UserService;
import com.tap.backend.audit.AuditAction;
import com.tap.backend.audit.AuditService;
import com.tap.backend.security.PrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.common.api.ApiResponse;
import com.tap.common.api.Maps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/jobs")
public class AgentJobController {
  private final UserService userService;
  private final UploadFolderRepository uploadFolderRepository;
  private final AgentJobRepository agentJobRepository;
  private final AgentResultRepository agentResultRepository;
  private final ObjectMapper objectMapper;
  private final AuditService auditService;
  private final PrincipalResolver principalResolver;
  private final ObjectStorageService objectStorageService;

  public AgentJobController(UserService userService,
      UploadFolderRepository uploadFolderRepository,
      AgentJobRepository agentJobRepository,
      AgentResultRepository agentResultRepository,
      ObjectMapper objectMapper,
      AuditService auditService,
      PrincipalResolver principalResolver,
      ObjectStorageService objectStorageService) {
    this.userService = userService;
    this.uploadFolderRepository = uploadFolderRepository;
    this.agentJobRepository = agentJobRepository;
    this.agentResultRepository = agentResultRepository;
    this.objectMapper = objectMapper;
    this.auditService = auditService;
    this.principalResolver = principalResolver;
    this.objectStorageService = objectStorageService;
  }

  public record CreateJobRequest(@NotNull Long uploadFolderId) {}
  public record JobSummary(
      Long id,
      Long uploadFolderId,
      String status,
      Integer progress,
      String currentStep,
      String errorMessage,
      Boolean hasZip,
      Instant createdAt,
      Instant finishedAt
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

    List<AgentJobEntity> jobs = agentJobRepository.findTop30ByUser_IdOrderByCreatedAtDesc(user.getId());
    List<JobSummary> items = new ArrayList<>();
    for (AgentJobEntity job : jobs) {
      if (items.size() >= safeLimit) break;
      items.add(new JobSummary(
          job.getId(),
          job.getUploadFolderId(),
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
      @Valid @RequestBody CreateJobRequest req
  ) {
    var resolved = principalResolver.resolve(principal);
    var user = userService.requireById(resolved.userId());
    UploadFolderEntity folder = uploadFolderRepository.findByIdAndUser_Id(req.uploadFolderId(), user.getId())
        .orElseThrow(() -> new IllegalArgumentException("upload folder not found"));

    AgentJobEntity job = new AgentJobEntity();
    job.setUser(user);
    job.setUploadFolder(folder);
    job.setStatus(AgentJobStatus.PENDING);
    job.setProgress(0);
    job.setRetryCount(0);
    job.setStartedAt(null);
    job.setFinishedAt(null);
    job = agentJobRepository.save(job);
    auditService.record(resolved, AuditAction.AGENT_SUBMIT, "AgentJob", String.valueOf(job.getId()),
        Maps.of("uploadFolderId", folder.getId()), request);
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
    AgentJobEntity job = agentJobRepository.findById(jobId)
        .orElseThrow(() -> new IllegalArgumentException("job not found"));
    if (!job.getUser().getId().equals(user.getId())) throw new IllegalArgumentException("job not owned by user");

    AgentResultEntity result = agentResultRepository.findByJob_Id(jobId).orElse(null);
    JsonNode resultJson = null;
    if (result != null && result.getResultJson() != null) {
      resultJson = objectMapper.readTree(result.getResultJson());
    }
    var map = new java.util.HashMap<String, Object>();
    map.put("id", job.getId());
    map.put("uploadFolderId", job.getUploadFolder().getId());
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
    AgentJobEntity job = agentJobRepository.findById(jobId)
        .orElseThrow(() -> new IllegalArgumentException("job not found"));
    if (!job.getUser().getId().equals(user.getId())) throw new IllegalArgumentException("job not owned by user");
    if (job.getStatus() == AgentJobStatus.RUNNING) throw new IllegalArgumentException("job is running");
    if (job.getRetryCount() >= 3) throw new IllegalArgumentException("job retry limit reached");

    agentResultRepository.findByJob_Id(jobId).ifPresent(agentResultRepository::delete);

    job.setRetryCount(job.getRetryCount() + 1);
    job.setStatus(AgentJobStatus.PENDING);
    job.setProgress(0);
    job.setErrorMessage(null);
    job.setStartedAt(null);
    job.setFinishedAt(null);
    job.setCurrentStep(null);
    job.setStepDetail(null);
    job.setOrganizedPrefix(null);
    job.setZipObjectKey(null);
    agentJobRepository.save(job);

    auditService.record(resolved, AuditAction.AGENT_RETRY, "AgentJob", String.valueOf(job.getId()),
        Maps.of("uploadFolderId", job.getUploadFolder().getId(), "retryCount", job.getRetryCount()), request);
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
    AgentJobEntity job = agentJobRepository.findById(jobId)
        .orElseThrow(() -> new IllegalArgumentException("job not found"));
    if (!job.getUser().getId().equals(user.getId())) throw new IllegalArgumentException("job not owned by user");
    if (job.getStatus() == AgentJobStatus.SUCCEEDED || job.getStatus() == AgentJobStatus.FAILED) {
      throw new IllegalArgumentException("job already finished");
    }
    job.setStatus(AgentJobStatus.CANCELLED);
    job.setFinishedAt(Instant.now());
    agentJobRepository.save(job);
    auditService.record(resolved, AuditAction.AGENT_CANCEL, "AgentJob", String.valueOf(job.getId()),
        Maps.of("uploadFolderId", job.getUploadFolder().getId()), request);
    return ApiResponse.of(Maps.of("jobId", job.getId(), "status", job.getStatus()));
  }

  @GetMapping("/{jobId}/download")
  public void download(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable("jobId") long jobId,
      HttpServletResponse response
  ) throws Exception {
    var resolved = principalResolver.resolve(principal);
    var user = userService.requireById(resolved.userId());
    AgentJobEntity job = agentJobRepository.findById(jobId)
        .orElseThrow(() -> new IllegalArgumentException("job not found"));
    if (!job.getUser().getId().equals(user.getId())) throw new IllegalArgumentException("job not owned by user");
    if (job.getZipObjectKey() == null) throw new IllegalArgumentException("整理结果尚未生成");

    byte[] zipBytes = objectStorageService.getBytes(job.getZipObjectKey());
    response.setContentType("application/zip");
    response.setHeader("Content-Disposition", "attachment; filename=\"organized_" + jobId + ".zip\"");
    response.setContentLength(zipBytes.length);
    response.getOutputStream().write(zipBytes);
    response.getOutputStream().flush();
  }
}
