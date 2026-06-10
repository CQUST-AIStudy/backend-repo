package com.tap.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.grading.*;
import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.infra.storage.ObjectStorageService;
import com.tap.backend.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class GradingTaskService {
    private static final Logger log = LoggerFactory.getLogger(GradingTaskService.class);
    private static final int MAX_BATCH_SIZE = 200;
    private static final String QUEUE_KEY = "grading:tasks";
    private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46}; // %PDF
    private static final java.math.BigDecimal DEFAULT_SCORE_RANGE_MIN = new java.math.BigDecimal("75");
    private static final java.math.BigDecimal DEFAULT_SCORE_RANGE_MAX = new java.math.BigDecimal("99");

    private final GradingTaskRepository taskRepo;
    private final GradingBatchRepository batchRepo;
    private final GradingSubmissionRepository submissionRepo;
    private final GradingRubricRepository rubricRepo;
    private final UserRepository userRepo;
    private final ObjectStorageService storageService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final GradingSubmissionService gradingSubmissionService;
    private final GradingUnifiedLinkService gradingUnifiedLinkService;
    private final GradingTraceRepository traceRepo;
    private final OfficeDocumentConversionService officeDocumentConversionService;

    @Value("${tap.grading.stuck-scan-enabled:true}")
    private boolean stuckScanEnabled;

    @Value("${tap.grading.stuck-timeout-minutes:20}")
    private long stuckTimeoutMinutes;

    public GradingTaskService(GradingTaskRepository taskRepo,
                              GradingBatchRepository batchRepo,
                              GradingSubmissionRepository submissionRepo,
                              GradingRubricRepository rubricRepo,
                              UserRepository userRepo,
                              ObjectStorageService storageService,
                              StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper,
                              GradingSubmissionService gradingSubmissionService,
                              GradingUnifiedLinkService gradingUnifiedLinkService,
                              GradingTraceRepository traceRepo,
                              OfficeDocumentConversionService officeDocumentConversionService) {
        this.taskRepo = taskRepo;
        this.batchRepo = batchRepo;
        this.submissionRepo = submissionRepo;
        this.rubricRepo = rubricRepo;
        this.userRepo = userRepo;
        this.storageService = storageService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.gradingSubmissionService = gradingSubmissionService;
        this.gradingUnifiedLinkService = gradingUnifiedLinkService;
        this.traceRepo = traceRepo;
        this.officeDocumentConversionService = officeDocumentConversionService;
    }

    @Transactional
    public Map<String, Object> createTask(Long teacherId, Long experimentId, Long classId,
                                           String teacherSignature, Long rubricId, java.math.BigDecimal scoreRangeMin,
                                           java.math.BigDecimal scoreRangeMax,
                                           MultipartFile[] files,
                                           Long batchId, String batchName) {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("At least one PDF or Word file is required");
        }
        if (files.length > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Batch size exceeds maximum of " + MAX_BATCH_SIZE);
        }
        ScoreRange resolvedScoreRange = resolveScoreRange(scoreRangeMin, scoreRangeMax);
        validateScoreRange(resolvedScoreRange.min(), resolvedScoreRange.max());

        UserEntity teacher = userRepo.findById(teacherId)
                .orElseThrow(() -> new IllegalArgumentException("Teacher not found"));
        GradingRubricEntity rubric = rubricRepo.findById(rubricId)
                .orElseThrow(() -> new IllegalArgumentException("Rubric not found"));
        if (!teacherId.equals(rubric.getTeacherId())) {
            throw new IllegalArgumentException("Rubric not found");
        }

        // Separate valid documents from invalid files
        List<MultipartFile> validPdfs = new ArrayList<>();
        List<String> rejectedFiles = new ArrayList<>();
        for (MultipartFile file : files) {
            if (isSupportedDocument(file)) {
                validPdfs.add(file);
            } else {
                rejectedFiles.add(file.getOriginalFilename());
            }
        }

        if (validPdfs.isEmpty()) {
            throw new IllegalArgumentException("No valid PDF or Word files in the batch");
        }

        GradingTaskEntity task = new GradingTaskEntity();
        task.setTeacher(teacher);
        task.setExperimentId(experimentId);
        task.setAssignmentOfferingId(gradingUnifiedLinkService.resolveAssignmentOfferingId(experimentId, classId, teacherId));
        task.setClassId(classId);
        task.setTeacherSignature(resolveTeacherSignature(teacher, teacherSignature));
        task.setRubric(rubric);
        task.setScoreRangeMin(resolvedScoreRange.min());
        task.setScoreRangeMax(resolvedScoreRange.max());
        task.setStatus(GradingTaskStatus.PENDING);
        task.setTotalCount(validPdfs.size());
        task = taskRepo.save(task);

        // Generate human-friendly display code (MMDD-XX format)
        task.setDisplayCode(generateDisplayCode(teacherId, task.getCreatedAt()));

        // Attach to an existing batch, or create a new batch for this upload (one upload = one batch)
        GradingBatchEntity batch = resolveOrCreateBatch(teacher, batchId, batchName, task.getDisplayCode());
        task.setBatch(batch);
        task = taskRepo.save(task);
        if (experimentId != null && task.getAssignmentOfferingId() == null) {
            log.warn("No assignment_offering_id resolved for grading task. experimentId={}, classId={}, teacherId={}",
                    experimentId, classId, teacherId);
        }

        // Store documents and create submissions
        for (MultipartFile pdf : validPdfs) {
            try {
                String originalFilename = pdf.getOriginalFilename();
                byte[] sourceBytes = pdf.getBytes();
                String extension = resolveExtension(originalFilename);
                String contentType = detectContentType(pdf, extension);
                if (isLegacyWordDocument(originalFilename, contentType)) {
                    sourceBytes = officeDocumentConversionService.convertWordToPdf(originalFilename, sourceBytes);
                    extension = ".pdf";
                    contentType = "application/pdf";
                }
                String objectKey = "grading/" + task.getId() + "/" + UUID.randomUUID() + extension;
                storageService.putBytes(objectKey, sourceBytes, contentType);

                GradingSubmissionEntity sub = new GradingSubmissionEntity();
                sub.setTask(task);
                sub.setPdfObjectKey(objectKey);
                sub.setOriginalFilename(originalFilename);
                sub.setStudentName(extractStudentName(originalFilename));
                applyUnifiedSubmissionIdentity(task, sub);
                sub.setStatus(SubmissionStatus.PENDING);
                submissionRepo.save(sub);
            } catch (Exception e) {
                log.error("Failed to store document: {}", pdf.getOriginalFilename(), e);
                rejectedFiles.add(pdf.getOriginalFilename() + " (storage error)");
                task.setTotalCount(task.getTotalCount() - 1);
            }
        }

        if (task.getTotalCount() <= 0) {
            throw new IllegalArgumentException("All PDF or Word files failed to upload");
        }

        task = taskRepo.save(task);
        final Long taskIdFinal = task.getId();

        // Publish to Redis AFTER transaction commits to avoid orphan messages
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishTaskToQueue(taskIdFinal);
            }
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getId());
        result.put("displayCode", task.getDisplayCode());
        result.put("batchId", batch.getId());
        result.put("batchName", batch.getName());
        result.put("status", task.getStatus().name());
        result.put("totalCount", task.getTotalCount());
        result.put("rubricId", rubricId);
        result.put("scoreRangeMin", task.getScoreRangeMin());
        result.put("scoreRangeMax", task.getScoreRangeMax());
        result.put("teacherSignature", task.getTeacherSignature());
        result.put("createdAt", task.getCreatedAt().toString());
        if (!rejectedFiles.isEmpty()) {
            result.put("rejectedFiles", rejectedFiles);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Page<GradingTaskEntity> getTaskList(Long teacherId, GradingTaskStatus status, Pageable pageable) {
        if (status != null) {
            return taskRepo.findAllByTeacherIdAndStatus(teacherId, status, pageable);
        }
        return taskRepo.findAllByTeacherId(teacherId, pageable);
    }

    @Transactional(readOnly = true)
    public GradingTaskEntity getTaskDetail(Long taskId, Long teacherId) {
        return requireOwnedTask(taskId, teacherId);
    }

    @Transactional(readOnly = true)
    public List<GradingSubmissionEntity> getTaskSubmissions(Long taskId, Long teacherId) {
        requireOwnedTask(taskId, teacherId);
        return submissionRepo.findAllByTaskId(taskId);
    }

    @Transactional
    public String updateTeacherSignature(Long taskId, Long teacherId, String teacherSignature) {
        GradingTaskEntity task = requireOwnedTask(taskId, teacherId);
        String resolved = resolveTeacherSignature(task.getTeacher(), teacherSignature);
        task.setTeacherSignature(resolved);
        taskRepo.save(task);
        return resolved;
    }

    @Transactional
    public void deleteTask(Long taskId, Long teacherId) {
        GradingTaskEntity task = taskRepo.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        if (!task.getTeacherId().equals(teacherId)) {
            throw new IllegalArgumentException("无权删除此任务");
        }
        if (task.getStatus() == GradingTaskStatus.PROCESSING) {
            throw new IllegalStateException("任务正在处理中，无法删除");
        }
        // CASCADE delete handles submissions, evidence, scores, traces, reports
        taskRepo.delete(task);
        log.info("Deleted grading task {} by teacher {}", taskId, teacherId);
    }

    @Transactional
    public void retryFailed(Long taskId, Long teacherId) {
        GradingTaskEntity task = requireOwnedTask(taskId, teacherId);

        List<GradingSubmissionEntity> failed = submissionRepo
                .findAllByTaskIdAndStatus(taskId, SubmissionStatus.FAILED);

        if (failed.isEmpty()) {
            throw new IllegalStateException("No failed submissions to retry");
        }

        for (GradingSubmissionEntity sub : failed) {
            sub.setStatus(SubmissionStatus.PENDING);
            sub.setErrorMessage(null);
            submissionRepo.save(sub);
        }

        refreshTaskCounters(task);
        task.setStatus(GradingTaskStatus.PROCESSING);
        taskRepo.save(task);

        final Long taskIdFinal = task.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishTaskToQueue(taskIdFinal);
            }
        });
    }

    @Transactional
    public void retryFailedSubmission(Long submissionId, Long teacherId) {
        GradingSubmissionEntity submission = submissionRepo.findById(submissionId)
                .orElseThrow(() -> new IllegalArgumentException("Submission not found"));
        GradingTaskEntity task = submission.getTask();
        if (task == null || !Objects.equals(task.getTeacherId(), teacherId)) {
            throw new IllegalArgumentException("No permission to access this submission");
        }
        if (submission.getStatus() != SubmissionStatus.FAILED) {
            throw new IllegalStateException("Only failed submissions can be retried");
        }

        submission.setStatus(SubmissionStatus.PENDING);
        submission.setErrorMessage(null);
        submission.setTotalScore(null);
        submissionRepo.save(submission);

        refreshTaskCounters(task);
        task.setStatus(GradingTaskStatus.PROCESSING);
        taskRepo.save(task);

        final Long taskIdFinal = task.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishTaskToQueue(taskIdFinal);
            }
        });
    }

    @Transactional
    public int forceRequeueProcessing(Long taskId, Long teacherId) {
        GradingTaskEntity task = requireOwnedTask(taskId, teacherId);
        List<GradingSubmissionEntity> processing = submissionRepo.findAllByTaskIdAndStatus(taskId, SubmissionStatus.PROCESSING);
        if (processing.isEmpty()) {
            throw new IllegalStateException("No processing submissions to requeue");
        }

        int changed = 0;
        for (GradingSubmissionEntity sub : processing) {
            sub.setStatus(SubmissionStatus.PENDING);
            sub.setErrorMessage("Manually requeued from stale processing state");
            submissionRepo.save(sub);
            changed++;
        }

        refreshTaskCounters(task);
        task.setStatus(GradingTaskStatus.PROCESSING);
        taskRepo.save(task);

        final Long taskIdFinal = taskId;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishTaskToQueue(taskIdFinal);
            }
        });
        return changed;
    }

    @Transactional
    public void deleteOwnedTask(Long taskId, Long teacherId) {
        GradingTaskEntity task = requireOwnedTask(taskId, teacherId);
        if (task.getStatus() == GradingTaskStatus.PROCESSING) {
            throw new IllegalStateException("Task is still processing");
        }
        // Note: For PENDING tasks, the Celery job in the queue will gracefully exit
        // when it finds the submission no longer exists (cascade delete).
        taskRepo.delete(task);
        log.info("Deleted grading task {} (status={}) by teacher {}", taskId, task.getStatus(), teacherId);
    }

    /**
     * 导出 Excel：学号、姓名、班级、成绩，可选评语
     */
    @Transactional(readOnly = true)
    public byte[] exportExcel(Long taskId, Long teacherId, List<Long> submissionIds, boolean includeComments) {
        requireOwnedTask(taskId, teacherId);
        List<GradingSubmissionEntity> subs;
        if (submissionIds != null && !submissionIds.isEmpty()) {
            subs = submissionRepo.findAllByTaskIdAndIdIn(taskId, submissionIds);
            if (subs.size() != new HashSet<>(submissionIds).size()) {
                throw new IllegalArgumentException("Some submissions do not belong to this task");
            }
        } else {
            subs = submissionRepo.findAllByTaskId(taskId);
        }

        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             var baos = new ByteArrayOutputStream()) {

            var sheet = workbook.createSheet("批改成绩");
            var headerStyle = workbook.createCellStyle();
            var headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            var headerRow = sheet.createRow(0);
            int col = 0;
            String[] headers = includeComments
                    ? new String[]{"学号", "姓名", "班级", "成绩", "总评"}
                    : new String[]{"学号", "姓名", "班级", "成绩"};
            for (String h : headers) {
                var cell = headerRow.createCell(col++);
                cell.setCellValue(h);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (GradingSubmissionEntity sub : subs) {
                var row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(sub.getStudentNo() != null ? sub.getStudentNo() : "");
                row.createCell(1).setCellValue(sub.getStudentName() != null ? sub.getStudentName() : "");
                row.createCell(2).setCellValue(sub.getClassName() != null ? sub.getClassName() : "");
                if (sub.getTotalScore() != null) {
                    row.createCell(3).setCellValue(sub.getTotalScore().doubleValue());
                } else {
                    row.createCell(3).setCellValue("");
                }
                if (includeComments) {
                    row.createCell(4).setCellValue(sub.getFinalReviewComment() != null ? sub.getFinalReviewComment() : "");
                }
            }

            // Calculate column widths with proper CJK character handling
            int[] maxWidths = new int[headers.length];
            // Initialize with header widths (CJK chars count as ~2 Latin chars)
            for (int i = 0; i < headers.length; i++) {
                maxWidths[i] = calcDisplayWidth(headers[i]);
            }
            // Check data rows
            for (int r = 1; r <= subs.size(); r++) {
                var row = sheet.getRow(r);
                if (row == null) continue;
                for (int c = 0; c < headers.length; c++) {
                    var cell = row.getCell(c);
                    if (cell == null) continue;
                    String val = getCellStringValue(cell);
                    int width = calcDisplayWidth(val);
                    if (width > maxWidths[c]) maxWidths[c] = width;
                }
            }
            // Set column widths with some padding, capped at reasonable max
            for (int i = 0; i < headers.length; i++) {
                int width = Math.min(maxWidths[i] + 2, 60); // +2 padding, max 60
                if (i == headers.length - 1 && includeComments) {
                    width = Math.min(width, 80); // comment column can be wider
                }
                sheet.setColumnWidth(i, width * 256); // POI uses 1/256 of a character width
            }

            workbook.write(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("导出 Excel 失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void onSubmissionComplete(Long submissionId, String status, java.math.BigDecimal totalScore) {
        GradingSubmissionEntity sub = submissionRepo.findById(submissionId).orElse(null);
        if (sub == null) {
            log.warn("Submission not found for completion: {}", submissionId);
            return;
        }

        SubmissionStatus subStatus = SubmissionStatus.valueOf(status);
        sub.setStatus(subStatus);
        sub.setTotalScore(totalScore);
        submissionRepo.save(sub);
        final Long teacherId = sub.getTask().getTeacherId();

        Long taskId = sub.getTaskId();
        GradingTaskEntity task = taskRepo.findById(taskId).orElse(null);
        if (task == null) return;
        refreshTaskCounters(task);
        int done = task.getCompletedCount() + task.getFailedCount();
        if (done >= task.getTotalCount()) {
            task.setStatus(task.getFailedCount() > 0 ? GradingTaskStatus.FAILED : GradingTaskStatus.COMPLETED);
            taskRepo.save(task);
        } else if (task.getStatus() != GradingTaskStatus.PROCESSING) {
            task.setStatus(GradingTaskStatus.PROCESSING);
            taskRepo.save(task);
        }

        if (subStatus == SubmissionStatus.SCORED || subStatus == SubmissionStatus.NEED_MORE_EVIDENCE) {
            autoFinalizeSubmission(submissionId, teacherId);
        }
    }

    private void publishTaskToQueue(Long taskId) {
        try {
            GradingTaskEntity task = taskRepo.findById(taskId).orElse(null);
            if (task == null) return;
            // Fetch rubric custom prompt
            GradingRubricEntity rubric = task.getRubric();
            String customPrompt = rubric != null ? rubric.getCustomPrompt() : null;

            List<GradingSubmissionEntity> pending = submissionRepo
                    .findAllByTaskIdAndStatus(taskId, SubmissionStatus.PENDING);
            if (!pending.isEmpty() && task.getStatus() == GradingTaskStatus.PENDING) {
                task.setStatus(GradingTaskStatus.PROCESSING);
                taskRepo.save(task);
            }
            for (GradingSubmissionEntity sub : pending) {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("taskId", taskId);
                msg.put("submissionId", sub.getId());
                msg.put("pdfObjectKey", sub.getPdfObjectKey());
                msg.put("originalFilename", sub.getOriginalFilename());
                msg.put("rubricId", rubric.getId());
                if (customPrompt != null && !customPrompt.isBlank()) {
                    msg.put("customPrompt", customPrompt);
                }
                if (task.getScoreRangeMin() != null) {
                    msg.put("scoreRangeMin", task.getScoreRangeMin());
                }
                if (task.getScoreRangeMax() != null) {
                    msg.put("scoreRangeMax", task.getScoreRangeMax());
                }
                redisTemplate.opsForList().rightPush(QUEUE_KEY, objectMapper.writeValueAsString(msg));
            }
            log.info("Published {} submissions to grading queue for task {}", pending.size(), taskId);
        } catch (Exception e) {
            log.error("Failed to publish to Redis queue", e);
        }
    }

    private boolean isSupportedDocument(MultipartFile file) {
        try {
            if (file.isEmpty()) return false;
            String filename = file.getOriginalFilename();
            String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".docx") || lower.endsWith(".doc")) {
                return true;
            }
            try (InputStream is = file.getInputStream()) {
                byte[] header = new byte[4];
                if (is.read(header) < 4) return false;
                return header[0] == PDF_MAGIC[0] && header[1] == PDF_MAGIC[1]
                        && header[2] == PDF_MAGIC[2] && header[3] == PDF_MAGIC[3];
            }
        } catch (Exception e) {
            return false;
        }
    }

    private String extractStudentName(String filename) {
        if (filename == null) return null;
        return filename.replaceAll("\\.[^.]+$", "");
    }

    private void applyUnifiedSubmissionIdentity(GradingTaskEntity task, GradingSubmissionEntity submission) {
        GradingUnifiedLinkService.SubmissionIdentity identity =
                gradingUnifiedLinkService.resolveSubmissionIdentity(task, submission);
        if (identity == null) {
            String className = gradingUnifiedLinkService.resolveClassName(
                    task == null ? null : task.getAssignmentOfferingId(),
                    task == null ? null : task.getClassId()
            );
            if (className != null && (submission.getClassName() == null || submission.getClassName().isBlank())) {
                submission.setClassName(className);
            }
            return;
        }
        submission.setStudentId(identity.studentProfileId());
        if (identity.studentNo() != null) {
            submission.setStudentNo(identity.studentNo());
        }
        if (identity.studentName() != null) {
            submission.setStudentName(identity.studentName());
        }
        if (identity.className() != null) {
            submission.setClassName(identity.className());
        }
    }

    private String resolveExtension(String filename) {
        if (filename == null) {
            return ".pdf";
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".docx")) {
            return ".docx";
        }
        if (lower.endsWith(".doc")) {
            return ".doc";
        }
        return ".pdf";
    }

    private String detectContentType(MultipartFile file, String extension) {
        if (file.getContentType() != null && !file.getContentType().isBlank()) {
            return file.getContentType();
        }
        if (".docx".equalsIgnoreCase(extension)) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (".doc".equalsIgnoreCase(extension)) {
            return "application/msword";
        }
        return "application/pdf";
    }

    private boolean isLegacyWordDocument(String filename, String contentType) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        return (lower.endsWith(".doc") && !lower.endsWith(".docx"))
                || "application/msword".equalsIgnoreCase(contentType);
    }

    private void validateScoreRange(java.math.BigDecimal scoreRangeMin, java.math.BigDecimal scoreRangeMax) {
        if (scoreRangeMin == null && scoreRangeMax == null) {
            return;
        }
        if (scoreRangeMin == null || scoreRangeMax == null) {
            throw new IllegalArgumentException("Both scoreRangeMin and scoreRangeMax are required");
        }
        if (scoreRangeMin.compareTo(java.math.BigDecimal.ZERO) < 0
                || scoreRangeMax.compareTo(new java.math.BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("Score range must be between 0 and 100");
        }
        if (scoreRangeMin.compareTo(scoreRangeMax) > 0) {
            throw new IllegalArgumentException("scoreRangeMin must be less than or equal to scoreRangeMax");
        }
    }

    private ScoreRange resolveScoreRange(java.math.BigDecimal scoreRangeMin, java.math.BigDecimal scoreRangeMax) {
        if (scoreRangeMin == null && scoreRangeMax == null) {
            return new ScoreRange(DEFAULT_SCORE_RANGE_MIN, DEFAULT_SCORE_RANGE_MAX);
        }
        return new ScoreRange(scoreRangeMin, scoreRangeMax);
    }

    private String resolveTeacherSignature(UserEntity teacher, String requestedSignature) {
        String normalized = normalizeTeacherSignature(requestedSignature);
        if (normalized != null) {
            return normalized;
        }
        normalized = normalizeTeacherSignature(teacher != null ? teacher.getDisplayName() : null);
        if (normalized != null) {
            return normalized;
        }
        normalized = normalizeTeacherSignature(teacher != null ? teacher.getUsername() : null);
        return normalized != null ? normalized : "任课教师";
    }

    private String normalizeTeacherSignature(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return null;
        }
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    private GradingTaskEntity requireOwnedTask(Long taskId, Long teacherId) {
        GradingTaskEntity task = taskRepo.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));
        if (!Objects.equals(task.getTeacherId(), teacherId)) {
            throw new IllegalArgumentException("No permission to access this task");
        }
        return task;
    }

    private void refreshTaskCounters(GradingTaskEntity task) {
        Long taskId = task.getId();
        int completedCount = submissionRepo.countByTaskIdAndStatus(taskId, SubmissionStatus.SCORED)
                + submissionRepo.countByTaskIdAndStatus(taskId, SubmissionStatus.NEED_MORE_EVIDENCE);
        int failedCount = submissionRepo.countByTaskIdAndStatus(taskId, SubmissionStatus.FAILED);
        task.setCompletedCount(completedCount);
        task.setFailedCount(failedCount);
    }

    private void autoFinalizeSubmission(Long submissionId, Long teacherId) {
        try {
            GradingSubmissionEntity submission = submissionRepo.findById(submissionId).orElse(null);
            if (submission == null || teacherId == null) {
                return;
            }
            gradingSubmissionService.ensureReviewAndAnnotatedReport(submissionId, teacherId);
            log.info("Auto finalized submission {}", submissionId);
        } catch (Exception e) {
            GradingSubmissionEntity submission = submissionRepo.findById(submissionId).orElse(null);
            if (submission != null) {
                submission.setErrorMessage("Auto finalization failed: " + e.getMessage());
                submissionRepo.save(submission);
            }
            log.warn("Auto finalization failed for submission {}: {}", submissionId, e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${tap.grading.stuck-scan-interval-ms:60000}")
    @Transactional
    public void recoverStuckProcessingSubmissions() {
        if (!stuckScanEnabled) {
            return;
        }

        long timeoutMinutes = Math.max(1, stuckTimeoutMinutes);
        Instant staleBefore = Instant.now().minus(Duration.ofMinutes(timeoutMinutes));
        List<GradingSubmissionEntity> candidates = submissionRepo
                .findAllByStatusAndUpdatedAtBefore(SubmissionStatus.PROCESSING, staleBefore);
        if (candidates.isEmpty()) {
            return;
        }

        Set<Long> taskIdsToRepublish = new HashSet<>();
        int recovered = 0;
        for (GradingSubmissionEntity sub : candidates) {
            Instant lastActivity = sub.getUpdatedAt();
            GradingTraceEntity latestTrace = traceRepo.findTopBySubmissionIdOrderByCreatedAtDesc(sub.getId());
            if (latestTrace != null && latestTrace.getCreatedAt() != null
                    && (lastActivity == null || latestTrace.getCreatedAt().isAfter(lastActivity))) {
                lastActivity = latestTrace.getCreatedAt();
            }
            if (lastActivity != null && lastActivity.isAfter(staleBefore)) {
                continue;
            }

            sub.setStatus(SubmissionStatus.PENDING);
            sub.setErrorMessage("Automatically requeued after stale processing timeout");
            submissionRepo.save(sub);
            taskIdsToRepublish.add(sub.getTaskId());
            recovered++;
        }

        if (recovered == 0) {
            return;
        }

        for (Long taskId : taskIdsToRepublish) {
            GradingTaskEntity task = taskRepo.findById(taskId).orElse(null);
            if (task == null) {
                continue;
            }
            refreshTaskCounters(task);
            task.setStatus(GradingTaskStatus.PROCESSING);
            taskRepo.save(task);

            final Long taskIdFinal = taskId;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishTaskToQueue(taskIdFinal);
                }
            });
        }

        log.warn("Recovered {} stale processing submissions and republished {} tasks",
                recovered, taskIdsToRepublish.size());
    }

    private record ScoreRange(java.math.BigDecimal min, java.math.BigDecimal max) {}

    /**
     * Resolves the batch a new task belongs to.
     * If batchId is provided, the existing batch is reused (must belong to the teacher);
     * otherwise a new batch is created for this upload (one upload = one batch).
     */
    private GradingBatchEntity resolveOrCreateBatch(UserEntity teacher, Long batchId, String batchName, String taskDisplayCode) {
        if (batchId != null) {
            GradingBatchEntity existing = batchRepo.findById(batchId)
                    .orElseThrow(() -> new IllegalArgumentException("批次不存在"));
            if (!Objects.equals(existing.getTeacherId(), teacher.getId())) {
                throw new IllegalArgumentException("无权使用此批次");
            }
            return existing;
        }
        GradingBatchEntity batch = new GradingBatchEntity();
        batch.setTeacher(teacher);
        batch.setDisplayCode(generateBatchDisplayCode(teacher.getId()));
        String normalizedName = batchName == null ? "" : batchName.trim();
        if (normalizedName.isEmpty()) {
            normalizedName = "批次 " + (taskDisplayCode != null ? taskDisplayCode : batch.getDisplayCode());
        }
        batch.setName(normalizedName.length() > 128 ? normalizedName.substring(0, 128) : normalizedName);
        return batchRepo.save(batch);
    }

    /**
     * Generates a human-friendly batch display code in MMDD-XX format,
     * sequenced by the number of batches the teacher created today.
     */
    private synchronized String generateBatchDisplayCode(Long teacherId) {
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate date = java.time.LocalDate.now(zone);
        java.time.Instant dayStart = date.atStartOfDay(zone).toInstant();
        long todayCount = batchRepo.countByTeacherIdAndCreatedAtAfter(teacherId, dayStart);
        return String.format("%02d%02d-%02d", date.getMonthValue(), date.getDayOfMonth(), todayCount + 1);
    }

    /**
     * Generates a human-friendly display code in MMDD-XX format.
     * Example: 0610-01 means June 10, first task of the day for this teacher.
     */
    private synchronized String generateDisplayCode(Long teacherId, Instant createdAt) {
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate date = createdAt.atZone(zone).toLocalDate();
        java.time.Instant dayStart = date.atStartOfDay(zone).toInstant();

        long todayCount = taskRepo.countByTeacherIdAndCreatedAtAfter(teacherId, dayStart);
        int seq = (int) todayCount + 1;

        return String.format("%02d%02d-%02d", date.getMonthValue(), date.getDayOfMonth(), seq);
    }

    /**
     * Safely get cell value as string regardless of cell type.
     */
    private String getCellStringValue(org.apache.poi.ss.usermodel.Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case BLANK -> "";
            default -> "";
        };
    }

    /**
     * Calculate display width for Excel column sizing.
     * CJK (Chinese/Japanese/Korean) characters are approximately 2 units wide,
     * while Latin/digit characters are 1 unit wide.
     */
    private int calcDisplayWidth(String text) {
        if (text == null || text.isEmpty()) return 0;
        int width = 0;
        for (char c : text.toCharArray()) {
            // CJK Unified Ideographs range and common fullwidth characters
            if ((c >= 0x4E00 && c <= 0x9FFF) ||   // CJK Unified Ideographs
                (c >= 0x3400 && c <= 0x4DBF) ||   // CJK Extension A
                (c >= 0x3000 && c <= 0x303F) ||   // CJK Symbols and Punctuation
                (c >= 0xFF00 && c <= 0xFFEF) ||   // Fullwidth Forms
                (c >= 0x2000 && c <= 0x206F)) {   // General Punctuation
                width += 2;
            } else {
                width += 1;
            }
        }
        return width;
    }
}
