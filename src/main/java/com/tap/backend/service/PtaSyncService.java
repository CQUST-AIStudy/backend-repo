package com.tap.backend.service;

import com.tap.backend.domain.classroom.TeachingClassEntity;
import com.tap.backend.repo.TeachingClassRepository;
import jakarta.persistence.EntityManager;
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
import org.springframework.web.client.RestClientException;
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
    private final EntityManager entityManager;
    private final RestTemplate restTemplate;

    @Value("${pta.spider-url:http://127.0.0.1:8100}")
    private String spiderUrl;

    public PtaSyncService(
            TeachingClassRepository classRepo,
            TeachingClassService teachingClassService,
            TeacherPtaCredentialService teacherPtaCredentialService,
            EntityManager entityManager,
            @Value("${pta.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${pta.read-timeout-ms:20000}") int readTimeoutMs
    ) {
        this.classRepo = classRepo;
        this.teachingClassService = teachingClassService;
        this.teacherPtaCredentialService = teacherPtaCredentialService;
        this.entityManager = entityManager;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(1000, connectTimeoutMs));
        requestFactory.setReadTimeout(Math.max(1000, readTimeoutMs));
        this.restTemplate = new RestTemplate(requestFactory);
    }

    @Transactional
    public Map<String, Object> updateSyncConfig(
            Long classId,
            Long teacherId,
            String ptaKeyword,
            Boolean syncEnabled,
            String ptaProblemSetId,
            String ptaProblemSetName,
            String ptaGroupId,
            String ptaGroupName
    ) {
        TeachingClassEntity teachingClass = requireOwnedClass(classId, teacherId);
        if (ptaKeyword != null) {
            teachingClass.setPtaKeyword(resolvePtaKeyword(teachingClass, ptaKeyword));
        }
        if (syncEnabled != null) {
            teachingClass.setSyncEnabled(syncEnabled);
        }
        if (ptaProblemSetId != null) {
            teachingClass.setPtaProblemSetId(normalizeNullableText(ptaProblemSetId));
        }
        if (ptaProblemSetName != null) {
            teachingClass.setPtaProblemSetName(normalizeNullableText(ptaProblemSetName));
        }
        if (ptaGroupId != null) {
            teachingClass.setPtaGroupId(normalizeNullableText(ptaGroupId));
        }
        if (ptaGroupName != null) {
            teachingClass.setPtaGroupName(normalizeNullableText(ptaGroupName));
        }
        teachingClass.setPtaBindingVerifyStatus(resolveBindingStatus(teachingClass));
        teachingClass.setPtaBindingVerifyMessage(resolveBindingMessage(teachingClass));
        classRepo.save(teachingClass);

        return toStatusMap(teachingClass);
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

    @Transactional(readOnly = true)
    public Map<String, Object> getSnapshotIntegrity(Long classId, Long teacherId) {
        TeachingClassEntity teachingClass = requireOwnedClass(classId, teacherId);
        List<String> warnings = new java.util.ArrayList<>();

        Map<String, Object> latestJob = findLatestImportJob(classId);
        Long latestJobId = latestJob.get("id") instanceof Number number ? number.longValue() : null;

        long expectedStudentCount = nativeCount(
                "SELECT COUNT(*) FROM class_student WHERE class_id = ?1",
                classId);
        long boundStudentCount = nativeCount(
                "SELECT COUNT(*) FROM class_student WHERE class_id = ?1 AND user_id IS NOT NULL",
                classId);

        long sourceFileCount = 0;
        long rawSubmissionRowCount = 0;
        long rawTranscriptRowCount = 0;
        long rawAnswerSheetCount = 0;
        long distinctPtaUserCount = 0;
        long distinctProblemCount = 0;
        Map<String, Long> sourceFileStatusCounts = new LinkedHashMap<>();

        if (latestJobId == null) {
            warnings.add("no PTA import job found for this class");
        } else {
            sourceFileCount = nativeCount(
                    "SELECT COUNT(*) FROM import_source_file WHERE import_job_id = ?1",
                    latestJobId);
            rawSubmissionRowCount = nativeCount(
                    "SELECT COUNT(*) FROM pta_raw_submission_row WHERE import_job_id = ?1",
                    latestJobId);
            rawTranscriptRowCount = nativeCount(
                    "SELECT COUNT(*) FROM pta_raw_transcript_row WHERE import_job_id = ?1",
                    latestJobId);
            rawAnswerSheetCount = nativeCount(
                    "SELECT COUNT(*) FROM pta_raw_answer_sheet WHERE import_job_id = ?1",
                    latestJobId);
            distinctPtaUserCount = nativeCount(
                    "SELECT COUNT(DISTINCT pta_user_id) FROM pta_raw_submission_row WHERE import_job_id = ?1 AND pta_user_id IS NOT NULL",
                    latestJobId);
            distinctProblemCount = nativeCount(
                    "SELECT COUNT(DISTINCT pta_problem_id) FROM pta_raw_submission_row WHERE import_job_id = ?1 AND pta_problem_id IS NOT NULL",
                    latestJobId);
            sourceFileStatusCounts = querySourceFileStatusCounts(latestJobId);

            Object jobStatus = latestJob.get("status");
            if (jobStatus != null && !"SUCCEEDED".equals(jobStatus.toString())) {
                warnings.add("latest PTA import job status is " + jobStatus);
            }
            if (sourceFileCount == 0) {
                warnings.add("latest PTA import job has no source files");
            }
            long failedFileCount = sourceFileStatusCounts.getOrDefault("FAILED", 0L);
            if (failedFileCount > 0) {
                warnings.add("latest PTA import job has failed source files: " + failedFileCount);
            }
            if (rawSubmissionRowCount == 0) {
                warnings.add("latest PTA import job has no raw submission rows");
            }
        }

        if (expectedStudentCount == 0) {
            warnings.add("class has no students to compare with PTA snapshot");
        } else if (boundStudentCount < expectedStudentCount) {
            warnings.add("some class students are not bound to login accounts");
        }

        boolean hasSnapshot = latestJobId != null && rawSubmissionRowCount > 0;
        boolean sourceFilesComplete = latestJobId != null
                && sourceFileCount > 0
                && sourceFileStatusCounts.getOrDefault("FAILED", 0L) == 0
                && sourceFileStatusCounts.getOrDefault("PENDING", 0L) == 0;
        boolean snapshotComplete = hasSnapshot && sourceFilesComplete && warnings.isEmpty();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("classId", teachingClass.getId());
        result.put("className", teachingClass.getName());
        result.put("ptaKeyword", teachingClass.getPtaKeyword());
        result.put("ptaProblemSetId", teachingClass.getPtaProblemSetId());
        result.put("ptaGroupId", teachingClass.getPtaGroupId());
        result.put("hasSnapshot", hasSnapshot);
        result.put("snapshotComplete", snapshotComplete);
        result.put("latestJob", latestJob.isEmpty() ? null : latestJob);
        result.put("sourceFileCount", sourceFileCount);
        result.put("sourceFileStatusCounts", sourceFileStatusCounts);
        result.put("rawSubmissionRowCount", rawSubmissionRowCount);
        result.put("rawTranscriptRowCount", rawTranscriptRowCount);
        result.put("rawAnswerSheetCount", rawAnswerSheetCount);
        result.put("distinctPtaUserCount", distinctPtaUserCount);
        result.put("distinctProblemCount", distinctProblemCount);
        result.put("expectedStudentCount", expectedStudentCount);
        result.put("boundStudentCount", boundStudentCount);
        result.put("warnings", warnings);
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> testConnection(
            Long classId,
            Long teacherId,
            String ptaUsername,
            String ptaPassword,
            String ptaKeyword,
            String ptaProblemSetId,
            String ptaGroupId
    ) {
        TeachingClassEntity teachingClass = requireOwnedClass(classId, teacherId);
        List<String> warnings = new java.util.ArrayList<>();
        List<String> nextSteps = new java.util.ArrayList<>();

        String effectiveKeyword = firstNotBlank(ptaKeyword, teachingClass.getPtaKeyword());
        String effectiveProblemSetId = firstNotBlank(ptaProblemSetId, teachingClass.getPtaProblemSetId());
        String effectiveGroupId = firstNotBlank(ptaGroupId, teachingClass.getPtaGroupId());

        ResolvedSyncCredential resolvedCredential = resolveCredential(teacherId, ptaUsername, ptaPassword);
        boolean keywordConfigured = effectiveKeyword != null;
        boolean preciseBindingConfigured = effectiveProblemSetId != null && effectiveGroupId != null;

        Map<String, Object> spiderHealth = new LinkedHashMap<>();
        boolean spiderReachable = false;
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(spiderUrl + "/health", Map.class);
            spiderReachable = response.getStatusCode().is2xxSuccessful();
            if (response.getBody() != null) {
                spiderHealth.putAll(response.getBody());
            }
        } catch (RestClientException ex) {
            spiderHealth.put("error", ex.getMessage());
            warnings.add("PTA spider is not reachable: " + ex.getMessage());
        }

        Map<String, Object> cooldown = new LinkedHashMap<>();
        if (spiderReachable && keywordConfigured) {
            try {
                ResponseEntity<Map> response = restTemplate.getForEntity(
                        spiderUrl + "/cooldown/" + java.net.URLEncoder.encode(effectiveKeyword, java.nio.charset.StandardCharsets.UTF_8),
                        Map.class);
                if (response.getBody() != null) {
                    cooldown.putAll(response.getBody());
                }
            } catch (RestClientException ex) {
                cooldown.put("error", ex.getMessage());
                warnings.add("PTA spider cooldown check failed: " + ex.getMessage());
            }
        }

        if (!keywordConfigured) {
            warnings.add("PTA keyword is not configured");
            nextSteps.add("Set ptaKeyword or pass it in this test request");
        }
        if (!preciseBindingConfigured) {
            warnings.add("PTA problem set id and group id are not fully configured");
            nextSteps.add("Bind ptaProblemSetId and ptaGroupId before triggering sync");
        }
        if (CREDENTIAL_SOURCE_COOKIE.equals(resolvedCredential.source())) {
            nextSteps.add("Use browser cookie mode, or bind PTA credentials for unattended sync");
        }
        if (!spiderReachable) {
            nextSteps.add("Start or check the PTA spider service at " + spiderUrl);
        }
        if (nextSteps.isEmpty()) {
            nextSteps.add("Configuration is ready for PTA sync trigger");
        }

        boolean readyToSync = spiderReachable && keywordConfigured && preciseBindingConfigured;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("classId", teachingClass.getId());
        result.put("className", teachingClass.getName());
        result.put("spiderUrl", spiderUrl);
        result.put("spiderReachable", spiderReachable);
        result.put("spiderHealth", spiderHealth);
        result.put("credentialSource", resolvedCredential.source());
        result.put("keywordConfigured", keywordConfigured);
        result.put("preciseBindingConfigured", preciseBindingConfigured);
        result.put("ptaKeyword", effectiveKeyword);
        result.put("ptaProblemSetId", effectiveProblemSetId);
        result.put("ptaGroupId", effectiveGroupId);
        result.put("cooldown", cooldown);
        result.put("readyToSync", readyToSync);
        result.put("warnings", warnings);
        result.put("nextSteps", nextSteps);
        result.put("message", readyToSync
                ? "PTA connection prerequisites look ready"
                : "PTA connection test completed with warnings");
        return result;
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
            putIfNotBlank(body, "problem_set_id", teachingClass.getPtaProblemSetId());
            putIfNotBlank(body, "problem_set_name", teachingClass.getPtaProblemSetName());
            putIfNotBlank(body, "group_id", teachingClass.getPtaGroupId());
            putIfNotBlank(body, "group_name", teachingClass.getPtaGroupName());
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
        } catch (RestClientException e) {
            log.error("Failed to trigger PTA sync: {}", e.getMessage());
            String restoredStatus = previousStatus == null || previousStatus.isBlank() ? "IDLE" : previousStatus;
            teachingClass.setSyncStatus(restoredStatus);
            classRepo.save(teachingClass);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("syncStatus", restoredStatus);
            result.put("blocked", true);
            result.put("credentialSource", credentialSource);
            result.put("spiderUrl", spiderUrl);
            result.put("message", "PTA spider 服务不可达，请先启动或检查 " + spiderUrl);
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
        result.put("ptaProblemSetId", teachingClass.getPtaProblemSetId());
        result.put("ptaProblemSetName", teachingClass.getPtaProblemSetName());
        result.put("ptaGroupId", teachingClass.getPtaGroupId());
        result.put("ptaGroupName", teachingClass.getPtaGroupName());
        result.put("ptaBindingVerifiedAt", teachingClass.getPtaBindingVerifiedAt());
        result.put("ptaBindingVerifyStatus", teachingClass.getPtaBindingVerifyStatus());
        result.put("ptaBindingVerifyMessage", teachingClass.getPtaBindingVerifyMessage());
        result.put("syncEnabled", teachingClass.getSyncEnabled());
        return result;
    }

    private String resolvePtaKeyword(TeachingClassEntity teachingClass, String ptaKeyword) {
        if (ptaKeyword != null && !ptaKeyword.isBlank()) {
            return ptaKeyword.trim();
        }
        return teachingClass.getName() == null ? null : teachingClass.getName().trim();
    }

    private Map<String, Object> findLatestImportJob(Long classId) {
        List<?> rows = entityManager.createNativeQuery("""
                        SELECT id, status, started_at, finished_at, error_message
                        FROM import_job
                        WHERE class_id = ?1 AND source_system = 'PTA'
                        ORDER BY started_at DESC, id DESC
                        LIMIT 1
                        """)
                .setParameter(1, classId)
                .getResultList();
        if (rows.isEmpty()) {
            return Map.of();
        }
        Object[] row = (Object[]) rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row[0]);
        result.put("status", row[1]);
        result.put("startedAt", row[2]);
        result.put("finishedAt", row[3]);
        result.put("errorMessage", row[4]);
        return result;
    }

    private long nativeCount(String sql, Object parameter) {
        Object value = entityManager.createNativeQuery(sql)
                .setParameter(1, parameter)
                .getSingleResult();
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private Map<String, Long> querySourceFileStatusCounts(Long latestJobId) {
        List<?> rows = entityManager.createNativeQuery("""
                        SELECT parse_status, COUNT(*)
                        FROM import_source_file
                        WHERE import_job_id = ?1
                        GROUP BY parse_status
                        """)
                .setParameter(1, latestJobId)
                .getResultList();
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object rowObject : rows) {
            Object[] row = (Object[]) rowObject;
            String status = row[0] == null ? "UNKNOWN" : row[0].toString();
            long count = row[1] instanceof Number number ? number.longValue() : 0L;
            result.put(status, count);
        }
        return result;
    }

    private void putIfNotBlank(Map<String, Object> body, String key, String value) {
        if (value != null && !value.isBlank()) {
            body.put(key, value.trim());
        }
    }

    private String normalizeNullableText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String firstNotBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }

    private String resolveBindingStatus(TeachingClassEntity teachingClass) {
        boolean hasProblemSet = teachingClass.getPtaProblemSetId() != null && !teachingClass.getPtaProblemSetId().isBlank();
        boolean hasGroup = teachingClass.getPtaGroupId() != null && !teachingClass.getPtaGroupId().isBlank();
        if (hasProblemSet && hasGroup) {
            return "CONFIGURED";
        }
        if (hasProblemSet || hasGroup) {
            return "PARTIAL";
        }
        return "UNCONFIGURED";
    }

    private String resolveBindingMessage(TeachingClassEntity teachingClass) {
        return switch (resolveBindingStatus(teachingClass)) {
            case "CONFIGURED" -> "PTA problem set and group are precisely bound";
            case "PARTIAL" -> "PTA precise binding is incomplete";
            default -> "PTA precise binding is not configured";
        };
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
