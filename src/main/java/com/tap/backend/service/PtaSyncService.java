package com.tap.backend.service;

import com.tap.backend.domain.classroom.TeachingClassEntity;
import com.tap.backend.repo.TeachingClassRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

@Service
public class PtaSyncService {

    private static final Logger log = LoggerFactory.getLogger(PtaSyncService.class);
    private static final Duration COOLDOWN = Duration.ofHours(24);
    private static final String CREDENTIAL_SOURCE_COOKIE = "cookie";
    private static final String CREDENTIAL_SOURCE_BOUND = "bound";
    private static final String CREDENTIAL_SOURCE_TEMPORARY = "temporary";

    private final TeachingClassRepository classRepo;
    private final TeachingClassService teachingClassService;
    private final TeacherPtaCredentialService teacherPtaCredentialService;
    private final RestTemplate restTemplate;

    @Value("${pta.spider-url:http://127.0.0.1:8100}")
    private String spiderUrl;

    public PtaSyncService(
            TeachingClassRepository classRepo,
            TeachingClassService teachingClassService,
            TeacherPtaCredentialService teacherPtaCredentialService,
            @Value("${pta.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${pta.read-timeout-ms:20000}") int readTimeoutMs
    ) {
        this.classRepo = classRepo;
        this.teachingClassService = teachingClassService;
        this.teacherPtaCredentialService = teacherPtaCredentialService;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(1000, connectTimeoutMs));
        requestFactory.setReadTimeout(Math.max(1000, readTimeoutMs));
        this.restTemplate = new RestTemplate(requestFactory);
    }

    @Transactional
    public Map<String, Object> updateSyncConfig(Long classId, Long teacherId, String ptaKeyword, Boolean syncEnabled) {
        TeachingClassEntity teachingClass = requireOwnedClass(classId, teacherId);
        if (ptaKeyword != null) {
            teachingClass.setPtaKeyword(resolvePtaKeyword(teachingClass, ptaKeyword));
        }
        if (syncEnabled != null) {
            teachingClass.setSyncEnabled(syncEnabled);
        }
        classRepo.save(teachingClass);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ptaKeyword", teachingClass.getPtaKeyword());
        result.put("syncEnabled", teachingClass.getSyncEnabled());
        return result;
    }

    @Transactional
    public Map<String, Object> triggerSync(
            Long classId,
            Long teacherId,
            String ptaUsername,
            String ptaPassword,
            String ptaKeyword,
            String mode,
            Boolean force
    ) {
        return doTriggerSync(classId, teacherId, true, ptaUsername, ptaPassword, ptaKeyword, mode, force);
    }

    @Transactional
    public Map<String, Object> triggerSyncScheduled(Long classId) {
        TeachingClassEntity teachingClass = classRepo.findById(classId)
                .orElseThrow(() -> new NoSuchElementException("class not found"));
        return doTriggerSync(teachingClass, false, null, null, null, null, false);
    }

    public Map<String, Object> getSyncStatus(Long classId, Long teacherId) {
        TeachingClassEntity teachingClass = requireOwnedClass(classId, teacherId);
        return toStatusMap(teachingClass);
    }

    @Transactional
    public Map<String, Object> importStudents(Long classId, Long teacherId) {
        return teachingClassService.importStudentsFromPta(classId, teacherId);
    }

    @Transactional
    public void updateSyncResult(Long classId, String status) {
        classRepo.findById(classId).ifPresent(teachingClass -> {
            String effectiveStatus = status;
            if ("SUCCESS".equals(status)) {
                try {
                    teachingClassService.importStudentsFromPta(classId, teachingClass.getTeacherId());
                } catch (Exception ex) {
                    log.error("PTA sync finished but importing class students failed for class {}: {}", classId, ex.getMessage());
                    effectiveStatus = "FAILED";
                }
            }

            teachingClass.setSyncStatus(effectiveStatus);
            if ("SUCCESS".equals(effectiveStatus) || "FAILED".equals(effectiveStatus)) {
                teachingClass.setLastSyncAt(Instant.now());
            }
            classRepo.save(teachingClass);
        });
    }

    public List<TeachingClassEntity> listSyncEnabledClasses() {
        return classRepo.findAll().stream()
                .filter(teachingClass -> Boolean.TRUE.equals(teachingClass.getSyncEnabled())
                        && teachingClass.getPtaKeyword() != null
                        && !teachingClass.getPtaKeyword().isBlank())
                .toList();
    }

    private Map<String, Object> doTriggerSync(
            Long classId,
            Long teacherId,
            boolean checkCooldown,
            String ptaUsername,
            String ptaPassword,
            String ptaKeyword,
            String mode,
            Boolean force
    ) {
        return doTriggerSync(requireOwnedClass(classId, teacherId), checkCooldown, ptaUsername, ptaPassword, ptaKeyword, mode, force);
    }

    private Map<String, Object> doTriggerSync(
            TeachingClassEntity teachingClass,
            boolean checkCooldown,
            String ptaUsername,
            String ptaPassword,
            String ptaKeyword,
            String mode,
            Boolean force
    ) {
        if (ptaKeyword != null) {
            teachingClass.setPtaKeyword(resolvePtaKeyword(teachingClass, ptaKeyword));
            classRepo.save(teachingClass);
        }
        if (teachingClass.getPtaKeyword() == null || teachingClass.getPtaKeyword().isBlank()) {
            throw new IllegalStateException("pta keyword is required before sync");
        }

        ResolvedSyncCredential resolvedCredential =
                resolveCredential(teachingClass.getTeacherId(), ptaUsername, ptaPassword);
        TeacherPtaCredentialService.ResolvedPtaCredential credential = resolvedCredential.credential();
        String credentialSource = resolvedCredential.source();

        boolean bypassCooldown = Boolean.TRUE.equals(force);
        if (checkCooldown && !bypassCooldown && teachingClass.getLastSyncAt() != null) {
            Duration since = Duration.between(teachingClass.getLastSyncAt(), Instant.now());
            if (since.compareTo(COOLDOWN) < 0) {
                long remainingHours = COOLDOWN.minus(since).toHours();
                long remainingMinutes = COOLDOWN.minus(since).toMinutes() % 60;
                String message = remainingHours > 0
                        ? "sync cooldown active, retry in " + remainingHours + "h " + remainingMinutes + "m"
                        : "sync cooldown active, retry in " + remainingMinutes + "m";
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("syncStatus", teachingClass.getSyncStatus());
                result.put("blocked", true);
                result.put("message", message);
                result.put("cooldown", true);
                result.put("credentialSource", credentialSource);
                result.put("remainingHours", remainingHours);
                result.put("remainingMinutes", remainingMinutes);
                return result;
            }
        }

        String previousStatus = teachingClass.getSyncStatus();
        teachingClass.setSyncStatus("RUNNING");
        classRepo.save(teachingClass);

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("keyword", teachingClass.getPtaKeyword());
            body.put("class_id", teachingClass.getId().intValue());
            if (mode != null && !mode.isBlank()) {
                body.put("mode", mode.trim());
            }
            if (Boolean.TRUE.equals(force)) {
                body.put("force", true);
                // In local/dev demos, "force" should visibly exercise the PTA browser login flow.
                body.put("force_selenium_login", true);
                body.put("headless", false);
            }
            body.put("credential_source", credentialSource);
            if (credential != null) {
                body.put("username", credential.username());
                body.put("password", credential.password());
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(spiderUrl + "/crawl", entity, Map.class);

            Map<String, Object> result = new LinkedHashMap<>();
            Map<?, ?> responseBody = response.getBody();
            boolean blocked = responseBody != null && Boolean.TRUE.equals(responseBody.get("blocked"));
            boolean accepted = responseBody != null && responseBody.get("task_id") != null;
            if (blocked) {
                teachingClass.setSyncStatus(previousStatus == null || previousStatus.isBlank() ? "IDLE" : previousStatus);
                classRepo.save(teachingClass);
                result.put("syncStatus", teachingClass.getSyncStatus());
                result.put("blocked", true);
                result.put("message", responseBody.get("message"));
                result.put("credentialSource", credentialSource);
                return result;
            }

            if (!accepted) {
                teachingClass.setSyncStatus(previousStatus == null || previousStatus.isBlank() ? "IDLE" : previousStatus);
                classRepo.save(teachingClass);
                result.put("syncStatus", teachingClass.getSyncStatus());
                result.put("credentialSource", credentialSource);
                result.put("message", responseBody == null ? "pta spider returned empty response" : responseBody.get("message"));
                return result;
            }

            result.put("syncStatus", "RUNNING");
            result.put("taskId", responseBody.get("task_id"));
            result.put("credentialSource", responseBody != null && responseBody.get("credential_source") != null
                    ? responseBody.get("credential_source")
                    : credentialSource);
            result.put("message", responseBody.get("message"));
            return result;
        } catch (Exception e) {
            log.error("Failed to trigger PTA sync: {}", e.getMessage());
            teachingClass.setSyncStatus("FAILED");
            classRepo.save(teachingClass);
            throw new RuntimeException("pta spider call failed: " + e.getMessage());
        }
    }

    private TeachingClassEntity requireOwnedClass(Long classId, Long teacherId) {
        TeachingClassEntity teachingClass = classRepo.findById(classId)
                .orElseThrow(() -> new NoSuchElementException("class not found"));
        if (!teacherId.equals(teachingClass.getTeacherId())) {
            throw new SecurityException("forbidden");
        }
        return teachingClass;
    }

    private Map<String, Object> toStatusMap(TeachingClassEntity teachingClass) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("syncStatus", teachingClass.getSyncStatus());
        result.put("lastSyncAt", teachingClass.getLastSyncAt());
        result.put("ptaKeyword", teachingClass.getPtaKeyword());
        result.put("syncEnabled", teachingClass.getSyncEnabled());
        return result;
    }

    private String resolvePtaKeyword(TeachingClassEntity teachingClass, String ptaKeyword) {
        if (ptaKeyword != null && !ptaKeyword.isBlank()) {
            return ptaKeyword.trim();
        }
        return teachingClass.getName() == null ? null : teachingClass.getName().trim();
    }

    private ResolvedSyncCredential resolveCredential(
            Long teacherId,
            String overrideUsername,
            String overridePassword
    ) {
        String username = overrideUsername == null ? "" : overrideUsername.trim();
        String password = overridePassword == null ? "" : overridePassword;
        boolean hasUsername = !username.isBlank();
        boolean hasPassword = !password.isBlank();

        if (hasUsername != hasPassword) {
            throw new IllegalArgumentException("ptaUsername and ptaPassword must be provided together");
        }
        if (hasUsername) {
            return new ResolvedSyncCredential(
                    CREDENTIAL_SOURCE_TEMPORARY,
                    new TeacherPtaCredentialService.ResolvedPtaCredential(username, password));
        }
        TeacherPtaCredentialService.ResolvedPtaCredential boundCredential =
                teacherPtaCredentialService.resolveCredentials(teacherId);
        if (boundCredential != null) {
            return new ResolvedSyncCredential(CREDENTIAL_SOURCE_BOUND, boundCredential);
        }
        return new ResolvedSyncCredential(CREDENTIAL_SOURCE_COOKIE, null);
    }

    private record ResolvedSyncCredential(
            String source,
            TeacherPtaCredentialService.ResolvedPtaCredential credential
    ) {}
}
