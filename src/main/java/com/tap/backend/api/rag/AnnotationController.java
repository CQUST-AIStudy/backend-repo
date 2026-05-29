package com.tap.backend.api.rag;

import com.tap.backend.domain.rag.DocChunkAnnotationEntity;
import com.tap.backend.rag.DocChunkAnnotationService;
import com.tap.backend.service.CourseSpaceService;
import com.tap.backend.security.PrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.common.api.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class AnnotationController {

    private final DocChunkAnnotationService annotationService;
    private final CourseSpaceService courseSpaceService;
    private final PrincipalResolver principalResolver;

    public AnnotationController(DocChunkAnnotationService annotationService,
                                 CourseSpaceService courseSpaceService,
                                 PrincipalResolver principalResolver) {
        this.annotationService = annotationService;
        this.courseSpaceService = courseSpaceService;
        this.principalResolver = principalResolver;
    }

    public record CreateAnnotationRequest(Long chunkId, String annotationType, String note) {}

    @PostMapping("/api/course-spaces/{id}/annotations")
    public ApiResponse<Map<String, Object>> createAnnotation(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long courseSpaceId,
            @RequestBody CreateAnnotationRequest req) {
        var resolved = principalResolver.resolve(principal);
        courseSpaceService.requireOwnedSpace(courseSpaceId, resolved.userId());
        DocChunkAnnotationEntity entity = annotationService.create(
                req.chunkId(), req.annotationType(), req.note(), resolved.userId());
        return ApiResponse.of(toMap(entity));
    }

    @GetMapping("/api/course-spaces/{id}/annotations")
    public ApiResponse<List<Map<String, Object>>> listAnnotations(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long courseSpaceId) {
        var resolved = principalResolver.resolve(principal);
        courseSpaceService.requireOwnedSpace(courseSpaceId, resolved.userId());
        List<DocChunkAnnotationEntity> annotations = annotationService.listByCourseSpace(courseSpaceId);
        return ApiResponse.of(annotations.stream().map(this::toMap).toList());
    }

    @DeleteMapping("/api/annotations/{annotationId}")
    public ApiResponse<Void> deleteAnnotation(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("annotationId") Long annotationId) {
        var resolved = principalResolver.resolve(principal);
        annotationService.delete(annotationId, resolved.userId());
        return ApiResponse.of(null);
    }

    private Map<String, Object> toMap(DocChunkAnnotationEntity e) {
        return Map.of(
                "id", e.getId(),
                "chunkId", e.getChunkId(),
                "annotationType", e.getAnnotationType(),
                "note", e.getNote() != null ? e.getNote() : "",
                "teacherId", e.getTeacherId(),
                "createdAt", e.getCreatedAt().toString()
        );
    }
}
