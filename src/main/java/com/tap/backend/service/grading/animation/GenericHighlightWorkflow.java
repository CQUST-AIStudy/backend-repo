package com.tap.backend.service.grading.animation;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 通用高亮工作流：无法归类时的兜底方案。
 */
@Component
public class GenericHighlightWorkflow {

    public AnimationResult generate(AnimationCandidate candidate, int index) {
        CodeContext ctx = candidate.codeContext();
        String anchor = candidate.anchor();

        Map<String, Object> frame = Map.of(
                "order", 1,
                "type", "highlight",
                "label", "错误位置",
                "content", anchor,
                "fullCode", ctx == null ? anchor : ctx.fullCode(),
                "anchorLine", ctx == null ? 1 : ctx.relativeAnchorLine()
        );

        return new AnimationResult(
                AnimationWorkflow.GENERIC_HIGHLIGHT.name(),
                "错误提示",
                candidate.note(),
                List.of(frame),
                Map.of(
                        "errorType", "GENERIC",
                        "sourceCode", ctx == null ? anchor : ctx.fullCode(),
                        "errorLine", ctx == null ? 1 : ctx.relativeAnchorLine()
                )
        );
    }
}
