package com.tap.backend.service.grading.animation;

import java.util.List;
import java.util.Map;

/**
 * 错误演示动画的统一返回结果。
 */
public record AnimationResult(
        String workflow,
        String title,
        String explanation,
        List<?> frames,
        Map<String, Object> metadata
) {
    public AnimationResult {
        frames = frames == null ? List.of() : List.copyOf(frames);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
