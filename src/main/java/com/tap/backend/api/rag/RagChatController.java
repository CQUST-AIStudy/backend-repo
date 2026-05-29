package com.tap.backend.api.rag;

import com.tap.backend.domain.rag.CourseSpaceEntity;
import com.tap.backend.domain.rag.QaLogEntity;
import com.tap.backend.rag.RagOrchestratorService;
import com.tap.backend.rag.lc4j.service.TeacherRagCitationService;
import com.tap.backend.repo.QaLogRepository;
import com.tap.backend.security.PrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.CourseSpaceService;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/rag")
public class RagChatController {

    private static final Logger log = LoggerFactory.getLogger(RagChatController.class);
    private static final String MAINTENANCE_MESSAGE = "RAG 功能正在维护中，请稍后再试。";
    private static final String SYSTEM_ERROR_MESSAGE = "抱歉，RAG 系统出现异常，请稍后重试。";

    private final QaLogRepository qaLogRepo;
    private final CourseSpaceService courseSpaceService;
    private final PrincipalResolver principalResolver;
    private final RagOrchestratorService ragOrchestrator;
    private final TeacherRagCitationService teacherRagCitationService;

    @Value("${tap.rag.maintenance-mode:false}")
    private boolean maintenanceMode;

    public RagChatController(QaLogRepository qaLogRepo,
                             CourseSpaceService courseSpaceService,
                             PrincipalResolver principalResolver,
                             RagOrchestratorService ragOrchestrator,
                             TeacherRagCitationService teacherRagCitationService) {
        this.qaLogRepo = qaLogRepo;
        this.courseSpaceService = courseSpaceService;
        this.principalResolver = principalResolver;
        this.ragOrchestrator = ragOrchestrator;
        this.teacherRagCitationService = teacherRagCitationService;
    }

    public record RagChatRequest(Long courseSpaceId, String query, String mode, Long classId, String className) {}

    record FeedbackRequest(Long qaLogId, Integer feedback) {}

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chat(@AuthenticationPrincipal UserPrincipal principal,
                                                      @RequestBody RagChatRequest request) {
        var resolved = principalResolver.resolve(principal);
        return chatForReadableSpace(request, resolved.userId(), false, null, null);
    }

    ResponseEntity<StreamingResponseBody> chatForReadableSpace(RagChatRequest request,
                                                               Long requesterUserId,
                                                               boolean allowPublicRead,
                                                               String studentId,
                                                               String studentNum) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            return textStream("请输入有效的问题。");
        }
        if (request.courseSpaceId() == null) {
            return textStream("请选择课程空间。");
        }

        CourseSpaceEntity courseSpace = studentNum != null
                ? courseSpaceService.requireReadableSpaceForStudent(request.courseSpaceId(), studentNum)
                : courseSpaceService.requireReadableSpace(
                        request.courseSpaceId(), requesterUserId, allowPublicRead);
        CourseSpaceService.RagChatScope chatScope = studentNum != null
                ? courseSpaceService.resolveStudentRagScope(courseSpace, studentNum, request.classId())
                : courseSpaceService.resolveTeacherRagScope(courseSpace, requesterUserId, request.classId());

        if (maintenanceMode) {
            return maintenanceFallback(request, courseSpace, studentId);
        }

        StreamingResponseBody body = outputStream -> {
            try {
                RagOrchestratorService.RagResult result = ragOrchestrator.execute(
                        courseSpace.getId(), request.query(), request.mode(), courseSpace, chatScope, outputStream);
                saveQaLog(request, courseSpace, result, studentId);
            } catch (Exception e) {
                log.error("[RAG] orchestrator failed: {}", e.getMessage(), e);
                outputStream.write(SYSTEM_ERROR_MESSAGE.getBytes(StandardCharsets.UTF_8));
                outputStream.write("\n\n<!--CITATIONS:[]-->".getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                saveQaLogFallback(request, courseSpace, SYSTEM_ERROR_MESSAGE, studentId, "strict", "error");
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    @PostMapping("/feedback")
    public ResponseEntity<?> submitFeedback(@AuthenticationPrincipal UserPrincipal principal,
                                            @RequestBody FeedbackRequest request) {
        var resolved = principalResolver.resolve(principal);
        if (request == null || request.qaLogId() == null || request.feedback() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "qaLogId and feedback are required"));
        }

        QaLogEntity logEntity = qaLogRepo.findById(request.qaLogId()).orElse(null);
        if (logEntity == null) {
            return ResponseEntity.status(404).body(Map.of("error", "qa_log not found"));
        }

        courseSpaceService.requireOwnedSpace(logEntity.getCourseSpaceId(), resolved.userId());
        logEntity.setFeedback(request.feedback());
        qaLogRepo.save(logEntity);
        return ResponseEntity.ok(Map.of("success", true));
    }

    private ResponseEntity<StreamingResponseBody> maintenanceFallback(RagChatRequest request,
                                                                      CourseSpaceEntity courseSpace,
                                                                      String studentId) {
        StreamingResponseBody body = outputStream -> {
            outputStream.write(MAINTENANCE_MESSAGE.getBytes(StandardCharsets.UTF_8));
            outputStream.write(("\n\n<!--CITATIONS:" + teacherRagCitationService.toJson(Collections.emptyList()) + "-->")
                    .getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        };
        saveQaLogFallback(request, courseSpace, MAINTENANCE_MESSAGE, studentId, "strict", "maintenance");
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    private ResponseEntity<StreamingResponseBody> textStream(String text) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(outputStream -> {
                    outputStream.write(text.getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                });
    }

    private void saveQaLog(RagChatRequest request, CourseSpaceEntity courseSpace,
                           RagOrchestratorService.RagResult result, String studentId) {
        try {
            QaLogEntity logEntity = new QaLogEntity();
            logEntity.setCourseSpace(courseSpace);
            logEntity.setStudentId(studentId == null || studentId.isBlank() ? "anonymous" : studentId);
            logEntity.setQuery(request.query());
            logEntity.setRetrievedChunkIds(result.retrievedChunkIds().toString());
            logEntity.setTop1Score(result.top1Score());
            logEntity.setAnswerText(result.answerText());
            logEntity.setCitationsJson(teacherRagCitationService.toJson(result.citations()));
            logEntity.setMode(result.effectiveMode());
            logEntity.setCoverageScore(result.coverageScore());
            logEntity.setUsedWeb(result.usedWeb());
            logEntity.setIntentType(result.intentType());
            qaLogRepo.save(logEntity);
        } catch (Exception e) {
            log.warn("[RAG] failed to save qa_log: {}", e.getMessage());
        }
    }

    private void saveQaLogFallback(RagChatRequest request, CourseSpaceEntity courseSpace,
                                   String answerText, String studentId,
                                   String mode, String intentType) {
        try {
            QaLogEntity logEntity = new QaLogEntity();
            logEntity.setCourseSpace(courseSpace);
            logEntity.setStudentId(studentId == null || studentId.isBlank() ? "anonymous" : studentId);
            logEntity.setQuery(request.query());
            logEntity.setRetrievedChunkIds("[]");
            logEntity.setTop1Score(0.0);
            logEntity.setAnswerText(answerText);
            logEntity.setCitationsJson("[]");
            logEntity.setMode(mode);
            logEntity.setCoverageScore(0.0);
            logEntity.setUsedWeb(false);
            logEntity.setIntentType(intentType);
            qaLogRepo.save(logEntity);
        } catch (Exception e) {
            log.warn("[RAG] failed to save qa_log: {}", e.getMessage());
        }
    }

}
