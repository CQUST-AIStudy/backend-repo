package com.tap.backend.service.grading.animation.execution;

import java.util.List;
import java.util.Map;

/**
 * 代码执行轨迹结果。
 *
 * @param success      是否成功生成执行轨迹
 * @param language     语言
 * @param sourceCode   源代码
 * @param errorMessage 错误信息（失败时）
 * @param stdout       程序标准输出
 * @param stderr       程序标准错误
 * @param steps        执行步骤列表
 */
public record ExecutionTrace(
        boolean success,
        String language,
        String sourceCode,
        String errorMessage,
        String stdout,
        String stderr,
        List<TraceStep> steps
) {
    public static ExecutionTrace failed(String language, String sourceCode, String errorMessage) {
        return new ExecutionTrace(false, language, sourceCode, errorMessage, "", "", List.of());
    }

    /**
     * 将执行轨迹转换为前端播放器可消费的 Map 列表。
     */
    public List<Map<String, Object>> toFrameList() {
        return steps.stream()
                .map(TraceStep::toMap)
                .toList();
    }

    /**
     * 帧序列是否包含任何可可视化状态（非空 nodes / variables / memory）。
     * <p>
     * 真实执行「成功」但插桩捕获不到变量时（如结构体/指针类代码），
     * 前端画布会全程空白，此时应回退 LLM 结构化步骤动画。
     */
    public static boolean hasVisualizableFrames(List<?> frames) {
        if (frames == null || frames.isEmpty()) {
            return false;
        }
        for (Object frame : frames) {
            if (!(frame instanceof Map<?, ?> map)) {
                continue;
            }
            if (map.get("state") instanceof Map<?, ?> state
                    && state.get("nodes") instanceof List<?> nodes && !nodes.isEmpty()) {
                return true;
            }
            if (map.get("variables") instanceof Map<?, ?> vars && !vars.isEmpty()) {
                return true;
            }
            if (map.get("memory") instanceof List<?> memory && !memory.isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
