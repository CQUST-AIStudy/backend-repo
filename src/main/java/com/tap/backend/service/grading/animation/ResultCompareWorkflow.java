package com.tap.backend.service.grading.animation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 图文对比 + 简单动画工作流：适合结果/数据类错误。
 */
@Component
public class ResultCompareWorkflow {

    public AnimationResult generate(AnimationCandidate candidate, int index) {
        ProblemContext problem = candidate.problemContext();
        String expected = problem != null && problem.expectedOutput() != null
                ? problem.expectedOutput()
                : "预期结果（未配置）";
        String actual = extractActualOutput(candidate);

        List<Map<String, Object>> frames = new ArrayList<>();
        frames.add(Map.of(
                "order", 1,
                "type", "expected",
                "label", "预期结果",
                "content", expected
        ));
        frames.add(Map.of(
                "order", 2,
                "type", "actual",
                "label", "实际结果",
                "content", actual
        ));
        frames.add(Map.of(
                "order", 3,
                "type", "analysis",
                "label", "差异分析",
                "content", candidate.note()
        ));

        return new AnimationResult(
                AnimationWorkflow.RESULT_COMPARE.name(),
                "结果对比分析",
                candidate.note(),
                frames,
                Map.of(
                        "errorType", "RESULT_MISMATCH",
                        "expected", expected,
                        "actual", actual
                )
        );
    }

    private String extractActualOutput(AnimationCandidate candidate) {
        String content = candidate.evidenceBlock().getContent();
        if (content == null || content.isBlank()) {
            return "实际结果（未识别）";
        }
        // 简单策略：返回证据块内容的前 500 字符
        return content.length() > 500 ? content.substring(0, 500) + "..." : content;
    }
}
