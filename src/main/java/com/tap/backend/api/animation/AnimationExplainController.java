package com.tap.backend.api.animation;

import com.tap.backend.security.StudentPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.animation.AnimationExplainService;
import com.tap.backend.service.animation.AnimationStylePresets;
import com.tap.common.api.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 学生端：动画讲解（HTML 动画 + TTS 配音）。 */
@RestController
@RequestMapping("/api/student/animation-explain")
public class AnimationExplainController {

    private final AnimationExplainService explainService;
    private final StudentPrincipalResolver studentResolver;

    public AnimationExplainController(AnimationExplainService explainService,
                                      StudentPrincipalResolver studentResolver) {
        this.explainService = explainService;
        this.studentResolver = studentResolver;
    }

    @GetMapping("/styles")
    public ApiResponse<List<Map<String, Object>>> styles() {
        return ApiResponse.of(AnimationStylePresets.listDto());
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@AuthenticationPrincipal UserPrincipal principal) {
        long userId = studentResolver.requireStudent(principal).userId();
        return ApiResponse.of(explainService.listByUser(userId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@AuthenticationPrincipal UserPrincipal principal,
                                                   @PathVariable("id") Long id) {
        long userId = studentResolver.requireStudent(principal).userId();
        return ApiResponse.of(explainService.detail(userId, id));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@AuthenticationPrincipal UserPrincipal principal,
                                                   @RequestBody CreateRequest body) {
        long userId = studentResolver.requireStudent(principal).userId();
        return ApiResponse.of(explainService.create(userId, body.topic(), body.style()));
    }

    public record CreateRequest(String topic, String style) {}
}
