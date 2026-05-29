package com.tap.backend.api.rag;

import com.tap.backend.domain.rag.CourseSpaceDocumentEntity;
import com.tap.backend.domain.rag.CourseSpaceEntity;
import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.security.PrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.CourseSpaceService;
import com.tap.backend.service.UserService;
import com.tap.common.api.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/course-spaces")
public class CourseSpaceController {

    private final CourseSpaceService courseSpaceService;
    private final UserService userService;
    private final com.tap.backend.repo.DocChunkRepository docChunkRepo;
    private final com.tap.backend.service.RagDocumentProcessor ragDocProcessor;
    private final PrincipalResolver principalResolver;

    public CourseSpaceController(CourseSpaceService courseSpaceService, UserService userService,
                                  com.tap.backend.repo.DocChunkRepository docChunkRepo,
                                  com.tap.backend.service.RagDocumentProcessor ragDocProcessor,
                                  PrincipalResolver principalResolver) {
        this.courseSpaceService = courseSpaceService;
        this.userService = userService;
        this.docChunkRepo = docChunkRepo;
        this.ragDocProcessor = ragDocProcessor;
        this.principalResolver = principalResolver;
    }

    public record CreateSpaceRequest(String name, String term, String courseName, String description,
                                     String defaultMode, Boolean allowWebSearch,
                                     Boolean requireCitation, String docVisibility,
                                     List<Long> classIds) {}
    public record UpdateSpaceRequest(String name, String term, String courseName, String description,
                                      String defaultMode, Boolean allowWebSearch, Boolean requireCitation,
                                      String docVisibility, List<Long> classIds) {}

    @PostMapping
    public ApiResponse<Map<String, Object>> createSpace(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody CreateSpaceRequest req) {
        var resolved = principalResolver.resolve(principal);
        UserEntity teacher = userService.requireById(resolved.userId());
        CourseSpaceEntity cs = courseSpaceService.createSpace(
                teacher, req.name(), req.term(), req.courseName(), req.description(),
                req.defaultMode(), req.allowWebSearch(), req.requireCitation(), req.docVisibility(),
                req.classIds());
        return ApiResponse.of(spaceToMap(cs));
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listSpaces(
            @AuthenticationPrincipal UserPrincipal principal) {
        var resolved = principalResolver.resolve(principal);
        List<CourseSpaceEntity> spaces = courseSpaceService.listSpaces(resolved.userId());
        return ApiResponse.of(spaces.stream().map(this::spaceToMap).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getSpace(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long id) {
        var resolved = principalResolver.resolve(principal);
        CourseSpaceEntity cs = courseSpaceService.requireOwnedSpace(id, resolved.userId());
        return ApiResponse.of(spaceToMap(cs));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> updateSpace(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long id,
            @RequestBody UpdateSpaceRequest req) {
        var resolved = principalResolver.resolve(principal);
        CourseSpaceEntity cs = courseSpaceService.updateSpace(
                id, resolved.userId(), req.name(), req.term(), req.courseName(), req.description(),
                req.defaultMode(), req.allowWebSearch(), req.requireCitation(), req.docVisibility(),
                req.classIds());
        return ApiResponse.of(spaceToMap(cs));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteSpace(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long id) {
        var resolved = principalResolver.resolve(principal);
        courseSpaceService.deleteSpace(id, resolved.userId());
        return ApiResponse.of(null);
    }

    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> uploadDocument(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long courseSpaceId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "docType", defaultValue = "textbook") String docType) throws Exception {
        var resolved = principalResolver.resolve(principal);
        UserEntity teacher = userService.requireById(resolved.userId());
        CourseSpaceDocumentEntity csDoc = courseSpaceService.uploadDocument(courseSpaceId, teacher, file, docType);
        ragDocProcessor.processAsync(csDoc.getId());
        return ApiResponse.of(docToMap(csDoc));
    }

    @GetMapping("/{id}/documents")
    public ApiResponse<List<Map<String, Object>>> listDocuments(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long courseSpaceId) {
        var resolved = principalResolver.resolve(principal);
        courseSpaceService.requireOwnedSpace(courseSpaceId, resolved.userId());
        List<CourseSpaceDocumentEntity> docs = courseSpaceService.listDocuments(courseSpaceId);
        return ApiResponse.of(docs.stream().map(this::docToMap).toList());
    }

    @GetMapping("/{id}/documents/status-summary")
    public ApiResponse<Map<String, Object>> getDocumentStatusSummary(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long courseSpaceId) {
        var resolved = principalResolver.resolve(principal);
        courseSpaceService.requireOwnedSpace(courseSpaceId, resolved.userId());
        List<CourseSpaceDocumentEntity> docs = courseSpaceService.listDocuments(courseSpaceId);
        return ApiResponse.of(buildDocumentStatusSummary(courseSpaceId, docs));
    }

    @PostMapping("/{id}/documents/reprocess")
    public ApiResponse<Map<String, Object>> reprocessAllDocuments(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long courseSpaceId) {
        var resolved = principalResolver.resolve(principal);
        courseSpaceService.requireOwnedSpace(courseSpaceId, resolved.userId());
        List<CourseSpaceDocumentEntity> docs = courseSpaceService.listDocuments(courseSpaceId);
        List<Long> processingIds = docs.stream()
                .filter(doc -> "PROCESSING".equalsIgnoreCase(doc.getStatus()))
                .map(CourseSpaceDocumentEntity::getId)
                .toList();
        List<CourseSpaceDocumentEntity> queued =
                courseSpaceService.queueAllDocumentsForReprocess(courseSpaceId, resolved.userId());
        for (CourseSpaceDocumentEntity doc : queued) {
            ragDocProcessor.processAsync(doc.getId());
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("courseSpaceId", courseSpaceId);
        result.put("requestedCount", queued.size());
        result.put("skippedProcessingCount", processingIds.size());
        result.put("queuedDocumentIds", queued.stream().map(CourseSpaceDocumentEntity::getId).toList());
        result.put("processingDocumentIds", processingIds);
        return ApiResponse.of(result);
    }

    @PostMapping("/{id}/documents/{docId}/reprocess")
    public ApiResponse<Map<String, Object>> reprocessDocument(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long courseSpaceId,
            @PathVariable("docId") Long courseSpaceDocId) {
        var resolved = principalResolver.resolve(principal);
        CourseSpaceDocumentEntity doc =
                courseSpaceService.queueDocumentReprocess(courseSpaceId, courseSpaceDocId, resolved.userId());
        boolean queued = !"PROCESSING".equalsIgnoreCase(doc.getStatus());
        if (queued) {
            ragDocProcessor.processAsync(doc.getId());
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("courseSpaceId", courseSpaceId);
        result.put("courseSpaceDocumentId", doc.getId());
        result.put("documentId", doc.getDocumentId());
        result.put("queued", queued);
        result.put("status", queued ? "PENDING" : doc.getStatus());
        return ApiResponse.of(result);
    }

    @PostMapping("/{id}/rebuild-bm25")
    public ApiResponse<Map<String, Object>> rebuildBm25Index(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long courseSpaceId) {
        var resolved = principalResolver.resolve(principal);
        courseSpaceService.rebuildBm25Index(courseSpaceId, resolved.userId());
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("courseSpaceId", courseSpaceId);
        result.put("rebuilt", true);
        return ApiResponse.of(result);
    }

    @GetMapping("/{id}/chunks")
    public ApiResponse<List<Map<String, Object>>> listChunks(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long courseSpaceId) {
        var resolved = principalResolver.resolve(principal);
        courseSpaceService.requireOwnedSpace(courseSpaceId, resolved.userId());
        List<com.tap.backend.domain.rag.DocChunkEntity> chunks = docChunkRepo.findAllByCourseSpaceId(courseSpaceId);
        return ApiResponse.of(chunks.stream()
                .filter(c -> "parent".equals(c.getChunkType()))
                .map(c -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("documentId", c.getDocumentId());
                    m.put("chapterPath", c.getChapterPath() != null ? c.getChapterPath() : "");
                    m.put("pageRange", c.getPageRange() != null ? c.getPageRange() : "");
                    m.put("contentPreview", c.getContent() != null && c.getContent().length() > 200
                            ? c.getContent().substring(0, 200) + "..." : c.getContent());
                    return m;
                }).toList());
    }

    private Map<String, Object> spaceToMap(CourseSpaceEntity cs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", cs.getId());
        map.put("name", cs.getName());
        map.put("term", cs.getTerm() == null ? "" : cs.getTerm());
        map.put("courseName", cs.getCourseName() == null ? "" : cs.getCourseName());
        map.put("description", cs.getDescription() == null ? "" : cs.getDescription());
        map.put("defaultMode", cs.getDefaultMode());
        map.put("allowWebSearch", cs.getAllowWebSearch());
        map.put("requireCitation", cs.getRequireCitation());
        map.put("docVisibility", cs.getDocVisibility());
        map.put("boundClassIds", courseSpaceService.listBoundClassIds(cs.getId()));
        map.put("docCount", courseSpaceService.countDocuments(cs.getId()));
        map.put("createdAt", cs.getCreatedAt().toString());
        map.put("updatedAt", cs.getUpdatedAt().toString());
        return map;
    }

    private Map<String, Object> docToMap(CourseSpaceDocumentEntity doc) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", doc.getId());
        map.put("courseSpaceId", doc.getCourseSpaceId());
        map.put("documentId", doc.getDocumentId());
        map.put("docType", doc.getDocType() == null ? "" : doc.getDocType());
        map.put("status", doc.getStatus());
        map.put("chunkCount", doc.getChunkCount());
        map.put("errorMessage", doc.getErrorMessage());
        map.put("createdAt", doc.getCreatedAt().toString());
        return map;
    }

    private Map<String, Object> buildDocumentStatusSummary(Long courseSpaceId, List<CourseSpaceDocumentEntity> docs) {
        long readyCount = docs.stream().filter(doc -> "READY".equalsIgnoreCase(doc.getStatus())).count();
        long processingCount = docs.stream().filter(doc -> "PROCESSING".equalsIgnoreCase(doc.getStatus())).count();
        long pendingCount = docs.stream().filter(doc -> "PENDING".equalsIgnoreCase(doc.getStatus())).count();
        long failedCount = docs.stream().filter(doc -> "FAILED".equalsIgnoreCase(doc.getStatus())).count();
        int totalChunks = docs.stream().mapToInt(CourseSpaceDocumentEntity::getChunkCount).sum();

        Map<String, Object> counts = new java.util.LinkedHashMap<>();
        counts.put("total", docs.size());
        counts.put("ready", readyCount);
        counts.put("processing", processingCount);
        counts.put("pending", pendingCount);
        counts.put("failed", failedCount);
        counts.put("totalChunks", totalChunks);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("courseSpaceId", courseSpaceId);
        result.put("counts", counts);
        result.put("documents", docs.stream()
                .sorted(java.util.Comparator.comparing(CourseSpaceDocumentEntity::getCreatedAt).reversed())
                .map(this::docToMap)
                .toList());
        return result;
    }
}
