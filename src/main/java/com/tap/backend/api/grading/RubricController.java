package com.tap.backend.api.grading;

import com.tap.backend.domain.grading.GradingRubricEntity;
import com.tap.backend.domain.grading.RubricDimensionEntity;
import com.tap.backend.security.TeacherPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.RubricDraftService;
import com.tap.backend.service.RubricService;
import com.tap.backend.service.RubricDraftService.DraftDimension;
import com.tap.backend.service.RubricService.DimensionInput;
import com.tap.common.api.ApiResponse;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
@RequestMapping("/api/grading/rubrics")
public class RubricController {

    private final RubricService rubricService;
    private final RubricDraftService rubricDraftService;
    private final TeacherPrincipalResolver teacherPrincipalResolver;

    public RubricController(
            RubricService rubricService,
            RubricDraftService rubricDraftService,
            TeacherPrincipalResolver teacherPrincipalResolver
    ) {
        this.rubricService = rubricService;
        this.rubricDraftService = rubricDraftService;
        this.teacherPrincipalResolver = teacherPrincipalResolver;
    }

    @PostMapping("/draft")
    public ResponseEntity<?> draft(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("templateFile") MultipartFile templateFile,
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "name", required = false) String name
    ) {
        teacherPrincipalResolver.requireTeacherId(principal);
        try {
            var draft = rubricDraftService.generateDraft(templateFile, subject, name);
            return ResponseEntity.ok(ApiResponse.of(Map.of(
                    "name", draft.name(),
                    "subject", draft.subject(),
                    "description", draft.description(),
                    "customPrompt", draft.customPrompt(),
                    "dimensions", draft.dimensions().stream().map(this::toDraftDimensionDto).toList()
            )));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/from-image")
    public ResponseEntity<?> createFromImage(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("image") MultipartFile image,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "customPrompt", required = false) String customPrompt
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        try {
            var rubric = rubricService.createFromImage(
                    teacherId, image, name, subject, description, customPrompt);
            return ResponseEntity.ok(ApiResponse.of(toDto(rubric)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody RubricRequest req
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        try {
            var rubric = rubricService.create(
                    teacherId,
                    req.name(),
                    req.subject(),
                    req.description(),
                    req.customPrompt(),
                    toDimensionInputs(req.dimensions())
            );
            return ResponseEntity.ok(ApiResponse.of(toDto(rubric)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String subject
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        var rubrics = rubricService.listByTeacher(teacherId, subject);
        return ResponseEntity.ok(ApiResponse.of(rubrics.stream().map(this::toDto).toList()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        try {
            var rubric = rubricService.getDetail(id, teacherId);
            return ResponseEntity.ok(ApiResponse.of(toDto(rubric)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody RubricRequest req
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        try {
            var rubric = rubricService.update(
                    id,
                    teacherId,
                    req.name(),
                    req.subject(),
                    req.description(),
                    req.customPrompt(),
                    toDimensionInputs(req.dimensions())
            );
            return ResponseEntity.ok(ApiResponse.of(toDto(rubric)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        }
    }

    private List<DimensionInput> toDimensionInputs(List<DimensionDto> dimensions) {
        if (dimensions == null) {
            return List.of();
        }
        return dimensions.stream()
                .map(d -> new DimensionInput(d.name(), d.description(), d.maxScore(), d.weight()))
                .toList();
    }

    private Map<String, Object> toDto(GradingRubricEntity rubric) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", rubric.getId());
        dto.put("name", rubric.getName());
        dto.put("subject", rubric.getSubject() != null ? rubric.getSubject() : "");
        dto.put("description", rubric.getDescription() != null ? rubric.getDescription() : "");
        dto.put("customPrompt", rubric.getCustomPrompt() != null ? rubric.getCustomPrompt() : "");
        dto.put("imageObjectKey", rubric.getImageObjectKey() != null ? rubric.getImageObjectKey() : "");
        dto.put("imageParsedAt", rubric.getImageParsedAt() != null ? rubric.getImageParsedAt().toString() : "");
        dto.put("createdAt", rubric.getCreatedAt().toString());
        dto.put("dimensions", rubric.getDimensions().stream().map(this::toDimensionDto).toList());
        return dto;
    }

    private Map<String, Object> toDimensionDto(RubricDimensionEntity dimension) {
        return Map.of(
                "id", dimension.getId(),
                "name", dimension.getName(),
                "description", dimension.getDescription() != null ? dimension.getDescription() : "",
                "maxScore", dimension.getMaxScore(),
                "weight", dimension.getWeight(),
                "sortOrder", dimension.getSortOrder()
        );
    }

    private Map<String, Object> toDraftDimensionDto(DraftDimension dimension) {
        return Map.of(
                "name", dimension.name(),
                "description", dimension.description() != null ? dimension.description() : "",
                "maxScore", dimension.maxScore(),
                "weight", dimension.weight()
        );
    }

    public record RubricRequest(
            String name,
            String subject,
            String description,
            String customPrompt,
            List<DimensionDto> dimensions
    ) {}

    public record DimensionDto(
            String name,
            String description,
            BigDecimal maxScore,
            Integer weight
    ) {}
}
