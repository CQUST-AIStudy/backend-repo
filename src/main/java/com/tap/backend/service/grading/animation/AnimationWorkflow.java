package com.tap.backend.service.grading.animation;

/**
 * 错误演示动画的工作流类型。
 */
public enum AnimationWorkflow {
    /**
     * Python Tutor 式执行可视化：适合代码类错误（数组越界、指针、死循环等）。
     */
    PYTHON_TUTOR,

    /**
     * AI 生成 HTML 动画：适合概念/原理类错误。
     */
    HTML_ANIMATION,

    /**
     * 图文对比 + 简单动画：适合结果/数据类错误。
     */
    RESULT_COMPARE,

    /**
     * 通用高亮：无法归类时的兜底方案。
     */
    GENERIC_HIGHLIGHT
}
