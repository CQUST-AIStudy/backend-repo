package com.tap.backend.service.grading.animation;

import java.util.List;

/**
 * 完整代码上下文：包含错误区域的完整代码片段及高亮范围。
 */
public record CodeContext(
        List<String> fullLines,
        int anchorStartLine,
        int anchorEndLine,
        int highlightStartLine,
        int highlightEndLine,
        String source
) {
    /** 兼容旧调用：默认来源为报告原文。 */
    public CodeContext(List<String> fullLines, int anchorStartLine, int anchorEndLine,
                       int highlightStartLine, int highlightEndLine) {
        this(fullLines, anchorStartLine, anchorEndLine, highlightStartLine, highlightEndLine, "raw");
    }

    public CodeContext withSource(String newSource) {
        return new CodeContext(fullLines, anchorStartLine, anchorEndLine,
                highlightStartLine, highlightEndLine, newSource);
    }
    /**
     * 返回完整代码文本。
     */
    public String fullCode() {
        return String.join("\n", fullLines);
    }

    /**
     * 返回 anchor_text 在完整代码中的相对起始行（从 1 开始）。
     */
    public int relativeAnchorLine() {
        return anchorStartLine - highlightStartLine + 1;
    }
}
