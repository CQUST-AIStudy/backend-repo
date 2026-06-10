package com.tap.backend.api.grading;

import com.tap.backend.domain.grading.TeacherSignatureEntity;
import com.tap.backend.security.TeacherPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.TeacherSignatureService;
import com.tap.common.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/grading/signatures")
public class TeacherSignatureController {

    private final TeacherSignatureService signatureService;
    private final TeacherPrincipalResolver teacherPrincipalResolver;

    public TeacherSignatureController(TeacherSignatureService signatureService,
                                      TeacherPrincipalResolver teacherPrincipalResolver) {
        this.signatureService = signatureService;
        this.teacherPrincipalResolver = teacherPrincipalResolver;
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal UserPrincipal principal) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        List<TeacherSignatureEntity> signatures = signatureService.listByTeacher(teacherId);
        return ResponseEntity.ok(ApiResponse.of(signatures.stream().map(this::toDto).toList()));
    }

    @PostMapping
    public ResponseEntity<?> add(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody SignatureRequest req
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        try {
            TeacherSignatureEntity entity = signatureService.add(teacherId, req.signature());
            return ResponseEntity.ok(ApiResponse.of(toDto(entity)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> remove(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        Long teacherId = teacherPrincipalResolver.requireTeacherId(principal);
        signatureService.remove(teacherId, id);
        return ResponseEntity.ok(ApiResponse.of(Map.of("deleted", true)));
    }

    private Map<String, Object> toDto(TeacherSignatureEntity entity) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", entity.getId());
        dto.put("signature", entity.getSignature());
        dto.put("createdAt", entity.getCreatedAt().toString());
        return dto;
    }

    public record SignatureRequest(String signature) {}
}
