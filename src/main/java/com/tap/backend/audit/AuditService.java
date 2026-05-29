package com.tap.backend.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.tracing.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
  private final AuditEventRepository repo;
  private final ObjectMapper objectMapper;

  public AuditService(AuditEventRepository repo, ObjectMapper objectMapper) {
    this.repo = repo;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public void record(UserPrincipal principal, AuditAction action, String targetType, String targetId,
      Map<String, Object> metadata, HttpServletRequest request) {
    AuditEventEntity e = new AuditEventEntity();
    if (principal != null) {
      e.setUserId(principal.userId());
      e.setRole(principal.role().name());
    }
    e.setAction(action.name());
    e.setTargetType(targetType);
    e.setTargetId(targetId);
    e.setTraceId(MDC.get(TraceIdFilter.MDC_KEY));
    if (request != null) {
      e.setIp(request.getRemoteAddr());
      e.setUserAgent(request.getHeader("User-Agent"));
    }
    if (metadata != null) {
      try {
        e.setMetadataJson(objectMapper.writeValueAsString(metadata));
      } catch (Exception ignored) {}
    }
    repo.save(e);
  }
}
