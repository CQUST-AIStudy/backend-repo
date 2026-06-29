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
}
